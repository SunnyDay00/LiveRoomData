import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import List, Optional, Tuple
from xml.dom import minidom


def _avoid_local_module_shadowing() -> None:
    """避免脚本文件名与三方包同名导致循环导入。"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    cwd = os.path.abspath(os.getcwd())
    sys.path[:] = [p for p in sys.path if os.path.abspath(p or cwd) != script_dir]


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


def _list_online_devices(adb: str) -> List[str]:
    proc = _run([adb, "devices"])
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "执行 adb devices 失败")
    serials: List[str] = []
    for line in proc.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def _pick_device(adb: str, serial: Optional[str]) -> str:
    online = _list_online_devices(adb)
    if serial:
        if serial not in online:
            raise RuntimeError(f"指定设备不在线：{serial}")
        return serial
    if not online:
        raise RuntimeError("未检测到在线设备，请先确认 adb devices 有 device 状态。")
    if len(online) > 1:
        raise RuntimeError(
            f"检测到多台在线设备：{', '.join(online)}，请用 --serial 指定。"
        )
    return online[0]


def _get_focused_windows(adb: str, serial: str) -> List[Tuple[str, str]]:
    proc = _run([adb, "-s", serial, "shell", "dumpsys", "window"])
    if proc.returncode != 0:
        return []
    current_display = "?"
    results: List[Tuple[str, str]] = []
    for raw_line in proc.stdout.splitlines():
        line = raw_line.strip()
        display_match = re.search(r"Display:\s*mDisplayId=(\d+)", line)
        if display_match:
            current_display = display_match.group(1)
        focus_match = re.search(r"mCurrentFocus=(.+)$", line)
        if focus_match:
            value = focus_match.group(1).strip()
            if value != "null":
                results.append((current_display, value))
    return results


def _extract_first_package(xml: str) -> Optional[str]:
    match = re.search(r'<node[^>]*\spackage="([^"]+)"', xml)
    if match:
        return match.group(1)
    return None


def _format_xml(xml: str, pretty: bool) -> str:
    if not pretty:
        return xml
    try:
        dom = minidom.parseString(xml.encode("utf-8"))
        pretty_xml = dom.toprettyxml(indent="  ", encoding="utf-8").decode("utf-8")
        lines = [line for line in pretty_xml.splitlines() if line.strip()]
        return "\n".join(lines) + "\n"
    except Exception:
        return xml


def _dump_via_adb(adb: str, serial: str, output: Path, compressed: bool) -> str:
    remote = f"/sdcard/window_dump_{int(time.time())}.xml"
    cmd = [adb, "-s", serial, "shell", "uiautomator", "dump"]
    if compressed:
        cmd.append("--compressed")
    cmd.append(remote)
    dump_proc = _run(cmd)
    if dump_proc.returncode != 0:
        raise RuntimeError(dump_proc.stderr.strip() or dump_proc.stdout.strip() or "uiautomator dump 失败")

    pull_proc = _run([adb, "-s", serial, "pull", remote, str(output)])
    _run([adb, "-s", serial, "shell", "rm", "-f", remote])
    if pull_proc.returncode != 0:
        raise RuntimeError(pull_proc.stderr.strip() or pull_proc.stdout.strip() or "adb pull 失败")

    if not output.exists():
        raise RuntimeError("导出文件不存在，可能 pull 失败。")
    return output.read_text(encoding="utf-8", errors="replace")


def _dump_via_u2(serial: str) -> str:
    _avoid_local_module_shadowing()
    try:
        import uiautomator2 as u2
    except ModuleNotFoundError as exc:
        raise RuntimeError("未找到依赖：uiautomator2，请先安装。") from exc

    device = u2.connect(serial)
    return device.dump_hierarchy(compressed=False)


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取 Android 当前界面的 UI 树 XML")
    parser.add_argument("--serial", help="adb 设备序列号，不传则自动选择唯一在线设备")
    parser.add_argument(
        "--output",
        default="live_ui.xml",
        help="输出 XML 文件路径，默认 live_ui.xml",
    )
    parser.add_argument(
        "--method",
        choices=["adb", "u2"],
        default="adb",
        help="抓取方式：adb(推荐，默认) / u2",
    )
    parser.add_argument(
        "--compressed",
        action="store_true",
        help="仅对 adb 模式生效，开启 uiautomator --compressed",
    )
    parser.add_argument(
        "--raw",
        action="store_true",
        help="不做 XML 美化，按原始单行内容保存",
    )
    parser.add_argument(
        "--adb-path",
        help="指定 adb 可执行文件路径（可选）",
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
    focused = _get_focused_windows(adb, serial)
    if focused:
        print("当前焦点窗口（按 display）：")
        for display_id, window in focused:
            print(f"  display {display_id}: {window}")

    output = Path(args.output).resolve()

    try:
        if args.method == "adb":
            xml = _dump_via_adb(adb, serial, output, args.compressed)
        else:
            xml = _dump_via_u2(serial)
    except Exception as exc:  # noqa: BLE001
        print(f"抓取 UI 树失败：{exc}")
        return 1

    final_xml = _format_xml(xml, pretty=not args.raw)
    output.write_text(final_xml, encoding="utf-8")

    package = _extract_first_package(xml) or "未知"
    print(f"UI 树已保存到 {output}")
    print(f"首个节点 package：{package}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
