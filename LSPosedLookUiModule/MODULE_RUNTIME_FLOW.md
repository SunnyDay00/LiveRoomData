# LSPosedLookUiModule 运行逻辑说明

最后更新：2026-03-02（v1.0.39）

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
- `app/src/main/java/com/oodbye/looklspmodule/LiveRoomRuntimeModule.java`
- `app/src/main/java/com/oodbye/looklspmodule/RealtimeAdLoop.java`
- `app/src/main/java/com/oodbye/looklspmodule/ModuleStartupReceiver.java`
- `app/src/main/java/com/oodbye/looklspmodule/FloatServiceBootstrap.java`
- `app/src/main/java/com/oodbye/looklspmodule/LookAccessibilityAdService.java`
- `app/src/main/java/com/oodbye/looklspmodule/AccessibilityCustomRulesAdEngine.java`

## 3. 设置与命令

- 设置项：
  - `全局悬浮按钮`
  - `广告处理`
  - `无障碍实时广告服务（替代LSP）`
  - `启动软件自动运行模块`
  - `一起聊直播间循环点击次数`（0=无限）
  - `每次循环后等待时间（秒）`（默认0秒）
- 悬浮按钮命令：
  - `运行` -> `RUNNING`
  - `暂停` -> `PAUSED`
  - `停止` -> `STOPPED`
- 悬浮按钮状态文案（v1.0.33）：
  - 运行中：`LSP-运行中`
  - 暂停：`LSP-已暂停`
  - 未运行：`LSP-未运行`
- `运行`命令补发机制（v1.0.17）：
  - 点击“运行”后若会 `force-stop` 并重启 LOOK，`GlobalFloatService` 会在启动后延迟多次补发 `RUN` 实时广播，确保新进程收到运行命令。
- 引擎状态同步心跳（v1.0.18）：
  - `GlobalFloatService` 会周期性向 LOOK 进程同步当前引擎状态广播。
  - 当状态为 `RUNNING` 时每隔数秒补发心跳广播，兜底新进程状态同步，避免“点击运行后无反应”。
- 默认不自动运行：需手动点悬浮按钮“运行”才进入流程。
- 若开启“启动软件自动运行模块”，则在 LOOK 进程启动时自动进入 `RUNNING`（每个 LOOK 进程仅自动触发一次，避免“停止后立即被再次拉起”）。
- 悬浮按钮自恢复：
  - 通过 `ModuleStartupReceiver` 监听开机、解锁、模块更新完成，以及 `ACTION_SYNC_FLOAT_SERVICE` 广播。
  - LOOK 进程在 `onResume` 时会发送 `ACTION_SYNC_FLOAT_SERVICE` 给模块进程，模块端按开关状态拉起/关闭 `GlobalFloatService`。
- 设置页前台行为（v1.0.23）：
  - `ModuleSettingsActivity` 已从最近任务列表排除（`excludeFromRecents=true`）。
  - 模块后台同步状态时仅静默拉起服务，不主动拉起设置页到前台。
- 悬浮按钮稳定性修复（v1.0.22）：
  - `GlobalFloatService` 从“多显示多窗口实例”调整为“主屏单实例悬浮窗”。
  - 增加悬浮窗附着状态自愈：若窗口 token 异常丢失会自动重建，避免运行中再次点击“运行”后悬浮按钮消失。
  - 前台通知改为纯静默常驻（不再挂设置页跳转入口），避免服务拉起时把设置页带到前台。
- 悬浮按钮显示修复（v1.0.28）：
  - LOOK 进程发送 `ACTION_SYNC_FLOAT_SERVICE` 时携带当前 `displayId`。
  - `GlobalFloatService` 按该 `displayId` 重建对应显示屏 `WindowManager` 并重新挂载悬浮窗。
  - 解决多显示屏环境下“服务在运行但按钮不在当前屏显示”的问题。
- 运行状态回传修复（v1.0.33）：
  - LOOK 进程状态变化时发送 `ACTION_ENGINE_STATUS_REPORT` 回模块进程。
  - 模块进程落盘同步 `engine_status/engine_command/engine_command_seq`，悬浮按钮读取后可正确显示“运行中/已暂停/未运行”。

## 4. 当前主流程

1. 点击悬浮按钮“运行”后，若 LOOK 正在运行先 `force-stop`，再启动 LOOK。
2. LSPosed 在 `com.netease.play` 进程注入，进入主循环。
3. 启动后先等待数秒，再在首页点击“`一起聊`”tab。
4. 进入一起聊界面后，先执行一次“下滑刷新”，等待页面稳定。
5. 在一起聊页识别直播卡片并点击“下一个未处理卡片”。
6. 若当前可见区域没有新卡片，则自动执行“上滑加载更多”后继续识别并点击下方卡片。
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
12. 回到一起聊后继续点击后续卡片；无新卡片时继续上滑，循环处理。
13. 当“一起聊无新卡片”满足完成阈值时，判定一次循环完成，延迟后请求模块服务重启 LOOK 进入下一轮。
14. 若设置了 `一起聊直播间循环点击次数=N`（N>0），则每完成一轮计数 +1；当计数达到 N 时，不再重启下一轮，模块自动切换为停止状态。
15. 每次完整循环完成后，会弹出提示：本轮进入直播间数量 + 剩余循环次数（约3秒后淡化消失，不阻塞流程）。
16. 每次收到“运行”命令时轮转本地日志文件：保留“本次运行 + 上次运行”两份。

## 5. 一起聊卡片识别规则（当前）

- 卡片列表容器：`com.netease.play:id/rnView`
- 候选卡片条件：
  - `ViewGroup`
  - `clickable=true`
  - `isShown=true`
  - 需要能采集到有效文本（过滤空文本、纯数字、tab 文本“`一起聊`”）
- 不使用 `bounds`（坐标/宽高）参与识别与去重。
- 每张卡片按文本特征生成 `key` 并去重，避免重复点击同一卡片。
- 去重 key 规则（v1.0.26）：
  - 优先使用卡片标题候选（`index=1..4`）生成 key；
  - 标题为空时再回退到通用文本特征；
  - 同一次运行流程内，已点击 key 不会再次点击（直到下次“运行”重置）。
- 每次点击卡片时会记录该卡片 `index=1..4` 子节点文本，作为进入直播间后的标题对比候选。
- 卡片点击执行顺序（v1.0.6）：
  - 优先点击封面图节点：`resource-id=DC_Image`
  - 再点击标题区子节点（`index=3..4`）
  - 再点击卡片根节点（主点击点 + 备用点击点）
  - 每个目标均执行触摸点击 + 父级回退点击（`performClick/callOnClick/tap`）
  - 日志会输出本次命中的点击目标（`clickTarget`）
- 点击有效性修复（v1.0.25）：
  - `performClick/callOnClick/tap_center` 仅在目标节点具备有效可见区域（`bounds` 宽高 > 2）时才认定成功。
  - 避免 `bounds=[0,0][0,0]` 伪成功导致“未进入直播间但误判点击成功”。
- 点击回退修复（v1.0.27）：
  - 父级回退点击中，`tap_center` 不再对“非 clickable 且非起始节点”的容器判定成功。
  - 避免把大容器误点当作“卡片已点击成功”，减少“未进入直播间/误入其他页面”的误判。
- 一起聊首屏流程：
  - 启动后等待 `STARTUP_WAIT_BEFORE_CLICK_TOGETHER_MS`
  - 进入一起聊后等待 `TOGETHER_REFRESH_WAIT_BEFORE_PULL_MS`
  - 执行一次下滑刷新手势，再等待 `TOGETHER_REFRESH_SETTLE_MS`
  - 每次从直播间返回后，等待 `LIVE_ROOM_RETURN_TOGETHER_WAIT_MS` 再点击下一卡片
- 一起聊列表翻页流程（v1.0.24）：
  - 当“当前可见区域无新卡片”时，执行上滑手势（`TOGETHER_SCROLL_SWIPE_*`）加载下一屏卡片。
  - 上滑后等待 `TOGETHER_SCROLL_SETTLE_MS`，并受 `TOGETHER_SCROLL_RETRY_INTERVAL_MS` 限流。
  - 日志会输出本屏 `visible/processed/unprocessed` 统计与 `emptyStreak` 连续空屏次数。
- 一起聊上滑起点调整（v1.0.29）：
  - 上滑手势改为从屏幕中部开始（`startY=0.60H` -> `endY=0.32H`）。
  - 目的：避开底部 `mainTab` 区域（约 `y=1752~1920`），减少误触底部按钮切页。
- 一起聊卡片点击安全区（v1.0.30）：
  - 卡片识别阶段增加底部安全区过滤：仅处理 `card.bottom <= 0.90H` 的卡片。
  - 对底部半遮挡卡片先不点击，优先上滑后再处理，避免点击落入 `mainTab` 触发“关注/消息/我的”切页。
- 一起聊循环完成判定（v1.0.37）：
  - 判定前提：`visibleTotal>0` 且 `unprocessedCount=0` 且已处理过卡片。
  - v1.0.37 起必须“同时满足”：
    - `emptyStreak >= TOGETHER_CYCLE_COMPLETE_EMPTY_STREAK`（当前 4）
    - `signatureStreak >= TOGETHER_CYCLE_COMPLETE_SAME_SIGNATURE_STREAK`（当前 2）
  - 用于避免“上滑次数过少就误判完整循环”。
  - 命中后按“每次循环后等待时间（秒）”发送重启请求给 `GlobalFloatService`，执行 force-stop + 重启 LOOK，进入下一轮采集。
- 一起聊循环次数限制（v1.0.34）：
  - 新增设置项 `一起聊直播间循环点击次数`，默认 `0`（无限循环）。
  - 每完成一次“全量卡片处理循环”即计数 +1。
  - 当 `N>0` 且累计完成次数达到 `N`，模块自动停止，不再触发下一轮重启。
- 一起聊循环后等待（v1.0.35）：
  - 新增设置项 `每次循环后等待时间（秒）`，默认 `0`。
  - 每次完整循环完成后，会先等待该秒数，再重启 LOOK 进入下一轮。
  - 与“循环点击次数”独立生效：达到次数上限时仍会直接停止，不再进入下一轮。
- 一起聊循环完成提示（v1.0.36）：
  - 完整循环完成时会弹出轻提示：`本轮进入直播间 X 个，还有 Y 次循环`。
  - `Y` 在无限循环模式下显示为“无限”。
  - 该提示为 Toast 轻提示，约 3 秒自动淡化消失。
- 一起聊循环完成提示稳定性修复（v1.0.37）：
  - 循环完成提示改为由模块进程接收广播后弹出，不再依赖 LOOK 进程弹窗。
  - 避免 LOOK 被立即重启/force-stop 时提示被吞掉的问题。
- 悬浮按钮二轮消失修复（v1.0.38）：
  - 修复 `GlobalFloatService` 对 `displayId` 的处理：仅在广播显式携带 `target_display_id` 时才切换显示屏并重建悬浮窗。
  - 修复 `FloatServiceBootstrap` 的启动参数：未携带显示屏信息的普通广播不再默认传 `-1` 覆盖当前屏。
  - 解决“第二次及以后完整循环时悬浮按钮消失”问题。
- 直播间悬浮按钮可见性修复（v1.0.39）：
  - 修复 LOOK 进程同步广播携带 `displayId=-1` 时误切到默认屏的问题。
  - `LookHookEntry` 仅在 `displayId>=0` 时才携带 `target_display_id`，避免直播间场景把悬浮窗切离当前显示屏。

## 6. 直播间进入校验规则

- 必须命中以下 3 个节点（配置见 `UiComponentConfig.java`）：
  - `com.netease.play:id/roomNo` / `android.widget.TextView`
  - `com.netease.play:id/title` / `android.widget.TextView`
  - `com.netease.play:id/closeBtn` / `android.widget.ImageView`
- 宽松校验兜底（v1.0.19）：
  - 若严格校验未通过，但当前已是直播间 Activity 且命中 `title + closeBtn`，则按“宽松模式”继续执行直播间任务。
  - 用于兼容 `roomNo` 组件偶发缺失的直播间页面，避免进入后无反应。
- 登录拦截即时处理（v1.0.26）：
  - 在直播间校验阶段若识别到 `menu_login_with_setting + qq + weibo + phone + cloudmusic` 组合，立即执行返回并跳过当前卡片。
  - 不再等待重试耗尽后才返回，缩短异常卡片停留时间。
- 直播间校验超时：
  - 独立超时参数 `LIVE_ROOM_VERIFY_TIMEOUT_MS`（当前 9000ms）。
- 直播间校验重试：
  - 显式重试上限参数 `LIVE_ROOM_VERIFY_MAX_RETRY_COUNT`（当前 18 次）。
  - 日志会输出重试进度（例如 `直播间校验重试 3/18`），便于确认是否真的持续重试。
  - 若重试耗尽且当前仍处于直播间 Activity，会自动执行返回，避免卡在直播间无后续动作。
- 标题一致性：
  - 读取直播间 `com.netease.play:id/title` 的 `text`
  - 与最近一次点击卡片的 `index=1..4` 子节点采集文本做“完全相等”比对
  - 若不一致，判定为未进入目标直播间，执行返回，不运行直播间任务

## 7. 直播间任务

- 主进程调用关系：
  - `LookHookEntry` 在识别“已离开一起聊并进入直播间检测阶段”后，调用 `LiveRoomRuntimeModule.run(...)`。
  - `LiveRoomRuntimeModule` 负责：直播间组件校验、标题一致性校验、触发 `live_room_enter_task`、执行返回动作。
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

- 架构说明（v1.0.16）：
  - 广告处理默认切换为“无障碍实时服务”架构（`LookAccessibilityAdService`）。
  - 无障碍服务通过 `AccessibilityCustomRulesAdEngine` 实时扫描并执行 `CustomRules.ini` 规则。
  - 当“无障碍实时广告服务（替代LSP）”开启时，LSP 进程内的 `RealtimeAdLoop` 不再执行广告处理。
- 单实例窗口策略（v1.0.23）：
  - 无障碍广告扫描默认只处理“当前显示屏的 active/focused 窗口”。
  - 不再全量多屏并发扫描，保证广告处理行为与当前可见界面一致。
- 无障碍详细日志（v1.0.23）：
  - 新增 `ALL` 复合规则缺失项日志：`ALL规则未满足 ... missing=[...]`（限频输出）。
  - 新增窗口级跳过日志：`当前显示屏未命中 LOOK 窗口，跳过本轮`（限频输出）。
- 实时处理机制（v1.0.14）：
  - 新增独立模块 `RealtimeAdLoop`，与主流程状态机解耦，按固定间隔循环扫描广告规则。
  - 扫描节奏：
    - 调度间隔：`AD_REALTIME_LOOP_INTERVAL_MS`
    - 规则最小扫描间隔：`AD_SCAN_INTERVAL_MS`
  - 当前实现仍运行在 `com.netease.play` 进程内（LSPosed 注入进程），以便实时访问当前 Activity 的 UI 树。
- 规则优先级：
  - 外部 `CustomRules.ini`
  - 模块内置 `assets/CustomRules.ini`
- `com.netease.play` 使用 hash key：`1144086404`。
- 当前已加规则：`com.netease.play:id/slidingContainer -> GLOBAL_ACTION_BACK`（出现即返回）。
- 当前已加复合条件规则：
  - 当 `menu_login_with_setting`、`qq`、`weibo`、`phone`、`cloudmusic` 五个组件都存在时，执行 `GLOBAL_ACTION_BACK` 返回。
- 点击执行策略（v1.0.14）：
  - `performClick` -> `callOnClick` -> 触摸点击（tap）
  - 若命中规则但动作失败，输出限频日志：`命中规则但动作失败`
- 无障碍点击策略（v1.0.16）：
  - `AccessibilityNodeInfo.ACTION_CLICK` -> 手势点击节点中心点（gesture tap）
  - 失败时沿父级节点回退继续尝试点击
- 广告处理成功提示（v1.0.35）：
  - 每次规则动作成功后，会显示系统 Toast 提示“广告已处理”，约 2 秒自动淡化消失。
  - 提示为非阻塞轻提示，不影响主流程继续运行。

## 9. 日志

- `XposedBridge.log` + `logcat`
- 前缀：`[LOOKLspModule]` / `[LOOKAdRules]` / `[LOOKScriptRunner]`
- 点击日志：
  - 每次实际点击成功时输出组件详情：`class/id/text/clickable/shown/bounds` 与点击方式（`performClick/callOnClick/tap`）。
  - 点击“一起聊”tab 与点击直播卡片均会输出对应组件详情。
- 诊断日志增强（v1.0.24）：
  - 主流程“非首页/非HomeActivity”日志会附带 `activity` 类名。
  - 直播间校验会输出“校验开始/当前是否直播间 Activity/重试进度”。
- 去重策略：
  - 高频等待类流程日志启用重复抑制，相同文案不重复输出，避免无效刷屏。
- 模块本地日志文件（每次运行轮转）：
  - 当前运行：`/sdcard/Android/data/com.oodbye.looklspmodule/files/look_lsp_run_current.log`
  - 上次运行：`/sdcard/Android/data/com.oodbye.looklspmodule/files/look_lsp_run_previous.log`
- 轮转规则：
  - 新一次“运行”开始时，把 `current` 重命名为 `previous`
  - 创建新的 `current` 写入本次日志
  - 仅保留这两份文件
