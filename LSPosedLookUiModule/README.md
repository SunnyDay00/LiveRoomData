# LSPosedLookUiModule

独立新建的 LOOK 直播 LSP 模块（包名目标：`com.netease.play`）。

## 运行逻辑说明（长期维护）

- 文档：`MODULE_RUNTIME_FLOW.md`
- 约定：后续每次新增/修改功能都同步更新该文档。

## 已实现要求

- 所有 UI 组件参数集中在：
  - `app/src/main/java/com/oodbye/looklspmodule/UiComponentConfig.java`
- 设置页包含两个开关：
  - `全局悬浮按钮`
  - `广告处理`
- 全局悬浮按钮支持：
  - `运行 / 暂停 / 停止`
- 运行按钮逻辑：
  - 检查 LOOK 是否在运行
  - 若在运行：先 `force-stop` 再启动
  - 若未运行：直接启动
- 模块主循环会根据以下四个组件判断 LOOK 首页：
  - `推荐` + `com.netease.play:id/tv_dragon_tab`
  - `听听` + `com.netease.play:id/tv_dragon_tab`
  - `一起聊` + `com.netease.play:id/tv_dragon_tab`
  - `看看` + `com.netease.play:id/tv_dragon_tab`
- 广告处理规则来源：
  - 优先读取：`/sdcard/Android/data/com.oodbye.looklspmodule/files/CustomRules.ini`
  - 否则读取模块内置：`app/src/main/assets/CustomRules.ini`
- 运行日志文件（每次“运行”自动轮转，仅保留两份）：
  - 本次：`/sdcard/Android/data/com.oodbye.looklspmodule/files/look_lsp_run_current.log`
  - 上次：`/sdcard/Android/data/com.oodbye.looklspmodule/files/look_lsp_run_previous.log`

## 目录

- `app/src/main/java/com/oodbye/looklspmodule/LookHookEntry.java`：LSPosed 入口与主循环
- `app/src/main/java/com/oodbye/looklspmodule/GlobalFloatService.java`：全局悬浮按钮服务
- `app/src/main/java/com/oodbye/looklspmodule/CustomRulesAdProcessor.java`：广告规则解析与执行
- `app/src/main/java/com/oodbye/looklspmodule/ModuleSettingsActivity.java`：设置页
- `app/src/main/java/com/oodbye/looklspmodule/ModuleSettings.java`：开关和状态配置
- `app/src/main/java/com/oodbye/looklspmodule/UiComponentConfig.java`：UI 组件参数配置文件（唯一参数入口）
