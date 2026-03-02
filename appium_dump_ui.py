#!/usr/bin/env python3
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import List, Optional

from appium import webdriver
from appium.options.android import UiAutomator2Options


def _run(cmd: List[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, capture_output=True)


def _find_adb(adb_arg: Optional[str]) -> Optional[str]:
    candidates: List[str] = []
    if adb_arg:
        candidates.append(adb_arg)
    env_adb = os.environ.get("ADB")
    if env_adb:
        candidates.append(env_adb)
    candidates.extend(
        [
            "adb",
            os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
        ]
    )
    for candidate in candidates:
        if os.path.sep in candidate:
            if os.path.exists(candidate):
                return candidate
            continue
        found = shutil.which(candidate)
        if found:
            return found
    return None


def _pick_device(adb: str, serial: Optional[str]) -> str:
    proc = _run([adb, "devices"])
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "执行 adb devices 失败")
    serials: List[str] = []
    for line in proc.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])

    if serial:
        if serial not in serials:
            raise RuntimeError(f"指定设备不在线：{serial}")
        return serial
    if not serials:
        raise RuntimeError("未检测到在线设备，请先确认 adb devices 有 device 状态。")
    if len(serials) > 1:
        raise RuntimeError(f"检测到多台在线设备：{', '.join(serials)}，请用 --serial 指定。")
    return serials[0]


def main() -> int:
    parser = argparse.ArgumentParser(description="通过 Appium 抓取当前 Android 界面 UI 树")
    parser.add_argument("--serial", help="adb 设备序列号，不传则自动选择唯一在线设备")
    parser.add_argument("--adb-path", help="指定 adb 可执行文件路径（可选）")
    parser.add_argument("--appium-url", default="http://127.0.0.1:4723", help="Appium 服务地址")
    parser.add_argument("--output", default="current_ui_appium.xml", help="输出 XML 文件路径")
    parser.add_argument("--app-package", help="可选，指定 appPackage 以显式附着到某应用")
    parser.add_argument("--app-activity", help="可选，指定 appActivity（通常与 --app-package 配合）")
    parser.add_argument(
        "--wait-for-idle-timeout",
        type=int,
        default=0,
        help="Appium setting: waitForIdleTimeout，默认 0",
    )
    parser.add_argument(
        "--wait-for-selector-timeout",
        type=int,
        default=0,
        help="Appium setting: waitForSelectorTimeout，默认 0",
    )
    args = parser.parse_args()

    adb = _find_adb(args.adb_path)
    if not adb:
        print("未找到 adb，请安装 Android platform-tools 或通过 --adb-path 指定。")
        return 1

    try:
        serial = _pick_device(adb, args.serial)
    except RuntimeError as exc:
        print(f"设备检查失败：{exc}")
        return 1

    print(f"使用设备：{serial}")
    options = UiAutomator2Options().load_capabilities(
        {
            "platformName": "Android",
            "appium:automationName": "UiAutomator2",
            "appium:udid": serial,
            "appium:noReset": True,
            "appium:newCommandTimeout": 120,
            "appium:skipDeviceInitialization": True,
            "appium:skipUnlock": True,
            "appium:autoGrantPermissions": False,
        }
    )
    if args.app_package:
        options.set_capability("appium:appPackage", args.app_package)
    if args.app_activity:
        options.set_capability("appium:appActivity", args.app_activity)

    driver = None
    try:
        driver = webdriver.Remote(command_executor=args.appium_url, options=options)
        settings = {
            "waitForIdleTimeout": max(0, int(args.wait_for_idle_timeout)),
            "waitForSelectorTimeout": max(0, int(args.wait_for_selector_timeout)),
        }
        driver.update_settings(settings)
        xml = driver.page_source
        output = Path(args.output).resolve()
        output.write_text(xml, encoding="utf-8")
        print(f"UI 树已保存到 {output}")
        print(f"已应用 Appium settings: {settings}")
        print(f"XML 长度：{len(xml)}")
        return 0
    except Exception as exc:  # noqa: BLE001
        print("抓取失败：")
        print(str(exc))
        print("请确认 Appium 服务正在运行：appium --port 4723")
        return 1
    finally:
        if driver is not None:
            try:
                driver.quit()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
