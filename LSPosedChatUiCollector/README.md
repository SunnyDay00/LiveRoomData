# LSPosedChatUiCollector

UI 组件采集版 LSPosed 模块：

- 不走网络抓包
- 在 `com.netease.play` 的直播页（`LiveViewerActivity`）高频扫描 `chatVp` 下文本
- 优先抓取 `id/content` 文本，拿不到时回退为 `chatVp` 下全部 TextView
- 注入悬浮按钮控制开关：默认关闭，点击开启时先读取直播页 `roomNo` 作为房间ID，再启动采集；长按手动扫描一次

## 输出

- LSPosed 日志：`[LOOKChatHook] ...`
- 聊天记录：仅上传远程数据库（通过 Cloudflare Worker 中间 API）
- 本地 CSV：当前版本已禁用

## 使用步骤

1. 编译并安装 APK。
2. 在 LSPosed 中启用模块，作用域勾选 `com.netease.play`（以及 `com.netease.play:*` 子进程）。
3. 强制停止 LOOK 后重开，进入直播间。
4. 右侧会出现悬浮按钮：
   - `聊天抓取:开` 绿色：正在采集
   - `聊天抓取:关` 红色：暂停采集

## 说明

- 当前是“UI组件采集”方案，不依赖 WebSocket。
- 开启采集前会先调用 `/v1/health` 检测数据库连通性，失败会弹窗提示并保持关闭。
- 礼物价格与黑名单配置：首次全量拉取，后续按版本检查；版本变化才重新拉取全量。
- 远程 API 地址、读取/写入 Key 当前版本已写死到模块代码中。
- 若 `chatVp` 在当前直播间结构中不存在，会在日志看到 `chatVp not found`。
- Worker 示例工程位于：`LSPosedChatUiCollector/cloudflare-worker`。
