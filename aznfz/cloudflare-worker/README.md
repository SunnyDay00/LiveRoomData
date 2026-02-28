# Cloudflare Worker 部署 - README

## 快速部署

这个 Worker 充当 Android 脚本和 Neon 数据库之间的安全中间层。

### 1. 安装依赖

```bash
npm install
```

### 2. 登录 Cloudflare

```bash
npx wrangler login
```

### 3. 设置密钥

```bash
# 数据库连接字符串
npx wrangler secret put DATABASE_URL
# 粘贴: postgresql://xxxx

# API 验证密钥 (自己生成一个强密码)
npx wrangler secret put CLIENT_API_KEY
# 粘贴: lrm_你的随机密钥
```

### 4. 部署

```bash
npx wrangler deploy
```

### 5. 测试

```bash
curl -X POST https://YOUR_WORKER.workers.dev/upload \
  -H "Content-Type: application/json" \
  -H "X-API-Key: 你的密钥" \
  -d '{"app_name":"TEST","homeid":"123",...}'
```

## 安全特性

- ✅ API Key 验证
- ✅ CORS 支持
- ✅ 仅允许 INSERT 操作（通过数据库角色限制）
- ✅ 参数化查询（防 SQL 注入）

## 文件说明

- `index.js` - Worker 主代码
- `wrangler.toml` - Cloudflare 配置
- `package.json` - 依赖管理

详细部署指南见：`walkthrough.md`
