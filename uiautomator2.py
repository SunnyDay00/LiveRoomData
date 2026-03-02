import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib import error as urlerror
from urllib import parse as urlparse
from urllib import request as urlrequest
from xml.dom import minidom

_VERBOSE = False


def _set_verbose(enabled: bool) -> None:
    global _VERBOSE
    _VERBOSE = enabled


def _debug(message: str) -> None:
    if _VERBOSE:
        print(f"[debug] {message}")


def _preview(text: str, limit: int = 1200) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + f"\n...（已截断，剩余 {len(text) - limit} 字符）"


def _avoid_local_module_shadowing() -> None:
    """避免脚本文件名与三方包同名导致循环导入。"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    cwd = os.path.abspath(os.getcwd())
    sys.path[:] = [p for p in sys.path if os.path.abspath(p or cwd) != script_dir]


def _run(cmd: List[str]) -> subprocess.CompletedProcess[str]:
    start = time.time()
    proc = subprocess.run(cmd, text=True, capture_output=True)
    elapsed = time.time() - start
    if _VERBOSE:
        _debug(f"$ {shlex.join(cmd)}")
        _debug(f"exit={proc.returncode} elapsed={elapsed:.2f}s")
        if proc.stdout.strip():
            _debug("stdout:\n" + _preview(proc.stdout.strip()))
        if proc.stderr.strip():
            _debug("stderr:\n" + _preview(proc.stderr.strip()))
    return proc


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


def _find_windows_by_package(adb: str, serial: str, package_name: str) -> List[str]:
    if not package_name:
        return []
    proc = _run([adb, "-s", serial, "shell", "dumpsys", "window", "windows"])
    if proc.returncode != 0:
        return []
    out: List[str] = []
    for raw_line in proc.stdout.splitlines():
        line = raw_line.strip()
        if "Window{" not in line:
            continue
        if package_name not in line:
            continue
        out.append(line)
    return out


def _extract_first_package(xml: str) -> Optional[str]:
    match = re.search(r'\spackage="([^"]+)"', xml)
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


def _probe_events_on_idle_error(adb: str, serial: str, seconds: float = 2.0) -> Optional[str]:
    cmd = [adb, "-s", serial, "shell", "uiautomator", "events"]
    start = time.time()
    proc = subprocess.Popen(
        cmd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        stdout, stderr = proc.communicate(timeout=seconds)
    except subprocess.TimeoutExpired:
        proc.kill()
        stdout, stderr = proc.communicate()
    elapsed = time.time() - start
    lines = [line for line in stdout.splitlines() if line.strip()]
    changed = sum("TYPE_WINDOW_CONTENT_CHANGED" in line for line in lines)
    windows = sum("TYPE_WINDOWS_CHANGED" in line for line in lines)
    summary = (
        "辅助诊断（uiautomator events）："
        + f" 采样 {elapsed:.2f}s，事件总数={len(lines)}，"
        + f"TYPE_WINDOW_CONTENT_CHANGED={changed}，TYPE_WINDOWS_CHANGED={windows}。"
    )
    if stderr.strip():
        summary += "\n事件采样 stderr：\n" + _preview(stderr.strip(), limit=600)
    return summary


def _dump_via_adb(adb: str, serial: str, output: Path, compressed: bool) -> str:
    remote = f"/sdcard/window_dump_{int(time.time())}.xml"
    cmd = [adb, "-s", serial, "shell", "uiautomator", "dump"]
    if compressed:
        cmd.append("--compressed")
    cmd.append(remote)
    dump_proc = _run(cmd)
    if dump_proc.returncode != 0:
        raise RuntimeError(dump_proc.stderr.strip() or dump_proc.stdout.strip() or "uiautomator dump 失败")

    ls_proc = _run([adb, "-s", serial, "shell", "ls", "-l", remote])
    if ls_proc.returncode != 0:
        details = [
            "uiautomator dump 执行后未找到导出文件。",
            f"远端路径：{remote}",
        ]
        dump_out = "\n".join(
            part for part in [dump_proc.stdout.strip(), dump_proc.stderr.strip()] if part
        )
        if dump_out:
            details.append("uiautomator dump 输出：")
            details.append(dump_out)
        if "could not get idle state" in dump_out.lower():
            details.append(
                "诊断：设备界面长期不空闲（动态视频/动画/高频刷新）导致 UIAutomator 无法完成层级抓取。"
            )
            probe = _probe_events_on_idle_error(adb, serial, seconds=2.0)
            if probe:
                details.append(probe)
        ls_out = "\n".join(part for part in [ls_proc.stdout.strip(), ls_proc.stderr.strip()] if part)
        if ls_out:
            details.append("远端文件检查输出：")
            details.append(ls_out)
        raise RuntimeError("\n".join(details))

    pull_proc = _run([adb, "-s", serial, "pull", remote, str(output)])
    _run([adb, "-s", serial, "shell", "rm", "-f", remote])
    if pull_proc.returncode != 0:
        raise RuntimeError(pull_proc.stderr.strip() or pull_proc.stdout.strip() or "adb pull 失败")

    if not output.exists():
        raise RuntimeError("导出文件不存在，可能 pull 失败。")
    return output.read_text(encoding="utf-8", errors="replace")


def _is_idle_state_error(exc: BaseException) -> bool:
    return "could not get idle state" in str(exc).lower()


def _invoke_u2_configurator(func: Any, payload: Dict[str, int]) -> Tuple[bool, str]:
    err_messages: List[str] = []
    try:
        func(payload)
        return True, "dict"
    except Exception as exc:  # noqa: BLE001
        err_messages.append(f"dict 参数失败：{exc}")
    try:
        func(**payload)
        return True, "kwargs"
    except Exception as exc:  # noqa: BLE001
        err_messages.append(f"kwargs 参数失败：{exc}")
    return False, "；".join(err_messages)


def _configure_u2_no_idle(device: Any, idle_ms: int, selector_ms: int) -> None:
    payload = {
        "waitForIdleTimeout": max(0, int(idle_ms)),
        "waitForSelectorTimeout": max(0, int(selector_ms)),
    }
    call_errors: List[str] = []
    candidates: List[Tuple[str, Any]] = []

    jsonrpc = getattr(device, "jsonrpc", None)
    if jsonrpc is not None:
        for name in ("setConfigurator", "set_configurator"):
            func = getattr(jsonrpc, name, None)
            if callable(func):
                candidates.append((f"device.jsonrpc.{name}", func))
    for name in ("setConfigurator", "set_configurator"):
        func = getattr(device, name, None)
        if callable(func):
            candidates.append((f"device.{name}", func))

    for full_name, func in candidates:
        ok, mode_or_err = _invoke_u2_configurator(func, payload)
        if ok:
            _debug(f"u2 配置成功：{full_name}（调用方式={mode_or_err}）payload={payload}")
            return
        call_errors.append(f"{full_name}: {mode_or_err}")

    details = " | ".join(call_errors) if call_errors else "未找到可用的 configurator 接口"
    raise RuntimeError("设置 u2 waitForIdleTimeout 失败：" + details)


def _dump_via_u2(
    serial: str,
    no_idle: bool,
    u2_wait_for_idle_timeout_ms: int,
    u2_wait_for_selector_timeout_ms: int,
) -> str:
    _avoid_local_module_shadowing()
    try:
        import uiautomator2 as u2
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "未找到依赖：uiautomator2，请先安装：python3 -m pip install -U uiautomator2"
        ) from exc

    device = u2.connect(serial)
    if no_idle:
        _configure_u2_no_idle(
            device,
            idle_ms=u2_wait_for_idle_timeout_ms,
            selector_ms=u2_wait_for_selector_timeout_ms,
        )
    return device.dump_hierarchy(compressed=False)


def _parse_appium_url(appium_url: str) -> Tuple[str, str, int, str]:
    parsed = urlparse.urlsplit(appium_url)
    scheme = parsed.scheme or "http"
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or 4723
    base_path = parsed.path.rstrip("/")
    base = f"{scheme}://{host}:{port}"
    return base, host, port, base_path


def _appium_endpoint(appium_url: str, suffix: str) -> str:
    base, _, _, base_path = _parse_appium_url(appium_url)
    clean_suffix = suffix if suffix.startswith("/") else "/" + suffix
    return f"{base}{base_path}{clean_suffix}"


def _http_json(
    method: str,
    url: str,
    payload: Optional[Dict[str, Any]] = None,
    timeout: float = 20.0,
) -> Dict[str, Any]:
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urlrequest.Request(url, data=data, headers=headers, method=method.upper())
    try:
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except urlerror.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"HTTP {exc.code} {method.upper()} {url}\n{body or exc.reason}"
        ) from exc
    except urlerror.URLError as exc:
        raise RuntimeError(f"请求失败 {method.upper()} {url}: {exc}") from exc
    if not raw.strip():
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        raise RuntimeError(f"响应不是合法 JSON：{raw[:300]}")


def _is_appium_server_ready(appium_url: str, timeout: float = 2.0) -> bool:
    try:
        _http_json("GET", _appium_endpoint(appium_url, "/status"), timeout=timeout)
        return True
    except Exception:
        return False


def _start_local_appium_server(
    appium_url: str,
    auto_start: bool,
    log_file: Optional[str],
) -> Tuple[bool, Optional[subprocess.Popen[str]], Optional[Any]]:
    if _is_appium_server_ready(appium_url, timeout=2.0):
        return False, None, None
    if not auto_start:
        raise RuntimeError("Appium 服务未启动，请先运行：appium --port 4723")

    appium_bin = shutil.which("appium")
    if not appium_bin:
        raise RuntimeError("未找到 appium 命令，请先安装：npm i -g appium")

    _, host, port, base_path = _parse_appium_url(appium_url)
    if host not in {"127.0.0.1", "localhost", "0.0.0.0"}:
        raise RuntimeError(f"仅支持自动拉起本地 Appium，当前 host={host}")

    stdout = subprocess.DEVNULL
    stderr = subprocess.DEVNULL
    stream: Optional[Any] = None
    if log_file:
        log_path = Path(log_file).expanduser().resolve()
        log_path.parent.mkdir(parents=True, exist_ok=True)
        stream = open(log_path, "a", encoding="utf-8")
        stdout = stream
        stderr = stream

    cmd = [appium_bin, "--port", str(port), "--base-path", base_path or "/"]
    proc = subprocess.Popen(cmd, stdout=stdout, stderr=stderr, text=True)
    for _ in range(40):
        if _is_appium_server_ready(appium_url, timeout=1.5):
            _debug(f"本地 Appium 已就绪，pid={proc.pid}")
            return True, proc, stream
        if proc.poll() is not None:
            if stream is not None:
                stream.close()
            raise RuntimeError(f"Appium 进程提前退出，exit={proc.returncode}")
        time.sleep(0.25)
    try:
        proc.terminate()
    except Exception:
        pass
    if stream is not None:
        stream.close()
    raise RuntimeError("等待 Appium 启动超时。")


def _extract_session_id(create_resp: Dict[str, Any]) -> str:
    sid = create_resp.get("sessionId")
    if isinstance(sid, str) and sid:
        return sid
    value = create_resp.get("value")
    if isinstance(value, dict):
        sid2 = value.get("sessionId")
        if isinstance(sid2, str) and sid2:
            return sid2
    raise RuntimeError(f"创建 Appium session 失败：{create_resp}")


def _dump_via_appium(
    serial: str,
    appium_url: str,
    auto_start_server: bool,
    appium_log_file: Optional[str],
    wait_for_idle_timeout: int,
    wait_for_selector_timeout: int,
) -> str:
    started_here = False
    local_proc: Optional[subprocess.Popen[str]] = None
    log_stream: Optional[Any] = None
    session_id = ""
    try:
        started_here, local_proc, log_stream = _start_local_appium_server(
            appium_url=appium_url,
            auto_start=auto_start_server,
            log_file=appium_log_file,
        )

        create_payload = {
            "capabilities": {
                "alwaysMatch": {
                    "platformName": "Android",
                    "appium:automationName": "UiAutomator2",
                    "appium:udid": serial,
                    "appium:noReset": True,
                    "appium:newCommandTimeout": 120,
                    "appium:skipDeviceInitialization": True,
                    "appium:skipUnlock": True,
                    "appium:autoGrantPermissions": False,
                },
                "firstMatch": [{}],
            }
        }
        create_resp = _http_json(
            "POST",
            _appium_endpoint(appium_url, "/session"),
            payload=create_payload,
            timeout=60.0,
        )
        session_id = _extract_session_id(create_resp)
        _debug(f"Appium session created: {session_id}")

        settings_payload = {
            "settings": {
                "waitForIdleTimeout": max(0, int(wait_for_idle_timeout)),
                "waitForSelectorTimeout": max(0, int(wait_for_selector_timeout)),
            }
        }
        _http_json(
            "POST",
            _appium_endpoint(appium_url, f"/session/{session_id}/appium/settings"),
            payload=settings_payload,
            timeout=20.0,
        )

        source_resp = _http_json(
            "GET",
            _appium_endpoint(appium_url, f"/session/{session_id}/source"),
            timeout=60.0,
        )
        value = source_resp.get("value")
        if not isinstance(value, str) or not value.strip():
            raise RuntimeError(f"Appium 返回的 source 为空：{source_resp}")
        return value
    finally:
        if session_id:
            try:
                _http_json(
                    "DELETE",
                    _appium_endpoint(appium_url, f"/session/{session_id}"),
                    timeout=20.0,
                )
            except Exception:
                pass
        if started_here and local_proc is not None:
            try:
                local_proc.terminate()
                local_proc.wait(timeout=5)
            except Exception:
                try:
                    local_proc.kill()
                except Exception:
                    pass
        if log_stream is not None:
            try:
                log_stream.close()
            except Exception:
                pass


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
        choices=["auto", "adb", "u2", "u2-no-idle", "appium"],
        default="auto",
        help="抓取方式：auto(默认，adb失败后自动切 appium) / adb / u2 / u2-no-idle / appium",
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
    parser.add_argument(
        "--check-window-package",
        help="额外检查该包名在 dumpsys window windows 中是否存在窗口（可用于悬浮窗巡检）",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="打印 adb 子命令详细日志（返回码/stdout/stderr）",
    )
    parser.add_argument(
        "--retry",
        type=int,
        default=1,
        help="adb 模式下最大抓取尝试次数（仅 idle 错误会重试，默认 1）",
    )
    parser.add_argument(
        "--retry-interval",
        type=float,
        default=0.6,
        help="adb 模式下重试间隔秒数（默认 0.6）",
    )
    parser.add_argument(
        "--u2-wait-for-idle-timeout-ms",
        type=int,
        default=0,
        help="u2-no-idle 模式下 waitForIdleTimeout 毫秒值（默认 0）",
    )
    parser.add_argument(
        "--u2-wait-for-selector-timeout-ms",
        type=int,
        default=0,
        help="u2-no-idle 模式下 waitForSelectorTimeout 毫秒值（默认 0）",
    )
    parser.add_argument(
        "--appium-url",
        default="http://127.0.0.1:4723",
        help="Appium 服务地址，默认 http://127.0.0.1:4723",
    )
    parser.add_argument(
        "--no-appium-auto-start",
        action="store_true",
        help="禁用自动拉起本地 Appium（默认会自动启动并在完成后关闭）",
    )
    parser.add_argument(
        "--appium-log-file",
        default="/tmp/appium-uiautomator2.log",
        help="自动拉起 Appium 时的日志文件路径，默认 /tmp/appium-uiautomator2.log",
    )
    parser.add_argument(
        "--appium-wait-for-idle-timeout",
        type=int,
        default=0,
        help="Appium setting: waitForIdleTimeout（默认 0）",
    )
    parser.add_argument(
        "--appium-wait-for-selector-timeout",
        type=int,
        default=0,
        help="Appium setting: waitForSelectorTimeout（默认 0）",
    )
    args = parser.parse_args()
    _set_verbose(args.verbose)

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
    if args.check_window_package:
        lines = _find_windows_by_package(adb, serial, args.check_window_package.strip())
        print(
            "窗口巡检 package="
            + args.check_window_package.strip()
            + f" count={len(lines)}"
        )
        for idx, line in enumerate(lines[:5], start=1):
            print(f"  [{idx}] {line}")

    output = Path(args.output).resolve()

    try:
        if args.method in {"adb", "auto"}:
            attempts = max(1, args.retry)
            last_exc: Optional[Exception] = None
            xml = ""
            for idx in range(1, attempts + 1):
                try:
                    xml = _dump_via_adb(adb, serial, output, args.compressed)
                    if attempts > 1:
                        print(f"抓取成功：第 {idx}/{attempts} 次尝试。")
                    break
                except Exception as exc:  # noqa: BLE001
                    last_exc = exc
                    if not _is_idle_state_error(exc):
                        raise
                    if idx < attempts:
                        print(
                            f"第 {idx}/{attempts} 次抓取遇到 idle 错误，"
                            + f"{args.retry_interval:.2f}s 后重试..."
                        )
                        time.sleep(max(0.0, args.retry_interval))
            if xml:
                pass
            elif last_exc and args.method == "auto" and _is_idle_state_error(last_exc):
                print("adb 抓取持续遇到 idle 错误，自动切换 Appium 抓取...")
                xml = _dump_via_appium(
                    serial=serial,
                    appium_url=args.appium_url,
                    auto_start_server=not args.no_appium_auto_start,
                    appium_log_file=args.appium_log_file,
                    wait_for_idle_timeout=args.appium_wait_for_idle_timeout,
                    wait_for_selector_timeout=args.appium_wait_for_selector_timeout,
                )
            elif last_exc:
                raise last_exc
            else:
                raise RuntimeError("adb 抓取失败，且未获得有效错误信息。")
        elif args.method in {"u2", "u2-no-idle"}:
            xml = _dump_via_u2(
                serial=serial,
                no_idle=args.method == "u2-no-idle",
                u2_wait_for_idle_timeout_ms=args.u2_wait_for_idle_timeout_ms,
                u2_wait_for_selector_timeout_ms=args.u2_wait_for_selector_timeout_ms,
            )
        else:
            xml = _dump_via_appium(
                serial=serial,
                appium_url=args.appium_url,
                auto_start_server=not args.no_appium_auto_start,
                appium_log_file=args.appium_log_file,
                wait_for_idle_timeout=args.appium_wait_for_idle_timeout,
                wait_for_selector_timeout=args.appium_wait_for_selector_timeout,
            )
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
