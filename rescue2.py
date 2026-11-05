#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DeviceFinder 局域网救援/调试终端
支持环境：Windows / Linux(Armbian) / Termux(Android)

【历史修复 R1~R14】
R1  udp_listener split(":",3)      — 聊天内容含冒号不再被丢弃
R2  _handle_report 用 update()      — 上报不再覆盖 last_chat / last_log
R3  ThreadingHTTPServer             — 并发上报不再排队超时（扫描命中率提升的关键）
R4  send_udp 子网广播也发 3 次      — 与 server2.py 行为一致
R5  send_udp 单播也发 2 次          — 拉日志丢包率下降
R6  _handle_report 不再下发 config  — 不覆盖手机端自定义配置
R7  get_local_ip 多目标 + fib_trie  — 无外网 / Termux 不再返回 127.0.0.1
R8  主菜单"强制上报"指向 PC 接收端   — 不再白等 15 秒超时
R9  计数器读写统一加锁              — 消除隐式竞态
R10 _probe_ips 探测节奏             — ARP 探测与全网段探测间隔 ≥3s，避免节流
R11 _probe_ips 去掉无效 settimeout  — UDP sendto 是立即返回的
R12 LogReceiver 增加 do_GET         — 浏览器访问 9090 可直接健康检查
R13 pull_logs_multi 批量发送        — 从串行 30s/台改为并发+统一等待
R14 choose_target 空列表明确提示    — 不再静默 fallback 到广播

【本次修复 R15~R19 (2026-11-05)】
R15 升级 send_udp 支持 IP 列表      — 实现多台设备精准单播批量发送
R16 合并 choose_target/select_targets — 统一为 choose_targets_multi，支持 1,2 多选，回车默认全选已知设备
R17 拦截拉日志传入广播地址          — 防止传入 255.255.255.255 导致白等 30 秒
R18 UDP 监听加入外层自愈死循环      — 端口被占用/断开后 10 秒自动重试抢回 8888
R19 警报/对讲/低电量移除 no_retry   — 利用默认多次发送机制，降低 UDP 局域网丢包率

【配套手机端修改】
AndroidManifest.xml 需加：
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
TrackingService.java 的 playAlarm() 需配合修改，才能让静音/震动模式下警报响。

【Termux 加速提示】
    pkg install iproute2 net-tools
扫描从 15s 降到 3s。

【重要提醒】
测试扫描/拉日志/悬浮窗时，手机必须保持亮屏（锁屏时 HTTP 上报和悬浮窗被系统限制）。
"""

import socket
import time
import sys
import json as _json
import gzip
import os
import re
import subprocess
import threading
import concurrent.futures
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from datetime import datetime

# [R3] ThreadingHTTPServer 兼容导入（Python 3.7+ 内置，老版本降级）
try:
    from http.server import ThreadingHTTPServer
except ImportError:
    import socketserver
    from http.server import HTTPServer

    class ThreadingHTTPServer(socketserver.ThreadingMixIn, HTTPServer):
        daemon_threads = True
        allow_reuse_address = True


# ===== 配置区 =====
RESCUE_SECRET = "your_private_secret_key"
DEFAULT_SERVER_URL = "http://dingwei.bai001.de5.net/api/report"
DEFAULT_CONFIG_URL = "https://your-config-url.com/config.txt"
LOG_PORT = 9090
LOG_DIR = "received_logs"

# ===== 全局状态 =====
DISCOVERED_DEVICES = {}
_state_lock = threading.Lock()
RECEIVED_REPORTS = 0
RECEIVED_LOGS = 0
RECEIVER_OK = False
UDP_OK = False
# UDP 聊天消息去重（同一 sender+content 5 秒内只显示 1 次）
_LAST_UDP_MSG = {}       # {(sender, content): timestamp}
_LAST_UDP_MSG_TTL = 10   # 清理超过 10 秒的记录


# ===== 环境检测 =====
def is_termux():
    return "com.termux" in os.environ.get("PREFIX", "") or "TERMUX_VERSION" in os.environ


def env_name():
    if os.name == "nt":
        return "Windows"
    if is_termux():
        return "Termux (Android)"
    return "Linux"


# ===== 基础工具 =====
def is_valid_ip(ip):
    parts = ip.split(".")
    if len(parts) != 4:
        return False
    try:
        return all(0 <= int(p) <= 255 for p in parts)
    except Exception:
        return False


def is_valid_url(url):
    if not url:
        return False
    if "://" not in url:
        return False
    if len(url) < 10:
        return False
    return True


# [R9] 统一计数器读取入口，写入/读取都在 _state_lock 内
def _get_counter(wait_type):
    with _state_lock:
        return RECEIVED_REPORTS if wait_type == "report" else RECEIVED_LOGS


# [R7] 多级降级获取本机内网 IP；无外网 / Termux / 无 ip 命令时也能拿到正确 IP
def get_local_ip():
    # 1) 多目标 connect：UDP connect 不需要真实连通，仅用于拿到出口网卡 IP
    for target in [("8.8.8.8", 80), ("114.114.114.114", 53),
                   ("1.1.1.1", 443), ("223.5.5.5", 53)]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(1)
            s.connect(target)
            ip = s.getsockname()[0]
            s.close()
            if ip and not ip.startswith("127.") and ip != "0.0.0.0":
                return ip
        except Exception:
            pass

    # 2) /proc/net/fib_trie：Linux / Termux 通用，无需 root 或 ip 命令
    try:
        with open("/proc/net/fib_trie", "r") as f:
            lines = f.readlines()
        for i, line in enumerate(lines):
            if "LOCAL" in line and i > 0:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", lines[i - 1])
                if m:
                    ip = m.group(1)
                    if (is_valid_ip(ip) and not ip.startswith("127.")
                            and not ip.endswith(".0")
                            and not ip.startswith("169.254.")):
                        return ip
    except Exception:
        pass

    # 3) ip -4 addr
    try:
        result = subprocess.run(["ip", "-4", "addr"], capture_output=True,
                                text=True, timeout=3, errors='ignore')
        for line in result.stdout.split("\n"):
            line = line.strip()
            if line.startswith("inet ") and "127.0.0.1" not in line:
                parts = line.split()
                if len(parts) >= 2:
                    ip = parts[1].split("/")[0]
                    if is_valid_ip(ip) and not ip.startswith("169.254."):
                        return ip
    except Exception:
        pass

    # 4) ifconfig
    try:
        result = subprocess.run(["ifconfig"], capture_output=True,
                                text=True, timeout=3, errors='ignore')
        for line in result.stdout.split("\n"):
            if "inet " in line and "127.0.0.1" not in line:
                parts = line.split()
                for i, p in enumerate(parts):
                    if p == "inet" and i + 1 < len(parts):
                        ip = parts[i + 1]
                        if (is_valid_ip(ip) and not ip.startswith("127.")
                                and not ip.startswith("169.254.")):
                            return ip
    except Exception:
        pass

    # 5) Windows ipconfig
    if os.name == "nt":
        try:
            result = subprocess.run(["ipconfig"], capture_output=True,
                                    text=True, timeout=3, errors='ignore')
            for match in re.finditer(r"IPv4.*?[:：]\s*(\d+\.\d+\.\d+\.\d+)", result.stdout):
                ip = match.group(1)
                if (is_valid_ip(ip) and not ip.startswith("127.")
                        and not ip.startswith("169.254.")):
                    return ip
        except Exception:
            pass

    return "127.0.0.1"


def get_network_prefix(ip):
    parts = ip.split(".")
    if len(parts) == 4:
        return ".".join(parts[:3])
    return "192.168.1"


# ===== UDP 发送 =====
# [R15] 升级：支持传入 IP 列表实现批量单播
def send_udp(command_str, target_ip="255.255.255.255", quiet=False, no_retry=False):
    if isinstance(target_ip, (list, tuple)):
        for ip in target_ip:
            send_udp(command_str, ip, quiet=quiet, no_retry=no_retry)
        return
        
    if not quiet:
        print(f"\n📡 发送指令至 {target_ip}: {command_str}")

    is_broadcast = (target_ip == "255.255.255.255") or target_ip.endswith(".255")
    if no_retry:
        send_times = 1
    else:
        send_times = 3 if is_broadcast else 2
    gap = 0.5 if is_broadcast else 0.15

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        for i in range(send_times):
            try:
                sock.sendto(command_str.encode('utf-8'), (target_ip, 8888))
            except Exception as e:
                if not quiet:
                    print(f"   [{i+1}/{send_times}] 发送失败: {e}")
                break
            if not quiet:
                if send_times > 1:
                    print(f"   [{i+1}/{send_times}] 发送中...")
                else:
                    print(f"   [1/1] 发送成功")
            if i < send_times - 1:
                time.sleep(gap)
    finally:
        sock.close()

    if not quiet:
        print("✅ 发送完毕。")


# [R9] 每次循环重新取计数，读操作走锁
def wait_for_response(before, wait_sec, wait_type):
    start = time.time()
    while time.time() - start < wait_sec:
        time.sleep(0.1)
        if _get_counter(wait_type) > before:
            return time.time() - start
    return None


def send_udp_and_wait(command_str, target_ip="255.255.255.255", wait_sec=15, wait_type="report"):
    """先广播，5 秒无响应自动对已知设备单播"""
    if wait_type is None:
        send_udp(command_str, target_ip)
        return

    before = _get_counter(wait_type)
    send_udp(command_str, target_ip)

    # 兼容列表、具体IP单播
    is_broadcast = isinstance(target_ip, str) and (target_ip == "255.255.255.255" or target_ip.endswith(".255"))
    
    if not is_broadcast:
        elapsed = wait_for_response(before, wait_sec, wait_type)
        if elapsed is not None:
            print(f"✅ 收到响应（{elapsed:.1f}s）")
        else:
            print(f"⚠️ 超时未响应")
        return

    elapsed = wait_for_response(before, 5, wait_type)
    if elapsed is not None:
        print(f"✅ 广播收到响应（{elapsed:.1f}s）")
        return

    with _state_lock:
        known = [(dev_id, info.get("source_ip") or info.get("ip"))
                 for dev_id, info in DISCOVERED_DEVICES.items()
                 if (info.get("source_ip") or info.get("ip")) not in (None, "", "0.0.0.0")]

    if not known:
        print(f"⚠️ 广播 5 秒无响应，且无已知设备可单播")
        print(f"   提示：选 4 查看设备，或选 X 扫描局域网")
        return

    print(f"⚠️ 广播 5 秒无响应，尝试对 {len(known)} 台已知设备单播...")
    for dev_id, ip in known:
        print(f"\n📡 单播到 {dev_id} ({ip})...")
        before = _get_counter(wait_type)
        send_udp(command_str, ip, quiet=True)
        elapsed = wait_for_response(before, 3, wait_type)
        if elapsed is not None:
            print(f"✅ {dev_id} 响应（{elapsed:.1f}s）")
            return
        else:
            print(f"⚠️ {dev_id} 未响应")

    print(f"⚠️ 所有已知设备均未响应")


# [R16] 合并通用多选函数，支持编号、逗号分隔、all、回车全选、scan
def choose_targets_multi(prompt="🎯 选择目标 (编号/逗号分隔/all/回车全选/IP): "):
    with _state_lock:
        devices = dict(DISCOVERED_DEVICES)

    if devices:
        print(f"\n📋 当前已知设备（{len(devices)} 台）：")
        dev_list = list(devices.items())
        now = time.time()
        for i, (dev_id, info) in enumerate(dev_list, 1):
            ip = info.get("source_ip") or info.get("ip", "?")
            batt_raw = info.get("battery")
            batt = f"{batt_raw}%" if batt_raw is not None else "未上报"
            wifi_raw = info.get("wifi")
            wifi = str(wifi_raw)[:16] if wifi_raw else "未上报"
            last = max(info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0))
            if last > 0:
                ago = max(0, int(now - last))
                ago_str = (f"{ago}秒前" if ago < 60 else
                           f"{ago // 60}分钟前" if ago < 3600 else
                           f"{ago // 3600}小时前")
            else:
                ago_str = "从未"
            tag = "" if info.get("last_seen", 0) > 0 else " [仅UDP]"
            print(f"  {i}. {dev_id:<26} ({ip:<15}){tag:<10} {batt:<8} {wifi:<18} {ago_str}")
    else:
        print("\n📭 当前没有已知设备（需先让手机上报一次，或先扫描局域网）")
        dev_list = []

    print("\n请选择目标：")
    print("  编号 (1/2/..., 逗号分隔多选)  → 单播到指定设备")
    print("  all                          → 所有已知设备")
    print("  IP (如 192.168.1.38)         → 单播到指定 IP")
    print("  回车                          → 对所有已知设备操作 (若空则回退广播)")
    print("  scan                         → 先扫描局域网再选择")

    choice = input(prompt).strip()

    if not choice:
        if not devices:
            print("💡 无已知设备，回退到全局广播")
            return ["255.255.255.255"]
        print("💡 未输入编号，默认对当前已知的所有设备操作 (all)")
        return [(info.get("source_ip") or info.get("ip"))
                for info in devices.values()
                if (info.get("source_ip") or info.get("ip")) not in (None, "", "0.0.0.0")]

    if choice.lower() == "scan":
        scan_lan_devices()
        return choose_targets_multi(prompt)
        
    if choice.lower() == "all":
        if not devices:
            return ["255.255.255.255"]
        return [(info.get("source_ip") or info.get("ip"))
                for info in devices.values()
                if (info.get("source_ip") or info.get("ip")) not in (None, "", "0.0.0.0")]
                
    if is_valid_ip(choice):
        return [choice]
        
    try:
        idxs = [int(x.strip()) for x in choice.split(",") if x.strip().isdigit()]
        result = []
        for i in idxs:
            if 0 < i <= len(dev_list):
                ip = dev_list[i - 1][1].get("source_ip") or dev_list[i - 1][1].get("ip")
                if ip:
                    result.append(ip)
        return result
    except Exception:
        return []


# ===== HTTP 接收端 =====
class LogReceiver(BaseHTTPRequestHandler):

    # [R12] 健康检查：浏览器访问 9090 直接可看到状态，不再 501
    def do_GET(self):
        try:
            with _state_lock:
                n = len(DISCOVERED_DEVICES)
                r = RECEIVED_REPORTS
                l = RECEIVED_LOGS
            body = (
                "DeviceFinder receiver OK\n"
                f"devices={n}\n"
                f"reports={r}\n"
                f"logs={l}\n"
                f"log_dir={os.path.abspath(LOG_DIR)}\n"
            )
            data = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception:
            pass

    def do_POST(self):
        try:
            if self.path.startswith("/api/upload_log"):
                self._handle_upload_log()
            elif self.path.startswith("/api/report"):
                self._handle_report()
            else:
                self.send_response(404)
                self.end_headers()
        except Exception as e:
            print(f"❌ HTTP 接收异常: {e}")
            try:
                self.send_response(500)
                self.end_headers()
            except Exception:
                pass

    def _handle_upload_log(self):
        global RECEIVED_LOGS
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)

        query = parse_qs(urlparse(self.path).query)
        token = query.get("token", [""])[0]
        if token != RESCUE_SECRET:
            print("⚠️ 拒绝上传：token 不匹配")
            self.send_response(403)
            self.end_headers()
            return

        try:
            data = gzip.decompress(body)
        except Exception:
            data = body

        device_id = query.get("device_id", ["unknown"])[0]
        safe_id = "".join(c for c in device_id if c.isalnum() or c in "_-")
        ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        filename = f"{safe_id}_{ts}.log"
        filepath = os.path.join(LOG_DIR, filename)

        os.makedirs(LOG_DIR, exist_ok=True)
        with open(filepath, "wb") as f:
            f.write(data)

        with _state_lock:
            RECEIVED_LOGS += 1
            info = DISCOVERED_DEVICES.setdefault(device_id, {})
            info["source_ip"] = self.client_address[0]
            info["last_log"] = time.time()

        print(f"\n📥 收到日志: {filename} ({len(data)} bytes) → {os.path.abspath(filepath)}")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    # [R2] 用 update 而非整体替换，保留 last_chat / last_log
    # [R6] 不再下发 config，避免覆盖手机端自定义设置
    def _handle_report(self):
        global RECEIVED_REPORTS
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode('utf-8', errors='ignore')
        try:
            data = _json.loads(body)
            if data.get("token") != RESCUE_SECRET:
                self.send_response(403)
                self.end_headers()
                return

            dev_id = data.get("device_id", "?")
            batt = data.get("battery", {}).get("level", "?")
            is_chg = data.get("battery", {}).get("is_charging", False)
            wifi = data.get("network", {}).get("wifi_ssid", "?")
            local_ip = data.get("network", {}).get("local_ip", "?")
            loc = data.get("location", {})
            lat = loc.get("latitude")
            lng = loc.get("longitude")
            acc = loc.get("accuracy", -1)
            provider = loc.get("provider", "?")
            nearby = data.get("nearby_wifi", [])

            if lat is not None and lng is not None:
                try:
                    loc_str = f"{lat},{lng} (精度 {float(acc):.0f}m, {provider})"
                except Exception:
                    loc_str = f"{lat},{lng} ({provider})"
            else:
                loc_str = "无定位"
            chg_str = "充电中" if is_chg else "未充电"

            print(f"\n📥 [{dev_id}] 电量 {batt}% ({chg_str}) | WiFi {wifi} | IP {local_ip}")
            print(f"   位置: {loc_str} | 周围热点: {len(nearby)} 个")

            with _state_lock:
                RECEIVED_REPORTS += 1
                info = DISCOVERED_DEVICES.setdefault(dev_id, {})
                info.update({
                    "ip": local_ip if local_ip and local_ip != "0.0.0.0" else self.client_address[0],
                    "source_ip": self.client_address[0],
                    "last_seen": time.time(),
                    "battery": batt,
                    "wifi": wifi,
                })
        except Exception as e:
            print(f"⚠️ 解析上报失败: {e}")

        resp = b'{"status":"success"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, format, *args):
        pass


# [R3] 关键修复：并发接收上报，多台手机同时响应不再排队超时
def start_log_receiver():
    global RECEIVER_OK
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        server = ThreadingHTTPServer(("0.0.0.0", LOG_PORT), LogReceiver)
        RECEIVER_OK = True
        local_ip = get_local_ip()
        print(f"📥 内置接收端已启动: http://{local_ip}:{LOG_PORT}/")
        print(f"   /api/report      → 实时接收上报")
        print(f"   /api/upload_log  → 接收日志，存到 {os.path.abspath(LOG_DIR)}")
        print(f"   GET /            → 健康检查")
        server.serve_forever()
    except OSError as e:
        if "Address already in use" in str(e) or e.errno in (98, 10048):
            print(f"⚠️ 端口 {LOG_PORT} 被占用，内置接收端未启动")
        else:
            print(f"⚠️ 内置接收端启动失败: {e}")


# ===== UDP 监听 =====
def udp_listener():
    global UDP_OK
    while True:  # ★★★ [R18] 新增：外层死循环，实现绑定失败自愈 ★★★
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(('0.0.0.0', 8888))
            UDP_OK = True
            print("🎧 UDP 监听已启动，等待手机对讲消息...")
            
            # 内层死循环：负责接收数据
            while True:
                try:
                    data, addr = sock.recvfrom(4096)
                    msg = data.decode('utf-8', errors='ignore')
                    if msg.startswith("FIND_ME_MSG:"):
                        # [R1] maxsplit=3，content 含冒号时不再被丢弃
                        parts = msg.split(":", 3)
                        if len(parts) == 4 and parts[1] == RESCUE_SECRET:
                            sender = parts[2]
                            content = parts[3]

                            # [新增] 5 秒去重：手机发子网+全局共 2 包，只显示 1 条
                            dedup_key = (sender, content)
                            now_ts = time.time()
                            if now_ts - _LAST_UDP_MSG.get(dedup_key, 0) < 5.0:
                                continue
                            _LAST_UDP_MSG[dedup_key] = now_ts

                            # 记录数超过 100 时清理过期项
                            if len(_LAST_UDP_MSG) > 100:
                                cutoff = now_ts - _LAST_UDP_MSG_TTL
                                for k in [k for k, v in _LAST_UDP_MSG.items() if v < cutoff]:
                                    _LAST_UDP_MSG.pop(k, None)

                            print(f"\n🗣️ [{sender}] {content}  (来自 {addr[0]})")
                            with _state_lock:
                                info = DISCOVERED_DEVICES.setdefault(sender, {})
                                info["source_ip"] = addr[0]
                                info["last_chat"] = time.time()
                except Exception as e:
                    print(f"⚠️ UDP 接收异常: {e}")
                    time.sleep(1)

        except OSError as e:
            UDP_OK = False
            if e.errno in (98, 10048):
                print("⚠️ UDP 8888 被占用（可能 server2.py 或 chat.py 正在运行）")
                print("   ⏳ 10 秒后自动重试绑定...")
            else:
                print(f"⚠️ UDP 监听绑定失败: {e}，10 秒后重试...")
            time.sleep(10)  # ★ 休眠 10 秒后，外层 while True 会再次尝试 bind
            continue        # 重新开始循环，再次尝试绑定
        finally:
            # 每次重试前或异常退出时，确保旧 socket 被关闭
            try: sock.close()
            except Exception: pass


# ===== 设备列表 / 状态摘要 =====
def show_devices():
    with _state_lock:
        devices = dict(DISCOVERED_DEVICES)
    if not devices:
        print("\n📭 暂无已发现设备")
        print("   提示：选 1 或 2 让手机上报，或选 X 扫描局域网")
        return

    now = time.time()
    print(f"\n📋 已发现设备（{len(devices)} 台）：")
    print(f"{'设备ID':<28} {'IP':<16} {'电量':<6} {'WiFi':<20} {'最后活跃':<10}")
    print("-" * 90)
    for dev_id, info in devices.items():
        last = max(info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0))
        if last > 0:
            ago = max(0, int(now - last))
            ago_str = (f"{ago}秒前" if ago < 60 else
                       f"{ago // 60}分钟前" if ago < 3600 else
                       f"{ago // 3600}小时前" if ago < 86400 else "很久前")
        else:
            ago_str = "从未"
        ip = info.get("source_ip") or info.get("ip", "?")
        batt_raw = info.get("battery")
        batt = f"{batt_raw}%" if batt_raw is not None else "未上报"
        wifi_raw = info.get("wifi")
        wifi = str(wifi_raw)[:18] if wifi_raw else "未上报"
        tag = "" if info.get("last_seen", 0) > 0 else " [仅UDP]"
        print(f"{dev_id:<28} {ip:<16}{tag:<10} {batt:<8} {wifi:<20} {ago_str:<10}")
    print()


def status_line():
    with _state_lock:
        dev_count = len(DISCOVERED_DEVICES)
        latest = 0
        for info in DISCOVERED_DEVICES.values():
            latest = max(latest, info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0))

    if latest > 0:
        ago = max(0, int(time.time() - latest))
        ago_str = (f"{ago}秒前" if ago < 60 else
                   f"{ago // 60}分钟前" if ago < 3600 else
                   f"{ago // 3600}小时前")
    else:
        ago_str = "无"

    if is_termux():
        return f"💡 {dev_count}台 | 收{'✅' if RECEIVER_OK else '❌'} | UDP{'✅' if UDP_OK else '❌'} | 最近活动 {ago_str}"
    return (f"💡 设备 {dev_count} 台  |  接收端 {LOG_PORT} {'✅' if RECEIVER_OK else '❌'}"
            f"  |  UDP {'✅' if UDP_OK else '❌'}  |  最近活动 {ago_str}")

# ===== ARP 查询 3 级降级 =====
def _try_ip_neigh(prefix):
    ips = set()
    try:
        result = subprocess.run(["ip", "neigh"], capture_output=True,
                                text=True, timeout=3, errors='ignore')
        for line in result.stdout.split("\n"):
            for token in line.split():
                if token.startswith(prefix + ".") and is_valid_ip(token):
                    ips.add(token)
    except Exception:
        pass
    return ips


def _try_arp_a(prefix):
    ips = set()
    try:
        result = subprocess.run(["arp", "-a"], capture_output=True,
                                text=True, timeout=3, errors='ignore')
        for match in re.finditer(r"(\d+\.\d+\.\d+\.\d+)", result.stdout):
            ip = match.group(1)
            if ip.startswith(prefix + ".") and is_valid_ip(ip):
                ips.add(ip)
    except Exception:
        pass
    return ips


def _try_proc_net_arp(prefix):
    ips = set()
    try:
        if not os.path.exists("/proc/net/arp"):
            return ips
        with open("/proc/net/arp", "r") as f:
            for line in f.readlines()[1:]:
                parts = line.split()
                if len(parts) >= 1 and parts[0].startswith(prefix + ".") and is_valid_ip(parts[0]):
                    ips.add(parts[0])
    except Exception:
        pass
    return ips


def get_arp_ips(prefix):
    ips = set()
    ips.update(_try_ip_neigh(prefix))
    ips.update(_try_arp_a(prefix))
    ips.update(_try_proc_net_arp(prefix))
    return list(ips)


# ===== 局域网扫描 =====
# [R11] 去掉无效的 settimeout（UDP sendto 是立即返回的）
# [R10] ARP 探测与全网段探测之间保持 ≥3s 间隔，避免手机端 2 秒节流丢包
def _probe_ips(ips, local_ip):
    temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
    cmd = f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}"

    def probe(ip):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.sendto(cmd.encode(), (ip, 8888))
            s.close()
        except Exception:
            pass

    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as ex:
        list(ex.map(probe, ips))


def scan_lan_devices():
    local_ip = get_local_ip()
    prefix = get_network_prefix(local_ip)

    print(f"\n🔍 正在扫描局域网 {prefix}.1-254 ...")
    print(f"   本机 IP: {local_ip}")
    print(f"   环境: {env_name()}")
    print(f"   ⚠️ 手机必须亮屏，否则 HTTP 上报到 PC 会被系统限制")

    if is_termux():
        print(f"💡 Termux 加速：pkg install iproute2 net-tools")

    scan_start = time.time()

    arp_ips = [ip for ip in get_arp_ips(prefix) if ip != local_ip]
    if arp_ips:
        print(f"📋 ARP 表里找到 {len(arp_ips)} 个 IP，先探测...")
        _probe_ips(arp_ips, local_ip)
        # [R10] 明确间隔，避免手机端 2 秒节流丢弃第二次探测
        time.sleep(3)
    else:
        print(f"⚠️ ARP 表为空")

    with _state_lock:
        found = len([d for d in DISCOVERED_DEVICES.values() if d.get("last_seen", 0) >= scan_start])

    if found == 0:
        print(f"⚠️ ARP 未命中，开始全网段扫描（约 10-15 秒）...")
        all_ips = [f"{prefix}.{i}" for i in range(1, 255) if f"{prefix}.{i}" != local_ip]
        _probe_ips(all_ips, local_ip)

    print("⏳ 等待手机响应（10 秒）...")
    time.sleep(10)

    with _state_lock:
        new_devices = {dev_id: info for dev_id, info in DISCOVERED_DEVICES.items()
                       if info.get("last_seen", 0) >= scan_start}

    if not new_devices:
        print("\n❌ 未发现任何运行中的手机")
        print("   可能原因：")
        print("   1. 手机锁屏（HTTP 上报被系统限制）→ 请亮屏重试")
        print("   2. 手机不在同一 WiFi 下（在移动数据）")
        print("   3. 路由器开了 AP 隔离")
        print("   4. 手机被 force-stop（UDP 监听已死）")
        if is_termux():
            print("   5. Termux 需 pkg install iproute2 net-tools")
        return

    print(f"\n✅ 发现 {len(new_devices)} 台手机：")
    print(f"{'设备ID':<28} {'IP':<16} {'电量':<6} {'WiFi':<20}")
    print("-" * 80)
    for dev_id, info in new_devices.items():
        ip = info.get("source_ip") or info.get("ip", "?")
        batt = str(info.get("battery", "?")) + "%"
        wifi = str(info.get("wifi", "?"))[:18]
        print(f"{dev_id:<28} {ip:<16} {batt:<6} {wifi:<20}")


# ===== 定时探测 =====
def periodic_probe(target_ip):
    s = input("探测间隔秒数（回车默认 60）: ").strip()
    interval = int(s) if s.isdigit() and int(s) > 0 else 60
    s = input("总探测时长秒数（回车默认 600）: ").strip()
    duration = int(s) if s.isdigit() and int(s) > 0 else 600

    local_ip = get_local_ip()
    temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
    cmd = f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}"

    print(f"\n🔄 每 {interval} 秒发一次 FIND_ME_REPORT_TEMP（指向 PC:9090），持续 {duration} 秒")
    print(f"   Ctrl+C 可随时停止")
    print(f"   ⚠️ 手机必须亮屏，否则 HTTP 上报被限制")

    end = time.time() + duration
    probe_count = 0
    respond_count = 0

    try:
        while time.time() < end:
            probe_count += 1
            # [R9] 走锁读计数器
            before = _get_counter("report")
            print(f"\n--- 第 {probe_count} 次探测 ---")

            # 改为调用 send_udp 支持列表
            send_udp(cmd, target_ip, quiet=True)

            wait_until = time.time() + 10
            got = False
            while time.time() < wait_until:
                time.sleep(0.2)
                if _get_counter("report") > before:
                    got = True
                    break

            if got:
                respond_count += 1
                print("   ✅ 收到响应")
            else:
                print("   ⚠️ 未响应")

            remaining = end - time.time()
            if remaining > interval:
                time.sleep(interval)
            elif remaining > 0:
                time.sleep(remaining)
    except KeyboardInterrupt:
        print("\n⏸ 探测已手动停止")

    rate = (respond_count / probe_count * 100) if probe_count > 0 else 0
    print(f"\n📊 探测结果: {probe_count} 次发送，{respond_count} 次响应，响应率 {rate:.0f}%")


# [R17] 改为"先全发 → 统一等"，并修复传入广播地址导致的假死
def pull_logs_multi(target_ips, temp_url, clear_after):
    if not target_ips:
        print("⚠️ 未选择任何有效目标，已取消操作")
        return

    # ★★★ [R17] 拦截广播地址 ★★★
    if target_ips == ["255.255.255.255"]:
        print("⚠️ 拉日志不能使用广播，请选择具体设备编号或输入 all")
        return

    cmd = f"FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}:{temp_url}" + (":clear" if clear_after else "")
    send_time = time.time()

    print(f"\n📡 向 {len(target_ips)} 台设备发送拉日志指令")
    print(f"   ⚠️ 手机必须亮屏，否则 HTTP 上传被限制")
    for ip in target_ips:
        # [关键] no_retry=True：拉日志只发 1 次，避免手机重复上传 2 份
        send_udp(cmd, ip, quiet=True, no_retry=True)
        time.sleep(0.1)

    print(f"⏳ 等待手机上传（最多 30 秒）...")     
    deadline = time.time() + 30                   
    received_ips = set()

    while time.time() < deadline:
        time.sleep(0.5)
        with _state_lock:
            for dev_id, info in DISCOVERED_DEVICES.items():
                src_ip = info.get("source_ip")
                if src_ip in target_ips and src_ip not in received_ips:
                    if info.get("last_log", 0) >= send_time:
                        received_ips.add(src_ip)
                        print(f"   ✅ {src_ip} 已上传 ({dev_id})")
        if len(received_ips) >= len(target_ips):
            break

    print(f"\n📊 完成: {len(received_ips)}/{len(target_ips)} 台成功")
    missing = [ip for ip in target_ips if ip not in received_ips]
    if missing:
        print(f"⚠️ 未响应: {', '.join(missing)}")
        print(f"   可能原因：手机已切移动数据 / 锁屏 / 拉日志指令丢失")


# ===== 二级菜单 =====
def menu_a_server_config():
    while True:
        print("\n" + "=" * 50)
        print("📂 A. 服务器与配置")
        print("=" * 50)
        print("  1. 🌐 换主服务器")
        print("  2. ⚙️ 换配置中心")
        print("  3. ⏱️ 改上报间隔（给出 ADB 命令）")
        print("  4. 🕵️ 临时服务器上报")
        print("  0. ← 返回一级菜单")
        print("=" * 50)

        c = input("请选择 (0-4): ").strip()
        if c == '0':
            return
        if c == '1':
            url = input(f"新主服务器 URL (回车默认 {DEFAULT_SERVER_URL}): ").strip() or DEFAULT_SERVER_URL
            if not is_valid_url(url):
                print(f"❌ URL 非法: {url}")
                continue
            send_udp(f"FIND_ME_SERVER:{RESCUE_SECRET}:{url}", choose_targets_multi())
        elif c == '2':
            url = input(f"新配置中心 URL (回车默认 {DEFAULT_CONFIG_URL}): ").strip() or DEFAULT_CONFIG_URL
            if not is_valid_url(url):
                print(f"❌ URL 非法: {url}")
                continue
            send_udp(f"FIND_ME_CONFIG:{RESCUE_SECRET}:{url}", choose_targets_multi())
        elif c == '3':
            sec = input("上报间隔秒数（默认 1800）: ").strip()
            if sec.isdigit():
                print(f"\n⚠️ 需 ADB 执行：")
                print(f'adb shell am startservice -n com.example.devicefinder/.TrackingService '
                      f'-a ACTION_UPDATE_RUNTIME_CONFIG --es report_interval_seconds {sec}')
        elif c == '4':
            url = input(f"临时 URL (回车默认 {DEFAULT_SERVER_URL}): ").strip() or DEFAULT_SERVER_URL
            if not is_valid_url(url):
                print(f"❌ URL 非法: {url}")
                continue
            send_udp(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{url}", choose_targets_multi())


def menu_b_cloud_switch():
    while True:
        print("\n" + "=" * 50)
        print("📂 B. 云端与开关")
        print("=" * 50)
        print("  1. ☁️ 开启云端日志")
        print("  2. ☁️ 关闭云端日志")
        print("  3. 🔒 锁定聊天")
        print("  4. 🔓 解锁聊天")
        print("  0. ← 返回一级菜单")
        print("=" * 50)

        c = input("请选择 (0-4): ").strip()
        if c == '0':
            return
        if c == '1':
            send_udp(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:1", choose_targets_multi())
        elif c == '2':
            send_udp(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:0", choose_targets_multi())
        elif c == '3':
            send_udp(f"FIND_ME_CHAT_LOCK:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '4':
            send_udp(f"FIND_ME_CHAT_UNLOCK:{RESCUE_SECRET}", choose_targets_multi())


def menu_c_message_cleanup(local_ip):
    while True:
        print("\n" + "=" * 50)
        print("📂 C. 消息与清理")
        print("=" * 50)
        print("  1. 💬 悬浮文字（需手机亮屏）")
        print("  2. 🧹 清空留言")
        print("  3. 📱 设置紧急号码")
        print("  4. 📋 拉日志（自定义 URL）")
        print("  0. ← 返回一级菜单")
        print("=" * 50)

        c = input("请选择 (0-4): ").strip()
        if c == '0':
            return
        if c == '1':
            msg = input("悬浮文字内容: ").strip()
            if msg:
                send_udp(f"FIND_ME_MSG:{RESCUE_SECRET}:{msg}", choose_targets_multi())
        elif c == '2':
            send_udp(f"FIND_ME_CLEAR_HISTORY:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '3':
            num = input("报警短信手机号: ").strip()
            if num:
                send_udp(f"FIND_ME_SET_SMS_NUM:{RESCUE_SECRET}:{num}", choose_targets_multi())
        elif c == '4':
            url = input(f"接收 URL (回车默认 http://{local_ip}:{LOG_PORT}/api/report): ").strip()
            if not url:
                url = f"http://{local_ip}:{LOG_PORT}/api/report"
            if not is_valid_url(url):
                print(f"❌ URL 非法: {url}")
                continue
            targets = choose_targets_multi()
            if not targets:
                continue
            clear = input("上传后清空手机本地日志？(y/N): ").strip().lower() == 'y'
            pull_logs_multi(targets, url, clear)


def menu_d_diagnostic():
    while True:
        print("\n" + "=" * 50)
        print("📂 D. 诊断工具")
        print("=" * 50)
        print("  1. 📡 扫描 WiFi")
        print("  2. 🔄 重启 UDP")
        print("  3. 🧪 模拟低电量")
        print("  4. 🔄 定时探测")
        print("  5. 🔍 扫描局域网")
        print("  0. ← 返回一级菜单")
        print("=" * 50)

        c = input("请选择 (0-5): ").strip()
        if c == '0':
            return
        if c == '1':
            send_udp(f"FIND_ME_SCAN_WIFI:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '2':
            send_udp(f"FIND_ME_RESTART_UDP:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '3':
            # ★★★ [R19] 移除 no_retry=True，利用多次发送降低丢包率
            send_udp(f"FIND_ME_TEST_LOW_BATTERY:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '4':
            periodic_probe(choose_targets_multi())
        elif c == '5':
            scan_lan_devices()


# ===== 主菜单 =====
def print_main_menu(local_ip):
    print("\n" + "=" * 50)
    print("🛠️  DeviceFinder 救援终端")
    print(f"   {env_name()}  |  {local_ip}")
    print("=" * 50)
    print("\n📱 高频操作")
    print("  1. 📍 强制上报（指向 PC 接收端，实时查看）")   # [R8] 语义变更提示
    print("  2. 📍 定位+上报")
    print("  3. 📋 拉取日志（内置）")
    print("  4. 📋 查看设备")
    print("  5. 🔊 播放警报")
    print("  6. 🗣️ 对讲机")
    print("\n📂 更多分类（输入字母进入）")
    if is_termux():
        print("  A. 🌐 服务器与配置")
        print("  B. ☁️ 云端与开关")
        print("  C. 💬 消息与清理")
        print("  D. 🔧 诊断工具")
    else:
        print("  A. 🌐 服务器与配置  →  换 URL / 改间隔 / 临时上报")
        print("  B. ☁️ 云端与开关    →  云端日志 / 聊天锁")
        print("  C. 💬 消息与清理    →  悬浮 / 留言 / 短信 / 自定义拉日志")
        print("  D. 🔧 诊断工具      →  扫描 / 探测 / 重启")
    print("\n  X. 🔍 快速扫描局域网")
    print("  0. 🚪 退出")
    print("=" * 50)
    print(status_line())
    print("=" * 50)


def main():
    local_ip = get_local_ip()

    while True:
        print_main_menu(local_ip)
        choice = input("请输入 (0-6, A-D, X): ").strip().upper().rstrip(".、,，")

        if choice == '0':
            print("👋 退出。")
            break
        if choice == 'X':
            scan_lan_devices()
            continue
        if choice == 'A':
            menu_a_server_config()
            continue
        if choice == 'B':
            menu_b_cloud_switch()
            continue
        if choice == 'C':
            menu_c_message_cleanup(local_ip)
            continue
        if choice == 'D':
            menu_d_diagnostic()
            continue

        if choice == '1':
            # [R8] 改为 FIND_ME_REPORT_TEMP 指向 PC 接收端，不再白等 15 秒超时
            target = choose_targets_multi()
            temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
            print(f"📤 强制上报（指向 PC 接收端 {temp_url}）")
            send_udp_and_wait(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}", target, 15, "report")
        elif choice == '2':
            target = choose_targets_multi()
            temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
            send_udp(f"FIND_ME_LOCATE_ONCE:{RESCUE_SECRET}", target, quiet=True)
            print("⏳ 等待手机定位（10 秒）...")
            time.sleep(10)
            send_udp_and_wait(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}", target, 15, "report")
        elif choice == '3':
            url = f"http://{local_ip}:{LOG_PORT}/api/report"
            print(f"📥 内置接收端: {url}")
            print(f"   保存目录: {os.path.abspath(LOG_DIR)}")
            targets = choose_targets_multi()
            if not targets:
                continue
            clear = input("上传后清空手机本地日志？(y/N): ").strip().lower() == 'y'
            pull_logs_multi(targets, url, clear)
        elif choice == '4':
            show_devices()
        elif choice == '5':
            print("\n⚠️ 手机将以最大音量播放 30 秒警报（静音/震动也会响）")
            # ★★★ [R19] 移除 no_retry=True
            send_udp(f"FIND_ME_ALARM:{RESCUE_SECRET}", choose_targets_multi())
        elif choice == '6':
            chat = input("对讲消息内容: ").strip()
            if chat:
                # ★★★ [R19] 移除 no_retry=True
                send_udp(f"FIND_ME_MSG:{RESCUE_SECRET}:PC_Terminal:{chat}", choose_targets_multi())


if __name__ == "__main__":
    print("\n🚀 正在启动内置服务...")
    print(f"   环境: {env_name()}")
    print(f"   本机 IP: {get_local_ip()}")
    if is_termux():
        print(f"💡 Termux 加速：pkg install iproute2 net-tools")
    threading.Thread(target=start_log_receiver, daemon=True).start()
    time.sleep(0.3)
    threading.Thread(target=udp_listener, daemon=True).start()
    time.sleep(0.3)
    try:
        main()
    except KeyboardInterrupt:
        print("\n👋 手动中断退出。")
        sys.exit(0)
