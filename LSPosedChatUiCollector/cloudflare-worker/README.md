# LSPosedChatUiCollector Cloudflare Worker

该 Worker 作为中间 API 层，供 LSPosed 模块使用：
- 检测数据库连通性
- 拉取礼物价格与聊天黑名单配置
- 批量写入聊天记录到 TiDB Cloud
- 提供只读查询接口（后续展示可复用）

## 接口

- `GET /v1/health`：数据库健康检查
- `GET /v1/config/version`：仅返回配置版本（用于低频比较）
- `GET /v1/config/snapshot`：返回完整配置（礼物价格 + 黑名单）
- `POST /v1/chat/ingest`：批量写入聊天记录
- `GET /v1/chat/list?roomId=xxx&limit=100`：只读查询聊天记录

## 鉴权

请求头使用：`X-API-Key`

- 写入接口：校验 `API_KEY_WRITE`
- 读取接口：校验 `API_KEY_READ`；如果未设置，则回退使用 `API_KEY_WRITE`

## 环境变量

在 Worker 中配置：

- `TIDB_DATABASE_URL`（必填）
- `API_KEY_WRITE`（必填）
- `API_KEY_READ`（可选）

## 本地开发

```bash
cd cloudflare-worker
npm install
npx wrangler dev
```

## 部署

```bash
cd cloudflare-worker
npx wrangler login
npx wrangler secret put TIDB_DATABASE_URL
npx wrangler secret put API_KEY_WRITE
npx wrangler secret put API_KEY_READ
npx wrangler deploy
```

## 数据表约定

Worker 默认使用表名：
- `chat_records`
- `gift_price_catalog`
- `chat_blacklist_rules`

字段名支持多候选自动识别（例如 `gift_name`/`giftName`），因此现有表结构只要语义一致即可。
