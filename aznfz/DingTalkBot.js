/*
 * Bot sender script (DingTalk / Feishu).
 *
 * 用法：
 * 1) 直接运行 main() 发送默认消息
 * 2) callScript("DingTalkBot", "你的消息")
 * 3) callScript("DingTalkBot", {content:"xxx", webhook:"...", keyword:"...", atMobiles:["13800138000"], atAll:false, silent:true})
 */

var DING_WEBHOOK = "https://open.feishu.cn/open-apis/bot/v2/hook/8df62739-fc99-4e1b-9734-7d7af8fcfdcc";
var DING_SECRET = ""; // 加签秘钥（不推荐在本引擎中使用）
var DING_KEYWORD = "数据采集通知："; // 关键词机器人必须包含
var DING_AT_MOBILES = []; // 可选: ["13800138000"]
var DING_AT_ALL = false;
var FEISHU_MSG_MODE = "post"; // 飞书消息模式: post(富文本) / text(纯文本)
var FEISHU_TEXT_MAX_LEN = 1800; // 飞书文本消息安全上限（保守值）
var FEISHU_TEXT_MAX_BYTES = 900; // 飞书文本单条按 UTF-8 字节控制
var FEISHU_MAX_CHUNKS = 6; // 飞书分段发送最多条数

function main(action, param1, param2, param3, param4, param5) {
  var opts = normalizeOptions(action, param1, param2, param3, param4, param5);
  if (opts.webhook == null || opts.webhook == "" || opts.webhook == "undefined") {
    alert("Please set webhook.");
    return;
  }

  var ts = nowTimestamp();
  var platform = detectBotPlatform(opts.webhook);
  var url = buildWebhookUrl(opts.webhook, opts.secret, ts, platform);
  if (url == null || url == "" || url == "undefined") {
    if (!opts.silent) {
      alert("Webhook is invalid.");
    }
    return;
  }
  var msg = buildMessage(opts.content, opts.keyword, ts);
  msg = sanitizeMessageText(msg);
  msg = fitMessageForPlatform(msg, opts.keyword, platform);
  console.log("bot platform=" + platform + " msgLen=" + strLen(msg));
  console.log("bot msgBytes=" + utf8ByteLen(msg));
  console.log("bot msg preview=" + previewText(msg, 120));
  var payload = buildPayload(msg, opts, platform, ts);
  console.log("bot payloadMode=" + getPayloadMode(platform, opts));

  var ret = postBotMessage(url, payload, platform);
  console.log("bot ret: " + ret);
  if (ret == null) {
    if (!opts.silent) {
      alert("httpPost returned null.");
    }
    return;
  }
  if (ret.state != "success") {
    if (platform == "feishu") {
      var chunkRet = trySendFeishuChunks(url, msg, opts.keyword, opts.secret, ts);
      if (chunkRet != null && chunkRet.state == "success") {
        console.log("bot ret: feishu chunk fallback success");
        ret = chunkRet;
      } else {
        console.log("bot ret: feishu chunk fallback failed ret=" + chunkRet);
      }
    }
  }

  if (ret.state != "success") {
    if (platform == "feishu") {
      logFeishuConnectivity();
    }
    if (!opts.silent) {
      alert("Request failed: " + ret.data);
    }
    return;
  }

  var err = parseDingErr(ret.data);
  if (isBotError(err)) {
    if (!opts.silent) {
      alert("Bot error: " + err.code + " " + err.msg);
    }
    return;
  }

  // 成功时仅记录日志，不弹窗，避免打断主流程
  console.log("Send success: " + ret.data);
}

function fitMessageForPlatform(msg, keyword, platform) {
  var s = "";
  if (msg != null) {
    s = "" + msg;
  }
  if (platform != "feishu") {
    return s;
  }
  var maxLen = FEISHU_TEXT_MAX_LEN;
  if (maxLen == null || maxLen <= 0) {
    maxLen = 1800;
  }
  if (strLen(s) <= maxLen) {
    return s;
  }
  var suffix = "\n\n(消息过长，已截断)";
  var keepLen = maxLen - strLen(suffix);
  if (keepLen < 0) {
    keepLen = maxLen;
    suffix = "";
  }
  var out = safeSubStr(s, keepLen) + suffix;
  var kw = "";
  if (keyword != null) {
    kw = "" + keyword;
  }
  if (kw != "" && out.indexOf(kw) < 0) {
    out = kw + "\n\n" + out;
    if (strLen(out) > maxLen) {
      out = safeSubStr(out, maxLen);
    }
  }
  return out;
}

function sanitizeMessageText(msg) {
  var s = "";
  if (msg != null) {
    s = "" + msg;
  }
  var out = "";
  var i = 0;
  for (i = 0; i < s.length; i = i + 1) {
    var c = s.charAt(i);
    var code = s.charCodeAt(i);
    if (code < 32 && c != "\n" && c != "\r" && c != "\t") {
      out = out + " ";
    } else {
      out = out + c;
    }
  }
  return out;
}

function strLen(s) {
  if (s == null) {
    return 0;
  }
  return ("" + s).length;
}

function safeSubStr(s, maxLen) {
  if (s == null) {
    return "";
  }
  var t = "" + s;
  var n = maxLen;
  if (n == null || n < 0) {
    n = 0;
  }
  if (t.length <= n) {
    return t;
  }
  return t.substring(0, n);
}

function previewText(s, maxLen) {
  var t = safeSubStr(s, maxLen);
  t = replaceAllStr(t, "\n", "\\n");
  t = replaceAllStr(t, "\r", "\\r");
  return t;
}

function utf8ByteLen(s) {
  if (s == null) {
    return 0;
  }
  var t = "" + s;
  var bytes = 0;
  var i = 0;
  for (i = 0; i < t.length; i = i + 1) {
    var code = t.charCodeAt(i);
    if (code <= 0x7F) {
      bytes = bytes + 1;
    } else if (code <= 0x7FF) {
      bytes = bytes + 2;
    } else if (code >= 0xD800 && code <= 0xDBFF) {
      var code2 = 0;
      if (i + 1 < t.length) {
        code2 = t.charCodeAt(i + 1);
      }
      if (code2 >= 0xDC00 && code2 <= 0xDFFF) {
        bytes = bytes + 4;
        i = i + 1;
      } else {
        bytes = bytes + 3;
      }
    } else {
      bytes = bytes + 3;
    }
  }
  return bytes;
}

function utf8PrefixByBytes(s, maxBytes) {
  if (s == null) {
    return "";
  }
  var t = "" + s;
  var out = "";
  var used = 0;
  var i = 0;
  for (i = 0; i < t.length; i = i + 1) {
    var code = t.charCodeAt(i);
    var b = 0;
    var step = 1;
    if (code <= 0x7F) {
      b = 1;
    } else if (code <= 0x7FF) {
      b = 2;
    } else if (code >= 0xD800 && code <= 0xDBFF) {
      var code2 = 0;
      if (i + 1 < t.length) {
        code2 = t.charCodeAt(i + 1);
      }
      if (code2 >= 0xDC00 && code2 <= 0xDFFF) {
        b = 4;
        step = 2;
      } else {
        b = 3;
      }
    } else {
      b = 3;
    }
    if (used + b > maxBytes) {
      break;
    }
    if (step == 2) {
      out = out + t.substring(i, i + 2);
      i = i + 1;
    } else {
      out = out + t.charAt(i);
    }
    used = used + b;
  }
  return out;
}

function fitTextByUtf8Bytes(s, maxBytes) {
  if (s == null) {
    return "";
  }
  var t = "" + s;
  if (utf8ByteLen(t) <= maxBytes) {
    return t;
  }
  return utf8PrefixByBytes(t, maxBytes);
}

function splitTextByUtf8Bytes(s, maxBytes, maxChunks) {
  var out = [];
  if (s == null) {
    return out;
  }
  var rest = "" + s;
  var countLimit = maxChunks;
  if (countLimit == null || countLimit <= 0) {
    countLimit = 1;
  }
  while (rest != "" && out.length < countLimit) {
    var part = utf8PrefixByBytes(rest, maxBytes);
    if (part == null || part == "") {
      part = safeSubStr(rest, 1);
    }
    out.push(part);
    rest = rest.substring(part.length);
  }
  if (rest != "" && out.length > 0) {
    var idx = out.length - 1;
    var suffix = "\n...(后续内容已省略)";
    var merged = out[idx] + suffix;
    out[idx] = fitTextByUtf8Bytes(merged, maxBytes);
  }
  return out;
}

function trySendFeishuChunks(url, msg, keyword, secret, timestamp) {
  var maxBytes = FEISHU_TEXT_MAX_BYTES;
  if (maxBytes == null || maxBytes <= 0) {
    maxBytes = 900;
  }
  if (utf8ByteLen(msg) <= maxBytes) {
    return null;
  }
  var chunks = splitTextByUtf8Bytes(msg, maxBytes, FEISHU_MAX_CHUNKS);
  if (chunks == null || chunks.length <= 1) {
    return null;
  }
  console.log("feishu chunk fallback start chunks=" + chunks.length + " maxBytes=" + maxBytes);
  var i = 0;
  var lastRet = null;
  for (i = 0; i < chunks.length; i = i + 1) {
    var part = chunks[i];
    var prefix = "";
    if (keyword != null && keyword != "") {
      prefix = "" + keyword + "\n\n";
    }
    prefix = prefix + "(分段 " + (i + 1) + "/" + chunks.length + ")\n";
    var partMsg = part;
    if (partMsg.indexOf("" + keyword) < 0) {
      partMsg = prefix + partMsg;
    }
    partMsg = fitTextByUtf8Bytes(partMsg, maxBytes);
    var payload = buildTextPayload(partMsg, null, false, "feishu", secret, timestamp);
    lastRet = postBotMessage(url, payload, "feishu");
    console.log("feishu chunk ret idx=" + (i + 1) + " bytes=" + utf8ByteLen(partMsg) + " ret=" + lastRet);
    if (lastRet == null || lastRet.state != "success") {
      return lastRet;
    }
    var err = parseDingErr(lastRet.data);
    if (err != null && err.code != 0) {
      return lastRet;
    }
  }
  return {state: "success", data: "chunk success"};
}

function postBotMessage(url, payload, platform) {
  var ret = null;
  if (platform == "feishu") {
    // 飞书优先使用 JSON 字符串 + 显式 Content-Type，避免部分运行时对象序列化差异
    var payloadText = "";
    try {
      payloadText = JSON.stringify(payload);
    } catch (e1) {
      payloadText = "";
    }
    if (payloadText != null && payloadText != "") {
      try {
        ret = httpPost(url, payloadText, {"Content-Type": "application/json; charset=utf-8"});
      } catch (e2) {
        ret = null;
      }
      if (ret != null && ret.state == "success") {
        return ret;
      }
    }
    // 回退：继续尝试原 object + json 模式
    try {
      ret = httpPost(url, payload, "json");
    } catch (e3) {
      ret = null;
    }
    return ret;
  }

  // 钉钉保持原有发送方式
  try {
    ret = httpPost(url, payload, "json");
  } catch (e4) {
    ret = null;
  }
  return ret;
}

function logFeishuConnectivity() {
  try {
    var ping = httpGet("https://open.feishu.cn", {"Accept": "text/plain"});
    console.log("feishu ping ret: " + ping);
  } catch (e) {
    console.log("feishu ping error: " + e);
  }
}

function normalizeOptions(action, p1, p2, p3, p4, p5) {
  var opts = {
    webhook: DING_WEBHOOK,
    secret: DING_SECRET,
    keyword: DING_KEYWORD,
    content: "",
    atMobiles: DING_AT_MOBILES,
    atAll: DING_AT_ALL,
    feishuMode: FEISHU_MSG_MODE,
    silent: false
  };

  if (action != null && typeof action === "object") {
    var o = action;
    if (o.webhook != null) { opts.webhook = o.webhook; }
    if (o.secret != null) { opts.secret = o.secret; }
    if (o.keyword != null) { opts.keyword = o.keyword; }
    if (o.content != null) { opts.content = o.content; }
    if (o.atMobiles != null) { opts.atMobiles = o.atMobiles; }
    if (o.atAll != null) { opts.atAll = o.atAll; }
    if (o.feishuMode != null) { opts.feishuMode = o.feishuMode; }
    if (o.silent == true) { opts.silent = true; }
    if (opts.webhook == null || opts.webhook == "" || opts.webhook == "undefined") { opts.webhook = DING_WEBHOOK; }
    if (opts.secret == null || opts.secret == "undefined") { opts.secret = ""; }
    if (opts.keyword == null || opts.keyword == "undefined") { opts.keyword = ""; }
    if (opts.feishuMode == null || opts.feishuMode == "" || opts.feishuMode == "undefined") { opts.feishuMode = FEISHU_MSG_MODE; }
    return opts;
  }

  if (action != null && action != "") {
    opts.content = action + "";
  }

  return opts;
}

function getPayloadMode(platform, opts) {
  if (platform != "feishu") {
    return "text";
  }
  var mode = FEISHU_MSG_MODE;
  if (opts != null && opts.feishuMode != null) {
    mode = "" + opts.feishuMode;
  }
  mode = ("" + mode).toLowerCase();
  if (mode == "text") {
    return "text";
  }
  return "post";
}

function buildPayload(content, opts, platform, timestamp) {
  if (platform == "feishu") {
    var mode = getPayloadMode(platform, opts);
    if (mode == "post") {
      return buildFeishuPostPayload(content, opts.secret, timestamp);
    }
    return buildTextPayload(content, opts.atMobiles, opts.atAll, "feishu", opts.secret, timestamp);
  }
  return buildTextPayload(content, opts.atMobiles, opts.atAll, "dingtalk", opts.secret, timestamp);
}

function nowTimestamp() {
  var t = 0;
  try {
    t = new Date().getTime();
  } catch (e) {
    t = 0;
  }
  if (t == null || t == 0) {
    t = 0;
  }
  return t;
}

function detectBotPlatform(webhook) {
  var w = "";
  if (webhook != null) {
    w = ("" + webhook).toLowerCase();
  }
  if (w.indexOf("open.feishu.cn/open-apis/bot/v2/hook/") >= 0) {
    return "feishu";
  }
  if (w.indexOf("oapi.dingtalk.com/robot/send") >= 0) {
    return "dingtalk";
  }
  return "dingtalk";
}

function buildWebhookUrl(webhook, secret, timestamp, platform) {
  if (webhook == null || webhook == "" || webhook == "undefined") {
    return "";
  }
  var p = platform;
  if (p == null || p == "") {
    p = detectBotPlatform(webhook);
  }
  if (secret == null || secret == "" || secret == "undefined") {
    return webhook;
  }
  // 飞书签名放在 body 中，不拼接 query 参数
  if (p == "feishu") {
    return webhook;
  }
  if (timestamp == null || timestamp == 0) {
    timestamp = nowTimestamp();
  }
  var stringToSign = timestamp + "\n" + secret;
  var sign = hmacSha256Base64(stringToSign, secret);
  var signEncoded = urlEncode(sign);
  var sep = "&";
  if (webhook.indexOf("?") < 0) {
    sep = "?";
  }
  return webhook + sep + "timestamp=" + timestamp + "&sign=" + signEncoded;
}

function buildMessage(content, keyword, timestamp) {
  var msg = "";
  if (content != null) { msg = "" + content; }
  if (msg == null || msg == "") {
    msg = "DingTalk bot test " + timestamp;
  }
  if (keyword != null && keyword != "") {
    var kw = "" + keyword;
    if (msg.indexOf(kw) < 0) {
      msg = kw + "\n\n" + msg;
    }
  }
  return msg;
}

function buildTextPayload(content, atMobiles, isAtAll, platform, secret, timestamp) {
  if (platform == "feishu") {
    var p = {
      msg_type: "text",
      content: { text: content }
    };
    if (secret != null && secret != "" && secret != "undefined") {
      var ts = timestamp;
      if (ts == null || ts == 0) {
        ts = nowTimestamp();
      }
      p.timestamp = "" + ts;
      p.sign = hmacSha256Base64(ts + "\n" + secret, secret);
    }
    return p;
  }

  var atObj = { atMobiles: [], isAtAll: false };
  if (atMobiles != null) {
    atObj.atMobiles = atMobiles;
  }
  if (isAtAll == true) {
    atObj.isAtAll = true;
  }

  var payload = {
    msgtype: "text",
    text: { content: content },
    at: atObj
  };
  return payload;
}

function buildFeishuPostPayload(content, secret, timestamp) {
  var text = "";
  if (content != null) {
    text = "" + content;
  }

  var lines = splitLinesForPost(text);
  var title = buildFeishuPostTitle(lines);
  var lineNodes = buildFeishuPostLineNodes(lines, title);

  var p = {
    msg_type: "post",
    content: {
      post: {
        zh_cn: {
          title: title,
          content: lineNodes
        }
      }
    }
  };

  if (secret != null && secret != "" && secret != "undefined") {
    var ts = timestamp;
    if (ts == null || ts == 0) {
      ts = nowTimestamp();
    }
    p.timestamp = "" + ts;
    p.sign = hmacSha256Base64(ts + "\n" + secret, secret);
  }

  return p;
}

function splitLinesForPost(text) {
  var out = [];
  if (text == null) {
    return out;
  }
  var s = ("" + text);
  s = replaceAllStr(s, "\r\n", "\n");
  s = replaceAllStr(s, "\r", "\n");
  var arr = s.split("\n");
  var i = 0;
  for (i = 0; i < arr.length; i = i + 1) {
    var line = "" + arr[i];
    if (line == "") {
      out.push(" ");
    } else {
      out.push(line);
    }
  }
  if (out.length <= 0) {
    out.push(" ");
  }
  return out;
}

function buildFeishuPostTitle(lines) {
  if (lines == null || lines.length <= 0) {
    return "数据采集通知";
  }
  var i = 0;
  for (i = 0; i < lines.length; i = i + 1) {
    var cur = ("" + lines[i]).trim();
    if (cur != "") {
      if (strLen(cur) > 40) {
        return safeSubStr(cur, 40);
      }
      return cur;
    }
  }
  return "数据采集通知";
}

function buildFeishuPostLineNodes(lines, title) {
  var rows = [];
  if (lines == null) {
    return rows;
  }
  var titleTrim = "";
  if (title != null) {
    titleTrim = ("" + title).trim();
  }
  var skippedTitleLine = false;
  var i = 0;
  for (i = 0; i < lines.length; i = i + 1) {
    var line = "" + lines[i];
    if (!skippedTitleLine) {
      var lineTrim = ("" + line).trim();
      if (lineTrim != "" && titleTrim != "" && lineTrim == titleTrim) {
        skippedTitleLine = true;
        continue;
      }
    }
    if (line == "") {
      line = " ";
    }
    rows.push([{tag: "text", text: line}]);
  }
  if (rows.length <= 0) {
    rows.push([{tag: "text", text: " "}]);
  }
  return rows;
}

function parseDingErr(dataStr) {
  if (dataStr == null || dataStr == "") {
    return null;
  }
  var obj = null;
  try {
    obj = JSON.parse(dataStr);
  } catch (e) {
    obj = null;
  }
  if (obj == null) {
    return null;
  }
  var code = null;
  var msg = "";
  if (obj.errcode != null) {
    code = obj.errcode;
  } else if (obj.code != null) {
    code = obj.code;
  } else if (obj.StatusCode != null) {
    code = obj.StatusCode;
  }
  if (obj.errmsg != null) {
    msg = obj.errmsg;
  } else if (obj.msg != null) {
    msg = obj.msg;
  } else if (obj.message != null) {
    msg = obj.message;
  } else if (obj.StatusMessage != null) {
    msg = obj.StatusMessage;
  }
  return { code: code, msg: msg };
}

function parseIntCode(v) {
  if (v == null) {
    return null;
  }
  var s = ("" + v).trim();
  if (s == "" || s == "undefined" || s == "null") {
    return null;
  }
  var i = 0;
  var sign = 1;
  if (s.charAt(0) == "-") {
    sign = -1;
    i = 1;
  }
  var n = 0;
  var found = false;
  for (; i < s.length; i = i + 1) {
    var c = s.charAt(i);
    if (c < "0" || c > "9") {
      return null;
    }
    n = n * 10 + (c.charCodeAt(0) - 48);
    found = true;
  }
  if (!found) {
    return null;
  }
  return n * sign;
}

function isBotError(err) {
  if (err == null) {
    return false;
  }
  var codeNum = parseIntCode(err.code);
  if (codeNum == null) {
    return false;
  }
  if (codeNum != 0) {
    return true;
  }
  return false;
}

function urlEncode(str) {
  if (str == null) {
    return "";
  }
  var s = str + "";
  var enc = "";
  var ok = false;

  try {
    if (typeof encodeURIComponent != "undefined") {
      enc = encodeURIComponent(s);
      ok = true;
    }
  } catch (e) {
  }

  if (ok) {
    return enc;
  }

  try {
    if (typeof encodeURI != "undefined") {
      enc = encodeURI(s);
    } else {
      enc = s;
    }
  } catch (e2) {
    enc = s;
  }

  enc = replaceAllStr(enc, "+", "%2B");
  enc = replaceAllStr(enc, "/", "%2F");
  enc = replaceAllStr(enc, "=", "%3D");
  return enc;
}

function replaceAllStr(s, from, to) {
  if (s == null) {
    return "";
  }
  var arr = (s + "").split(from);
  return arr.join(to);
}

function hmacSha256Base64(data, secret) {
  if (typeof rsContext == "undefined") {
    throw "rsContext is undefined";
  }

  var loader = rsContext.getClass().getClassLoader();
  var MacClass = loader.loadClass("javax.crypto.Mac");
  var SecretKeySpecClass = loader.loadClass("javax.crypto.spec.SecretKeySpec");
  var CharsetClass = loader.loadClass("java.nio.charset.Charset");

  var charset = CharsetClass.forName("UTF-8");
  var dataBytes = (data + "").getBytes(charset);
  var keyBytes = (secret + "").getBytes(charset);

  var keySpec = SecretKeySpecClass.newInstance(keyBytes, "HmacSHA256");
  var mac = MacClass.getInstance("HmacSHA256");
  mac.init(keySpec);
  var rawBytes = mac.doFinal(dataBytes);

  return base64Encode(rawBytes);
}

function base64Encode(bytes) {
  var loader = rsContext.getClass().getClassLoader();
  var Base64Class = null;
  var isAndroid = false;

  try {
    Base64Class = loader.loadClass("android.util.Base64");
    isAndroid = true;
  } catch (e1) {
    Base64Class = null;
  }

  if (Base64Class == null) {
    try {
      Base64Class = loader.loadClass("java.util.Base64");
      isAndroid = false;
    } catch (e2) {
      Base64Class = null;
    }
  }

  if (Base64Class == null) {
    throw "Base64 class not found";
  }

  if (isAndroid) {
    var NO_WRAP = Base64Class.NO_WRAP;
    return Base64Class.encodeToString(bytes, NO_WRAP);
  }

  var encoder = Base64Class.getEncoder();
  return encoder.encodeToString(bytes);
}
