/**
 * UI.js - 主UI脚本
 * 展示“LOOK直播”入口，并在点击后弹出全屏确认遮罩。
 * 确认运行则启动 LOOK_Main.js，取消则直接停止脚本。
 */

// UI 模板：全屏 frame，底部是主内容，顶层是全屏确认遮罩
<template fullscreen="true">
  <frame width="matchParent" height="matchParent" backgroundColor="#F5F6FA">
    <!-- 主面板 -->
    <linear id="main_panel" orientation="vertical" width="matchParent" height="matchParent" padding="24" gravity="top|center_horizontal">
      <text text="直播数据采集" size="28" gravity="center" width="matchParent" style="bold" textColor="#000000" layoutMargin="0 12 50 12"/>

      <!-- 卡片 (回退为 Linear) -->
      <linear orientation="vertical" width="matchParent" backgroundColor="#89beffff" padding="22" layoutMargin="0 0 0 0" layoutGravity="center_horizontal">
        <text text="采集平台" size="24" gravity="left" textColor="#111827" layoutMargin="0 0 0 10"/>
        <linear orientation="horizontal" width="matchParent" height="1" backgroundColor="#E5E7EB" layoutMargin="0 0 0 14"/>

        <linear orientation="horizontal" width="matchParent" gravity="center_vertical" layoutMargin="0 14 0 0">
          <linear orientation="vertical" layoutWeight="1" width="0">
            <text text="LOOK直播数据采集" size="14" gravity="left" textColor="#000000ff" layoutMargin="11 0 0 10"/>
            <text text="运行LOOK.main脚本，采集LOOK直播数据。" size="10" gravity="left" layoutMargin="0 0 0 10" textColor="#367055ff"/>
          </linear>
          <!-- 运行按钮 (回退为 Button) -->
          <button id="btn_start" text="运行" width="80" height="42" layoutMargin="12 0 0 0" onClick="onStartClick" backgroundColor="#ff5a4bff" textColor="#FFFFFF" gravity="center"/>
        </linear>
      </linear>
    </linear>

    <!-- 全屏确认遮罩 -->
    <frame id="confirm_mask" width="matchParent" height="matchParent" backgroundColor="#80000000" visibility="gone">
      <linear orientation="vertical" width="matchParent" height="matchParent" gravity="center" padding="24">
        <!-- 弹窗卡片 (回退为 Linear) -->
        <linear orientation="vertical" width="matchParent" backgroundColor="#FFFFFF" padding="22" gravity="center">
          <text text="是否运行？" size="22" gravity="center" width="matchParent" textColor="#000000" layoutMargin="0 8 0 6"/>
          <text text="LOOK直播数据采集脚本" size="14" gravity="center" width="matchParent" textColor="#1B5E20" layoutMargin="0 0 0 14"/>
          <linear orientation="horizontal" width="matchParent" layoutMargin="0 18 0 0" gravity="center">
            <!-- 取消按钮 (自定义布局) -->
            <linear id="btn_cancel" orientation="vertical" width="120" height="50" backgroundColor="#FAD7D7" layoutMargin="20 20 0 0" onClick="onCancelClick" gravity="center">
                <text text="不运行" size="18" textColor="#111827" layoutMargin="0 20 0 0" gravity="center"/>
                <text text="退出" size="12" textColor="#111827" layoutMargin="0 20 0 0" gravity="center"/>
            </linear>
            <!-- 确认按钮 (自定义布局) -->
            <linear id="btn_confirm" orientation="vertical" width="120" height="50" backgroundColor="#C8F7D4" layoutMargin="20 0 0 0" onClick="onConfirmClick" gravity="center">
                <text text="运行" size="18" textColor="#111827" layoutMargin="0 50 0 0" gravity="center"/>
                <text text="继续" size="12" textColor="#111827" layoutMargin="0 20 0 0" gravity="center"/>
            </linear>
          </linear>
        </linear>
      </linear>
    </frame>
  </frame>
</template>

function main() {
  // 创建并显示 UI（aznfz-docs/ui/UI介绍.md）
  setupUI();
}

// 点击“LOOK直播”按钮，显示全屏确认遮罩
function onStartClick() {
  ui('main_panel').setVisibility('gone'); // 避免遮罩下的文字叠影
  ui('confirm_mask').setVisibility('visible');
}

// 取消运行：隐藏遮罩并停止脚本
function onCancelClick() {
  ui('confirm_mask').setVisibility('gone');
  ui('main_panel').setVisibility('visible');
  stop();
}

// 确认运行：隐藏遮罩并启动采集脚本
function onConfirmClick() {
  ui('confirm_mask').setVisibility('gone');
  ui('main_panel').setVisibility('visible');
  // UI 线程不做耗时操作，运行任务交给 runTask（aznfz-docs/全局函数.md）
  runTask('LOOK_Main.js');
}
