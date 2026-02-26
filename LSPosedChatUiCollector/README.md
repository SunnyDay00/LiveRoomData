# LSPosedChatUiCollector

UI 组件采集版 LSPosed 模块：

- 不走网络抓包
- 在 `com.netease.play` 的直播页（`LiveViewerActivity`）高频扫描 `chatVp` 下文本
- 仅在底部标签页为“房间”时采集；切到“广场”会自动跳过
- 优先抓取 `id/content` 文本，拿不到时回退为 `chatVp` 下全部 TextView
- 注入悬浮按钮控制开关：默认关闭，点击开启时先读取直播页 `roomNo` 作为房间ID，再启动采集；长按手动扫描一次

## 输出

- LSPosed 日志：`[LOOKChatHook] [ui] ...`
- CSV 文件：优先写入 `/storage/emulated/0/LiveRoomData/<房间ID>_<日期>_<编号>.csv`，若无权限会回退到 app 私有目录（日志可见 `output dir=...`）。
- CSV 列：`时间,聊天记录`
- 点击“开启抓取”时会立即创建 CSV 并写入表头（即使暂时没有新聊天）。
- CSV 时间统一按北京时间（`GMT+08:00`）格式化。

## 使用步骤

1. 编译并安装 APK。
2. 在 LSPosed 中启用模块，作用域勾选 `com.netease.play`（以及 `com.netease.play:*` 子进程）。
3. 强制停止 LOOK 后重开，进入直播间。
4. 右侧会出现悬浮按钮：
   - `聊天抓取:开` 绿色：正在采集
   - `聊天抓取:关` 红色：暂停采集

## 说明

- 当前是“UI组件采集”方案，不依赖 WebSocket。
- 若 `chatVp` 在当前直播间结构中不存在，会在日志看到 `chatVp not found`。
