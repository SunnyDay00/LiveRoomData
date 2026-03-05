# LSPosedLookUiModule

LOOK 直播间数据采集与分析模块（目标包：`com.netease.play`）。

## 当前版本

- 文档对齐版本：`2.2.29`
- 详细运行说明：`MODULE_RUNTIME_FLOW.md`

## 模块做什么

1. 自动从“一起聊”列表进入直播间。
2. 采集贡献榜、魅力榜数据并落盘 CSV。
3. 可选调用 AI 大模型推断“谁给谁消费了多少”。
4. 可选将推断结果按用户逐条发送到飞书 Webhook。

## 设置项（与设置页一致）

- 基础开关：
  - `全局悬浮按钮`
  - `悬浮信息窗口`
  - `广告处理`
  - `无障碍实时广告服务（替代LSP）`
  - `启动软件自动运行模块`
  - `View树调试输出（仅调试）`
- 运行节奏：
  - `一起聊直播间循环点击次数`（0=无限）
  - `每次循环后等待时间（秒）`
- 榜单采集：
  - `贡献榜列表组循环点击次数`
  - `魅力榜列表组循环点击次数`
  - `单榜重试上限/超时设置`
  - `全量采集榜单用户数据`
  - `全量采集Data数值限制`（默认5000，0=不限制）
  - `采集用户详细界面`
- AI 分析：
  - `AI大模型分析消费数据`
  - `AI大模型URL`
  - `AI大模型AKY`
  - `AI大模型model`
  - `测试连接`
- 飞书推送：
  - `飞书机器人发送数据结果`
  - `Webhook地址`
  - `签名验证`
  - `测试机器人连接`

## 运行前预检规则

点击悬浮按钮“运行”时：

1. 若 `AI大模型分析消费数据=开启`，先自动执行 AI 连接测试；失败则阻断运行。
2. 若 `飞书机器人发送数据结果=开启`，再自动执行飞书连接测试；失败则阻断运行。
3. 两项都通过后，才进入正式运行流程。

## 数据输出目录

统一目录：

- `/sdcard/Android/data/com.oodbye.looklspmodule/files/look_rank_csv/`

文件类型：

1. 榜单采集 CSV：
   - `look_contribution_rank_yyyyMMdd_N.csv`
   - `look_charm_rank_yyyyMMdd_N.csv`
2. AI 结果 CSV（按天聚合）：
   - `look_ai_consumption_analysis_yyyyMMdd.csv`
   - 标题列：
     - `用户昵称,用户ID,用户等级,消费数据,消费计算准确度,消费对象昵称,消费对象ID,直播间ID,在直播间总消费数据,写入时间`

说明：

- AI 无有效记录（如 `NO_VALID_RECORDS`、空模板）时：
  - 不写入 AI 结果 CSV；
  - 不发送飞书。

## 关键日志标签

- `LOOKLspModule`：主流程与运行状态
- `LOOKScriptRunner`：直播间任务编排与榜单采集调度
- `LOOKA11yAd`：无障碍采集与广告规则执行
- `RankAiAnalyzer`：AI 分析全链路（请求、回复、解析、落盘、飞书）
- `LOOKFeishuPushTest`：设置页飞书测试
- `LOOKFeishuPush`：飞书发送结果

## 关键源码

- `app/src/main/java/com/oodbye/looklspmodule/LookHookEntry.java`
- `app/src/main/java/com/oodbye/looklspmodule/GlobalFloatService.java`
- `app/src/main/java/com/oodbye/looklspmodule/ModuleSettingsActivity.java`
- `app/src/main/java/com/oodbye/looklspmodule/LiveRoomTaskScriptRunner.java`
- `app/src/main/java/com/oodbye/looklspmodule/LiveRoomRankCsvStore.java`
- `app/src/main/java/com/oodbye/looklspmodule/RankAiConsumptionAnalyzer.java`
- `app/src/main/java/com/oodbye/looklspmodule/FeishuWebhookSender.java`
