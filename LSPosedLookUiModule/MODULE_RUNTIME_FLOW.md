# LSPosedLookUiModule 运行逻辑说明

最后更新：2026-03-01（v1.0.12）

## 1. 长期维护约定

- 后续每次新增或修改功能，必须同步更新本文件。
- 所有 UI 参数统一维护在 `UiComponentConfig.java`。
- 每次修改模块代码后，必须同步更新 `app/build.gradle` 中的 `versionCode` 与 `versionName`（当前基线：`1.0.0`）。

## 2. 关键文件

- `app/src/main/java/com/oodbye/looklspmodule/LookHookEntry.java`
- `app/src/main/java/com/oodbye/looklspmodule/GlobalFloatService.java`
- `app/src/main/java/com/oodbye/looklspmodule/ModuleSettingsActivity.java`
- `app/src/main/java/com/oodbye/looklspmodule/ModuleSettings.java`
- `app/src/main/java/com/oodbye/looklspmodule/UiComponentConfig.java`
- `app/src/main/java/com/oodbye/looklspmodule/CustomRulesAdProcessor.java`
- `app/src/main/java/com/oodbye/looklspmodule/LiveRoomTaskScriptRunner.java`

## 3. 设置与命令

- 设置项：
  - `全局悬浮按钮`
  - `广告处理`
- 悬浮按钮命令：
  - `运行` -> `RUNNING`
  - `暂停` -> `PAUSED`
  - `停止` -> `STOPPED`
- 首次安装且 `engine_command_seq=0` 时，默认按 `RUNNING` 自动进入流程。

## 4. 当前主流程

1. 点击悬浮按钮“运行”后，若 LOOK 正在运行先 `force-stop`，再启动 LOOK。
2. LSPosed 在 `com.netease.play` 进程注入，进入主循环。
3. 启动后先等待数秒，再在首页点击“`一起聊`”tab。
4. 进入一起聊界面后，先执行一次“下滑刷新”，等待页面稳定。
5. 在一起聊页识别直播卡片并点击“下一个未处理卡片”。
6. 点击卡片后先做“是否离开一起聊界面”检测：
   - 若点击后仍在一起聊页：继续等待，超时后判定本次点击未生效，恢复列表点击。
   - 若已离开一起聊页：判定点击生效，进入下一步。
7. 点击生效后，先执行广告处理（若设置中开启“广告处理”）。
8. 再做“直播间进入校验”：
   - 页面中必须同时存在 `roomNo`、`title`、`closeBtn` 三个组件。
   - 直播间 `title` 文本需与该卡片 `index=1..4` 子节点文本之一一致。
9. 校验通过后执行直播间任务。
10. 直播间任务执行完成后自动返回上一页（退出直播间）。
11. 从直播间返回到一起聊后，先等待 `1秒`，再点击下一个直播卡片。
12. 回到一起聊后继续点击后续卡片。
13. 每次收到“运行”命令时轮转本地日志文件：保留“本次运行 + 上次运行”两份。

## 5. 一起聊卡片识别规则（当前）

- 卡片列表容器：`com.netease.play:id/rnView`
- 候选卡片条件：
  - `ViewGroup`
  - `clickable=true`
  - `isShown=true`
  - 需要能采集到有效文本（过滤空文本、纯数字、tab 文本“`一起聊`”）
- 不使用 `bounds`（坐标/宽高）参与识别与去重。
- 每张卡片按文本特征生成 `key` 并去重，避免重复点击同一卡片。
- 每次点击卡片时会记录该卡片 `index=1..4` 子节点文本，作为进入直播间后的标题对比候选。
- 卡片点击执行顺序（v1.0.6）：
  - 优先点击封面图节点：`resource-id=DC_Image`
  - 再点击标题区子节点（`index=3..4`）
  - 再点击卡片根节点（主点击点 + 备用点击点）
  - 每个目标均执行触摸点击 + 父级回退点击（`performClick/callOnClick/tap`）
  - 日志会输出本次命中的点击目标（`clickTarget`）
- 一起聊首屏流程：
  - 启动后等待 `STARTUP_WAIT_BEFORE_CLICK_TOGETHER_MS`
  - 进入一起聊后等待 `TOGETHER_REFRESH_WAIT_BEFORE_PULL_MS`
  - 执行一次下滑刷新手势，再等待 `TOGETHER_REFRESH_SETTLE_MS`
  - 每次从直播间返回后，等待 `LIVE_ROOM_RETURN_TOGETHER_WAIT_MS` 再点击下一卡片

## 6. 直播间进入校验规则

- 必须命中以下 3 个节点（配置见 `UiComponentConfig.java`）：
  - `com.netease.play:id/roomNo` / `android.widget.TextView`
  - `com.netease.play:id/title` / `android.widget.TextView`
  - `com.netease.play:id/closeBtn` / `android.widget.ImageView`
- 标题一致性：
  - 读取直播间 `com.netease.play:id/title` 的 `text`
  - 与最近一次点击卡片的 `index=1..4` 子节点采集文本做“完全相等”比对
  - 若不一致，判定为未进入目标直播间，执行返回，不运行直播间任务

## 7. 直播间任务

- 固定任务名：`live_room_enter_task`（内部 Java 任务，不依赖 `.sh/.txt` 文件）。
- 入口文件与方法：
  - `app/src/main/java/com/oodbye/looklspmodule/LiveRoomTaskScriptRunner.java`
  - `runLiveRoomEnterTask(Activity activity)`
- 当前行为：
  - 进入直播间并完成校验后，执行 `live_room_enter_task`。
  - 当前任务逻辑为等待 `5000ms` 后结束，随后自动返回上一页。
- 说明：
  - 你后续要扩展“进入直播间后的采集功能”，直接在 `LiveRoomTaskScriptRunner.runLiveRoomEnterTask(...)` 中追加即可。

## 8. 广告规则

- 规则优先级：
  - 外部 `CustomRules.ini`
  - 模块内置 `assets/CustomRules.ini`
- `com.netease.play` 使用 hash key：`1144086404`。
- 当前已加规则：`com.netease.play:id/slidingContainer -> GLOBAL_ACTION_BACK`（出现即返回）。
- 当前已加复合条件规则：
  - 当 `menu_login_with_setting`、`qq`、`weibo`、`phone`、`cloudmusic` 五个组件都存在时，执行 `GLOBAL_ACTION_BACK` 返回。

## 9. 日志

- `XposedBridge.log` + `logcat`
- 前缀：`[LOOKLspModule]` / `[LOOKAdRules]` / `[LOOKScriptRunner]`
- 点击日志：
  - 每次实际点击成功时输出组件详情：`class/id/text/clickable/shown/bounds` 与点击方式（`performClick/callOnClick/tap`）。
  - 点击“一起聊”tab 与点击直播卡片均会输出对应组件详情。
- 去重策略：
  - 高频等待类流程日志启用重复抑制，相同文案不重复输出，避免无效刷屏。
- 模块本地日志文件（每次运行轮转）：
  - 当前运行：`/sdcard/Android/data/com.oodbye.looklspmodule/files/look_lsp_run_current.log`
  - 上次运行：`/sdcard/Android/data/com.oodbye.looklspmodule/files/look_lsp_run_previous.log`
- 轮转规则：
  - 新一次“运行”开始时，把 `current` 重命名为 `previous`
  - 创建新的 `current` 写入本次日志
  - 仅保留这两份文件
