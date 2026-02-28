# LSPosedChatUiCollector 模块流程图

```mermaid
flowchart TD
    A[LSPosed 加载模块<br/>命中 com.netease.play 进程] --> B[Hook Activity 生命周期]
    B --> C[onResume 进入 LiveViewerActivity<br/>创建/恢复 UiSession]
    C --> D[注入悬浮按钮<br/>聊天抓取开关 + 广告处理开关]

    D --> E{用户点击开启聊天抓取?}
    E -- 否 --> F[保持关闭状态]
    E -- 是 --> G[读取 roomNo -> 房间ID]
    G --> H{房间ID读取成功?}
    H -- 否 --> I[Toast: 开启失败]
    H -- 是 --> J[调用 /v1/health 检查远程可用性]
    J --> K{health ok?}
    K -- 否 --> I
    K -- 是 --> L[拉取远程配置 snapshot<br/>礼物价格 + 黑名单]
    L --> M[开启采集 captureEnabled=true]

    M --> N[主循环 80ms]
    N --> O[可选: 自动处理全屏广告]
    O --> P[可选: 自动点击“底部有新消息”]
    P --> Q[扫描 chatVp 可见文本]
    Q --> R[与上一帧做增量对比]
    R --> S[去重窗口过滤 + 黑名单过滤]
    S --> T{聊天解析开关}
    T -- 关 --> U[仅保留时间+聊天内容]
    T -- 开 --> V[识别送礼消息并解析字段]
    U --> W[记录入队 RemoteBatchUploader]
    V --> W

    W --> X[每 300ms 批量 flush]
    X --> Y[POST /v1/chat/ingest]
    Y --> Z{写入成功?}
    Z -- 是 --> AA[完成]
    Z -- 否 --> AB[重入队等待下次重试]

    N --> AC[每 30s 调 /v1/config/version]
    AC --> AD{版本变化?}
    AD -- 否 --> N
    AD -- 是 --> L
```

## Worker 端接口（供讲解）
- `GET /v1/health`：数据库连通性检查
- `GET /v1/config/version`：配置版本检查
- `GET /v1/config/snapshot`：礼物价格 + 黑名单全量配置
- `POST /v1/chat/ingest`：批量写入聊天记录
- `GET /v1/chat/list`：只读查询聊天记录

