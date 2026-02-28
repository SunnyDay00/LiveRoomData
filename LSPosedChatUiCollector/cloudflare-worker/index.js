import { connect } from '@tidbcloud/serverless';

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, X-API-Key',
};

const CHAT_TABLE = 'chat_records';
const GIFT_TABLE = 'gift_price_catalog';
const BLACKLIST_TABLE = 'chat_blacklist_rules';
const MAX_INGEST_BATCH = 1000;

let schemaCache = {
  loadedAt: 0,
  tables: new Map(),
};

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method.toUpperCase();

    try {
      const authMode = resolveAuthMode(path, method);
      if (!authMode) {
        return json({ ok: false, error: 'not_found' }, 404);
      }
      if (!isAuthorized(request, env, authMode)) {
        return json({ ok: false, error: 'unauthorized' }, 401);
      }

      const dbUrl = (env.TIDB_DATABASE_URL || '').trim();
      if (!dbUrl) {
        return json({ ok: false, error: 'missing_tidb_database_url' }, 500);
      }
      const conn = connect({ url: dbUrl });

      if (path === '/v1/health' && method === 'GET') {
        return await handleHealth(conn);
      }
      if (path === '/v1/config/version' && method === 'GET') {
        return await handleConfigVersion(conn);
      }
      if (path === '/v1/config/snapshot' && method === 'GET') {
        return await handleConfigSnapshot(conn);
      }
      if (path === '/v1/chat/ingest' && method === 'POST') {
        return await handleChatIngest(conn, request);
      }
      if (path === '/v1/chat/list' && method === 'GET') {
        return await handleChatList(conn, url);
      }

      return json({ ok: false, error: 'not_found' }, 404);
    } catch (err) {
      return json({
        ok: false,
        error: 'internal_error',
        message: safeError(err),
      }, 500);
    }
  },
};

function resolveAuthMode(path, method) {
  if (method === 'GET' && (path === '/v1/health' || path === '/v1/config/version' || path === '/v1/config/snapshot' || path === '/v1/chat/list')) {
    return 'read';
  }
  if (method === 'POST' && path === '/v1/chat/ingest') {
    return 'write';
  }
  return '';
}

function isAuthorized(request, env, mode) {
  const reqKey = (request.headers.get('X-API-Key') || '').trim();
  const writeKey = (env.API_KEY_WRITE || '').trim();
  const readKey = (env.API_KEY_READ || '').trim();
  const expectedReadKey = readKey || writeKey;

  if (!reqKey) {
    return false;
  }
  if (mode === 'write') {
    return writeKey.length > 0 && reqKey === writeKey;
  }
  return expectedReadKey.length > 0 && reqKey === expectedReadKey;
}

async function handleHealth(conn) {
  await executeRows(conn, 'SELECT 1 AS ok');
  return json({ ok: true, data: { status: 'up', ts: nowIso() } }, 200);
}

async function handleConfigVersion(conn) {
  const giftMeta = await loadTableMeta(conn, GIFT_TABLE, {
    updatedColCandidates: ['updated_at', 'updatedAt', 'modified_at'],
    enabledColCandidates: ['enabled', 'is_enabled'],
  });
  const blacklistMeta = await loadTableMeta(conn, BLACKLIST_TABLE, {
    updatedColCandidates: ['updated_at', 'updatedAt', 'modified_at'],
    enabledColCandidates: ['enabled', 'is_enabled'],
  });

  const giftStats = await loadTableStats(conn, giftMeta);
  const blacklistStats = await loadTableStats(conn, blacklistMeta);
  const version = buildConfigVersion(giftStats, blacklistStats);

  return json({
    ok: true,
    data: {
      version,
      giftCount: giftStats.count,
      blacklistCount: blacklistStats.count,
    },
  }, 200);
}

async function handleConfigSnapshot(conn) {
  const giftMeta = await loadTableMeta(conn, GIFT_TABLE, {
    nameColCandidates: ['gift_name', 'giftName', 'name'],
    priceColCandidates: ['note_price', 'notePrice', 'price', 'unit_price'],
    updatedColCandidates: ['updated_at', 'updatedAt', 'modified_at'],
    enabledColCandidates: ['enabled', 'is_enabled'],
  });
  if (!giftMeta.nameCol || !giftMeta.priceCol) {
    return json({ ok: false, error: 'gift_table_columns_missing' }, 500);
  }

  const blacklistMeta = await loadTableMeta(conn, BLACKLIST_TABLE, {
    ruleColCandidates: ['rule_text', 'rule', 'content', 'text'],
    updatedColCandidates: ['updated_at', 'updatedAt', 'modified_at'],
    enabledColCandidates: ['enabled', 'is_enabled'],
  });
  if (!blacklistMeta.ruleCol) {
    return json({ ok: false, error: 'blacklist_table_columns_missing' }, 500);
  }

  const gifts = await loadGiftPrices(conn, giftMeta);
  const blacklistRules = await loadBlacklistRules(conn, blacklistMeta);

  const giftStats = await loadTableStats(conn, giftMeta);
  const blacklistStats = await loadTableStats(conn, blacklistMeta);
  const version = buildConfigVersion(giftStats, blacklistStats);

  return json({
    ok: true,
    data: {
      version,
      giftPrices: gifts,
      blacklistRules,
    },
  }, 200);
}

async function handleChatIngest(conn, request) {
  let payload;
  try {
    payload = await request.json();
  } catch (_err) {
    return json({ ok: false, error: 'invalid_json' }, 400);
  }

  const input = payload && Array.isArray(payload.records) ? payload.records : [];
  if (input.length === 0) {
    return json({ ok: true, data: { accepted: 0, inserted: 0 } }, 200);
  }
  if (input.length > MAX_INGEST_BATCH) {
    return json({ ok: false, error: 'batch_too_large', max: MAX_INGEST_BATCH }, 400);
  }

  const meta = await loadTableMeta(conn, CHAT_TABLE, {
    deviceIdColCandidates: ['device_id', 'deviceId'],
    roomIdColCandidates: ['room_id', 'roomId'],
    eventTimeColCandidates: ['event_time', 'eventTime'],
    chatTextColCandidates: ['chat_text', 'chatText', 'content'],
    senderColCandidates: ['sender'],
    receiverColCandidates: ['receiver'],
    giftNameColCandidates: ['gift_name', 'giftName'],
    giftQtyColCandidates: ['gift_qty', 'giftQty'],
    notePriceColCandidates: ['note_price', 'notePrice'],
    msgHashColCandidates: ['msg_hash', 'msgHash'],
  });

  const selected = [
    ['deviceId', meta.deviceIdCol],
    ['roomId', meta.roomIdCol],
    ['eventTime', meta.eventTimeCol],
    ['chatText', meta.chatTextCol],
    ['sender', meta.senderCol],
    ['receiver', meta.receiverCol],
    ['giftName', meta.giftNameCol],
    ['giftQty', meta.giftQtyCol],
    ['notePrice', meta.notePriceCol],
    ['msgHash', meta.msgHashCol],
  ].filter((item) => !!item[1]);

  if (selected.length === 0) {
    return json({ ok: false, error: 'chat_table_columns_missing' }, 500);
  }

  const rows = [];
  for (const rec of input) {
    const row = {};
    row.deviceId = sanitizeText(rec?.deviceId, 128);
    row.roomId = sanitizeText(rec?.roomId, 64);
    row.eventTime = sanitizeDateTime(rec?.eventTime);
    row.chatText = sanitizeText(rec?.chatText, 1000);
    row.sender = sanitizeText(rec?.sender, 128);
    row.receiver = sanitizeText(rec?.receiver, 128);
    row.giftName = sanitizeText(rec?.giftName, 128);
    row.giftQty = sanitizeInt(rec?.giftQty);
    row.notePrice = sanitizeText(rec?.notePrice, 64);
    row.msgHash = sanitizeText(rec?.msgHash, 128);

    if (!row.chatText) {
      continue;
    }
    if (!row.eventTime) {
      row.eventTime = nowLocalDateTime();
    }
    rows.push(row);
  }

  if (rows.length === 0) {
    return json({ ok: true, data: { accepted: input.length, inserted: 0 } }, 200);
  }

  const columnsSql = selected.map((item) => `\`${item[1]}\``).join(', ');
  const placeholders = [];
  const values = [];

  for (const row of rows) {
    placeholders.push(`(${selected.map(() => '?').join(', ')})`);
    for (const item of selected) {
      values.push(row[item[0]] ?? null);
    }
  }

  let sql = `INSERT INTO \`${CHAT_TABLE}\` (${columnsSql}) VALUES ${placeholders.join(', ')}`;
  if (meta.msgHashCol) {
    sql += ` ON DUPLICATE KEY UPDATE \`${meta.msgHashCol}\`=\`${meta.msgHashCol}\``;
  }

  await executeRows(conn, sql, values);

  return json({
    ok: true,
    data: {
      accepted: input.length,
      inserted: rows.length,
    },
  }, 200);
}

async function handleChatList(conn, url) {
  const limit = clampInt(url.searchParams.get('limit'), 1, 500, 100);
  const roomId = sanitizeText(url.searchParams.get('roomId') || '', 64);

  const meta = await loadTableMeta(conn, CHAT_TABLE, {
    idColCandidates: ['id'],
    deviceIdColCandidates: ['device_id', 'deviceId'],
    roomIdColCandidates: ['room_id', 'roomId'],
    eventTimeColCandidates: ['event_time', 'eventTime'],
    chatTextColCandidates: ['chat_text', 'chatText', 'content'],
    senderColCandidates: ['sender'],
    receiverColCandidates: ['receiver'],
    giftNameColCandidates: ['gift_name', 'giftName'],
    giftQtyColCandidates: ['gift_qty', 'giftQty'],
    notePriceColCandidates: ['note_price', 'notePrice'],
    msgHashColCandidates: ['msg_hash', 'msgHash'],
  });

  const selectCols = [];
  if (meta.idCol) selectCols.push(`\`${meta.idCol}\` AS id`);
  if (meta.deviceIdCol) selectCols.push(`\`${meta.deviceIdCol}\` AS deviceId`);
  if (meta.roomIdCol) selectCols.push(`\`${meta.roomIdCol}\` AS roomId`);
  if (meta.eventTimeCol) selectCols.push(`\`${meta.eventTimeCol}\` AS eventTime`);
  if (meta.chatTextCol) selectCols.push(`\`${meta.chatTextCol}\` AS chatText`);
  if (meta.senderCol) selectCols.push(`\`${meta.senderCol}\` AS sender`);
  if (meta.receiverCol) selectCols.push(`\`${meta.receiverCol}\` AS receiver`);
  if (meta.giftNameCol) selectCols.push(`\`${meta.giftNameCol}\` AS giftName`);
  if (meta.giftQtyCol) selectCols.push(`\`${meta.giftQtyCol}\` AS giftQty`);
  if (meta.notePriceCol) selectCols.push(`\`${meta.notePriceCol}\` AS notePrice`);
  if (meta.msgHashCol) selectCols.push(`\`${meta.msgHashCol}\` AS msgHash`);

  if (selectCols.length === 0) {
    return json({ ok: false, error: 'chat_table_columns_missing' }, 500);
  }

  const whereSql = [];
  const params = [];
  if (roomId && meta.roomIdCol) {
    whereSql.push(`\`${meta.roomIdCol}\` = ?`);
    params.push(roomId);
  }

  let sql = `SELECT ${selectCols.join(', ')} FROM \`${CHAT_TABLE}\``;
  if (whereSql.length > 0) {
    sql += ` WHERE ${whereSql.join(' AND ')}`;
  }

  if (meta.idCol) {
    sql += ` ORDER BY \`${meta.idCol}\` DESC`;
  } else if (meta.eventTimeCol) {
    sql += ` ORDER BY \`${meta.eventTimeCol}\` DESC`;
  }
  sql += ' LIMIT ?';
  params.push(limit);

  const rows = await executeRows(conn, sql, params);
  return json({ ok: true, data: { rows } }, 200);
}

async function loadGiftPrices(conn, meta) {
  let sql = `SELECT \`${meta.nameCol}\` AS giftName, CAST(\`${meta.priceCol}\` AS SIGNED) AS notePrice FROM \`${GIFT_TABLE}\``;
  if (meta.enabledCol) {
    sql += ` WHERE \`${meta.enabledCol}\` = 1`;
  }
  sql += ` ORDER BY \`${meta.nameCol}\` ASC LIMIT 10000`;
  const rows = await executeRows(conn, sql);
  const out = [];
  for (const row of rows) {
    const giftName = sanitizeText(row?.giftName, 128);
    if (!giftName) {
      continue;
    }
    const notePrice = Number(row?.notePrice || 0);
    out.push({ giftName, notePrice: Number.isFinite(notePrice) && notePrice > 0 ? Math.floor(notePrice) : 0 });
  }
  return out;
}

async function loadBlacklistRules(conn, meta) {
  let sql = `SELECT \`${meta.ruleCol}\` AS ruleText FROM \`${BLACKLIST_TABLE}\``;
  if (meta.enabledCol) {
    sql += ` WHERE \`${meta.enabledCol}\` = 1`;
  }
  sql += ` ORDER BY \`${meta.ruleCol}\` ASC LIMIT 10000`;
  const rows = await executeRows(conn, sql);
  const out = [];
  for (const row of rows) {
    const value = sanitizeText(row?.ruleText, 2000);
    if (value) {
      out.push(value);
    }
  }
  return out;
}

async function loadTableStats(conn, meta) {
  const whereSql = meta.enabledCol ? ` WHERE \`${meta.enabledCol}\` = 1` : '';
  let sql = `SELECT COUNT(*) AS c`;
  if (meta.updatedCol) {
    sql += `, MAX(\`${meta.updatedCol}\`) AS m`;
  }
  sql += ` FROM \`${meta.table}\`${whereSql}`;

  const rows = await executeRows(conn, sql);
  const row = rows[0] || {};
  const count = Number(row.c || 0);
  const maxUpdated = row.m ? String(row.m) : '';
  return {
    count: Number.isFinite(count) ? count : 0,
    maxUpdated,
  };
}

function buildConfigVersion(giftStats, blacklistStats) {
  return [
    `g:${giftStats.count}:${giftStats.maxUpdated || '-'}`,
    `b:${blacklistStats.count}:${blacklistStats.maxUpdated || '-'}`,
  ].join('|');
}

async function loadTableMeta(conn, table, config) {
  const columns = await loadTableColumns(conn, table);
  const meta = {
    table,
    columns,
  };

  if (config.nameColCandidates) meta.nameCol = pickFirst(columns, config.nameColCandidates);
  if (config.priceColCandidates) meta.priceCol = pickFirst(columns, config.priceColCandidates);
  if (config.ruleColCandidates) meta.ruleCol = pickFirst(columns, config.ruleColCandidates);
  if (config.enabledColCandidates) meta.enabledCol = pickFirst(columns, config.enabledColCandidates);
  if (config.updatedColCandidates) meta.updatedCol = pickFirst(columns, config.updatedColCandidates);

  if (config.idColCandidates) meta.idCol = pickFirst(columns, config.idColCandidates);
  if (config.deviceIdColCandidates) meta.deviceIdCol = pickFirst(columns, config.deviceIdColCandidates);
  if (config.roomIdColCandidates) meta.roomIdCol = pickFirst(columns, config.roomIdColCandidates);
  if (config.eventTimeColCandidates) meta.eventTimeCol = pickFirst(columns, config.eventTimeColCandidates);
  if (config.chatTextColCandidates) meta.chatTextCol = pickFirst(columns, config.chatTextColCandidates);
  if (config.senderColCandidates) meta.senderCol = pickFirst(columns, config.senderColCandidates);
  if (config.receiverColCandidates) meta.receiverCol = pickFirst(columns, config.receiverColCandidates);
  if (config.giftNameColCandidates) meta.giftNameCol = pickFirst(columns, config.giftNameColCandidates);
  if (config.giftQtyColCandidates) meta.giftQtyCol = pickFirst(columns, config.giftQtyColCandidates);
  if (config.notePriceColCandidates) meta.notePriceCol = pickFirst(columns, config.notePriceColCandidates);
  if (config.msgHashColCandidates) meta.msgHashCol = pickFirst(columns, config.msgHashColCandidates);

  return meta;
}

async function loadTableColumns(conn, table) {
  const now = Date.now();
  if (schemaCache.tables.has(table) && now - schemaCache.loadedAt < 60_000) {
    return schemaCache.tables.get(table);
  }

  const rows = await executeRows(
    conn,
    'SELECT COLUMN_NAME AS col FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?',
    [table],
  );

  const columns = new Set();
  for (const row of rows) {
    const col = String(row?.col || '').trim();
    if (col) {
      columns.add(col);
    }
  }

  schemaCache.tables.set(table, columns);
  schemaCache.loadedAt = now;
  return columns;
}

function pickFirst(columnsSet, candidates) {
  if (!columnsSet || !candidates) {
    return '';
  }
  for (const name of candidates) {
    if (columnsSet.has(name)) {
      return name;
    }
  }
  return '';
}

async function executeRows(conn, sql, params = []) {
  const result = await conn.execute(sql, params);

  if (Array.isArray(result)) {
    if (result.length > 0 && Array.isArray(result[0])) {
      return result[0];
    }
    return result;
  }

  if (result && Array.isArray(result.rows)) {
    return result.rows;
  }

  if (result && Array.isArray(result.results)) {
    return result.results;
  }

  return [];
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      ...CORS_HEADERS,
      'Content-Type': 'application/json; charset=utf-8',
    },
  });
}

function safeError(err) {
  if (!err) {
    return 'unknown_error';
  }
  if (typeof err === 'string') {
    return err;
  }
  if (err.message) {
    return String(err.message);
  }
  try {
    return JSON.stringify(err);
  } catch (_e) {
    return String(err);
  }
}

function sanitizeText(value, maxLen) {
  if (value === null || value === undefined) {
    return '';
  }
  let text = String(value).replace(/[\r\n\t]+/g, ' ').trim();
  if (!text) {
    return '';
  }
  if (text.length > maxLen) {
    text = text.slice(0, maxLen);
  }
  return text;
}

function sanitizeInt(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const num = Number(value);
  if (!Number.isFinite(num)) {
    return null;
  }
  const v = Math.floor(num);
  return v >= 0 ? v : null;
}

function sanitizeDateTime(value) {
  const text = sanitizeText(value, 64);
  if (!text) {
    return '';
  }
  return text.replace('T', ' ').slice(0, 23);
}

function clampInt(raw, min, max, fallback) {
  const n = Number(raw);
  if (!Number.isFinite(n)) {
    return fallback;
  }
  const v = Math.floor(n);
  if (v < min) {
    return min;
  }
  if (v > max) {
    return max;
  }
  return v;
}

function nowIso() {
  return new Date().toISOString();
}

function nowLocalDateTime() {
  const d = new Date();
  const pad = (n, size = 2) => String(n).padStart(size, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}
