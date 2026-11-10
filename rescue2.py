#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DeviceFinder 局域网救援/调试终端 (Ultimate 终极完全体)
============================================================
版本：见 BUILD_VERSION 常量（YYYYMMDD-HHMM 构建时间戳）
核心架构：原生 Socket + ThreadingHTTPServer + ThreadPool 探测 + ADB 跨端控制
============================================================

【一、核心功能】
- 内置接收端 9090：HTTP 上报/日志接收，浏览器访问 / 可健康检查
- 局域网扫描：ARP 表优先 + 全网段探测，多网卡场景遍历所有本机网段
- 多设备定向：单播 / 子网广播 / 全局广播 三档，按 IP 或设备编号选择
- 拉日志：并发下发 + 统一等待 30s，结果汇总缺失清单
- 对讲机：FLOAT/CHAT 双前缀发送与接收，PC 自身消息不记入设备列表
- UDP 8888 监听：断线自愈，指数退避重试，避免端口被占用后永久失聪

【二、协议兼容 v1/v2 双发】
- custom_msg：发 FIND_ME_FLOAT（v2）+ FIND_ME_MSG（v1，内容不含冒号才发）
              含冒号时只发 FLOAT，避免 v1 手机的 legacy 4 段被误判为 chat
- chat_msg  ：同时发 FIND_ME_CHAT（v2）+ FIND_ME_MSG（v1）
- 接收侧    ：认 FIND_ME_CHAT / FIND_ME_MSG 双前缀，5 秒去重
- 版本上报  ：_handle_report 记录 build_version / protocol_version 到 DISCOVERED_DEVICES
- 设备列表  ：show_devices / 选择目标列表 显示 build_version，便于分辨新旧 APK

【三、ADB 超级调试台 (E 菜单) - 稳定防弹版】
- 🔧 一键注入：前台豁免、隐藏权限、白名单
- 📦 极速安装：自动扫描最新 APK，支持签名冲突/防卸载装甲自动强卸重装
- 🩸 生命周期：强制停止、清空数据、拉起界面
- 📸 媒体抓取：截屏、录屏直接保存到电脑
- 🩺 深度体检：抓取系统属性、内核版本、开机时长、后台状态、传感器、基站
- 📥 物理拉日志：无视手机休眠，通过 exec-out 瞬间抽干本地日志
- 🛠️ 指令发射：模拟开机广播、断网、发 ACTION
- 🔍 权限检查：强制 UTF-8 解析，防止 Windows GBK 报错闪退

【四、未来功能预演 (T 菜单)】
- 🏃 传感器探底：一键获取硬件层全量传感器（气压计/计步器/光线）
- 🛰️ 基站探底：抓取 CellIdentity，预演 LBS 基站反查
- 📍 缓存探底：查看系统底层最后已知位置，预演 FMD 降级定位

【五、Termux 加速与运行提示】
    pkg install iproute2 net-tools android-tools
扫描从 15s 降到 3s（ip neigh / arp 表可用，无需全网段盲扫）。
Termux 运行前强烈建议执行：termux-wake-lock

【重要提醒】
- 测试扫描/拉日志/悬浮窗时，手机必须保持亮屏（锁屏时 HTTP 上报和悬浮窗被系统限制）
- 端口 8888 被 server2.py/chat.py 占用时，监听会 10 秒自愈重试，不会永久失聪
- 拉日志不能用广播地址（代码会拦截并提示），必须选具体设备编号或 all
- 手机切 4G 后 source_ip 会变公网 IP，但 source_ip_lan 保留最后局域网 IP，
  显示与单播目标优先 source_ip_lan，能正常控制回局域网
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
import urllib.request
import tempfile
import zipfile
import shutil
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from datetime import datetime

# 解决 Windows 下 stdout 编码报错问题
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except AttributeError:
        pass

try:
    from http.server import ThreadingHTTPServer
except ImportError:
    import socketserver
    from http.server import HTTPServer
    class ThreadingHTTPServer(socketserver.ThreadingMixIn, HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

# ============================================================
# 脚本自身元数据 (Script Metadata)
# ============================================================
BUILD_VERSION = "20261111-2000"
PROTOCOL_VERSION = 2
PACKAGE_NAME = "com.example.devicefinder"

# ===== 配置区 =====
RESCUE_SECRET = "your_private_secret_key"
DEFAULT_SERVER_URL = "http://dingwei.bai001.de5.net/api/report"
DEFAULT_CONFIG_URL = "https://your-config-url.com/config.txt"
LOG_PORT = 9090
UDP_PORT = 8888
LOG_DIR = "received_logs"

# ============================================================
# 智能表情自适应策略 (兼容 Windows CMD/IDLE)
# ============================================================
USE_EMOJI = True
if os.name == "nt":
    if not os.environ.get("WT_SESSION"):
        USE_EMOJI = False

class I:
    ok   = "✅" if USE_EMOJI else "[成功]"
    err  = "❌" if USE_EMOJI else "[错误]"
    warn = "⚠️" if USE_EMOJI else "[警告]"
    send = "📡" if USE_EMOJI else "[发送]"
    recv = "📥" if USE_EMOJI else "[接收]"
    loc  = "📍" if USE_EMOJI else "[定位]"
    chat = "🗣️" if USE_EMOJI else "[对讲]"
    dev  = "📱" if USE_EMOJI else "[设备]"
    term = "🛠️" if USE_EMOJI else ">>"
    ping = "💡" if USE_EMOJI else "[提示]"
    doc  = "🩺" if USE_EMOJI else "[体检]"

# ===== 全局状态 =====
DISCOVERED_DEVICES = {}
_state_lock = threading.Lock()
RECEIVED_REPORTS = 0
RECEIVED_LOGS = 0
RECEIVER_OK = False
UDP_OK = False
_LAST_UDP_MSG = {}
_LAST_UDP_MSG_TTL = 10

# ===== 环境检测 =====
def is_termux():
    return "com.termux" in os.environ.get("PREFIX", "") or "TERMUX_VERSION" in os.environ

def env_name():
    if os.name == "nt": return "Windows"
    if is_termux(): return "Termux (Android)"
    return "Linux"

# ===== 基础工具 =====
def is_valid_ip(ip):
    parts = ip.split(".")
    if len(parts) != 4: return False
    try: return all(0 <= int(p) <= 255 for p in parts)
    except Exception: return False

def is_valid_url(url):
    if not url: return False
    if "://" not in url: return False
    if len(url) < 10: return False
    return True

def _get_counter(wait_type):
    with _state_lock:
        return RECEIVED_REPORTS if wait_type == "report" else RECEIVED_LOGS

def get_local_ip():
    for target in [("8.8.8.8", 80), ("114.114.114.114", 53)]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(1)
            s.connect(target)
            ip = s.getsockname()[0]
            s.close()
            if ip and not ip.startswith("127.") and ip != "0.0.0.0": return ip
        except Exception: pass
    try:
        with open("/proc/net/fib_trie", "r") as f:
            lines = f.readlines()
        for i, line in enumerate(lines):
            if "LOCAL" in line and i > 0:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", lines[i - 1])
                if m:
                    ip = m.group(1)
                    if is_valid_ip(ip) and not ip.startswith("127.") and not ip.endswith(".0"): return ip
    except Exception: pass
    if os.name == "nt":
        try:
            result = subprocess.run(["ipconfig"], capture_output=True, encoding="utf-8", errors="ignore", timeout=3)
            for match in re.finditer(r"IPv4.*?[:：]\s*(\d+\.\d+\.\d+\.\d+)", result.stdout):
                ip = match.group(1)
                if is_valid_ip(ip) and not ip.startswith("127."): return ip
        except Exception: pass
    return "127.0.0.1"

def get_network_prefix(ip):
    parts = ip.split(".")
    if len(parts) == 4: return ".".join(parts[:3])
    return "192.168.1"

def get_all_local_ips():
    ips = []
    seen = set()
    def _add(ip):
        if is_valid_ip(ip) and not ip.startswith("127.") and not ip.endswith(".0") and ip not in seen:
            seen.add(ip)
            ips.append(ip)
    try:
        with open("/proc/net/fib_trie", "r") as f:
            lines = f.readlines()
        for i, line in enumerate(lines):
            if "LOCAL" in line and i > 0:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", lines[i - 1])
                if m: _add(m.group(1))
    except Exception: pass
    if not ips:
        try:
            result = subprocess.run(["ip", "-4", "addr"], capture_output=True, encoding="utf-8", errors="ignore", timeout=3)
            for line in result.stdout.split("\n"):
                line = line.strip()
                if line.startswith("inet ") and "127.0.0.1" not in line:
                    parts = line.split()
                    if len(parts) >= 2: _add(parts[1].split("/")[0])
        except Exception: pass
    if os.name == "nt" and not ips:
        try:
            result = subprocess.run(["ipconfig"], capture_output=True, encoding="utf-8", errors="ignore", timeout=3)
            for match in re.finditer(r"IPv4.*?[:：]\s*(\d+\.\d+\.\d+\.\d+)", result.stdout):
                _add(match.group(1))
        except Exception: pass
    return ips

def _is_private_ip(ip):
    if not is_valid_ip(ip): return False
    parts = [int(p) for p in ip.split(".")]
    if parts[0] == 10: return True
    if parts[0] == 172 and 16 <= parts[1] <= 31: return True
    if parts[0] == 192 and parts[1] == 168: return True
    return False

# ===== UDP 发送 =====
def send_udp(command_str, target_ip="255.255.255.255", quiet=False, no_retry=False):
    if isinstance(target_ip, (list, tuple)):
        if not target_ip:
            if not quiet: print(f"{I.warn} 无有效目标，未发送")
            return
        for ip in target_ip: send_udp(command_str, ip, quiet=quiet, no_retry=no_retry)
        return
    if not quiet: print(f"\n{I.send} 发送指令至 {target_ip}: {command_str}")
    is_broadcast = (target_ip == "255.255.255.255") or target_ip.endswith(".255")
    send_times = 1 if no_retry else (3 if is_broadcast else 2)
    gap = 0.5 if is_broadcast else 0.15
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        for i in range(send_times):
            try: sock.sendto(command_str.encode('utf-8'), (target_ip, UDP_PORT))
            except Exception as e:
                if not quiet: print(f"   [{i+1}/{send_times}] 发送失败: {e}")
                break
            if not quiet and send_times > 1: print(f"   [{i+1}/{send_times}] 发送中...")
            if i < send_times - 1: time.sleep(gap)
    finally: sock.close()
    if not quiet: print(f"{I.ok} 发送完毕。")

def wait_for_response(before, wait_sec, wait_type):
    start = time.time()
    while time.time() - start < wait_sec:
        time.sleep(0.1)
        if _get_counter(wait_type) > before: return time.time() - start
    return None

def send_udp_and_wait(command_str, target_ip="255.255.255.255", wait_sec=15, wait_type="report"):
    if wait_type is None:
        send_udp(command_str, target_ip)
        return
    before = _get_counter(wait_type)
    send_udp(command_str, target_ip)
    is_broadcast = isinstance(target_ip, str) and (target_ip == "255.255.255.255" or target_ip.endswith(".255"))
    mode_desc = "广播" if is_broadcast else ("多台单播" if isinstance(target_ip, (list, tuple)) else "单播")
    if not is_broadcast:
        elapsed = wait_for_response(before, wait_sec, wait_type)
        print(f"{I.ok} {mode_desc}收到响应（{elapsed:.1f}s）" if elapsed else f"{I.warn} {mode_desc}超时未响应")
        return
    elapsed = wait_for_response(before, 5, wait_type)
    if elapsed is not None:
        print(f"{I.ok} 广播收到响应（{elapsed:.1f}s）")
        return
    with _state_lock:
        known = [(dev_id, info.get("source_ip_lan") or info.get("source_ip") or info.get("ip")) for dev_id, info in DISCOVERED_DEVICES.items() if (info.get("source_ip_lan") or info.get("source_ip") or info.get("ip")) not in (None, "", "0.0.0.0")]
    if not known:
        print(f"{I.warn} 广播无响应，且无已知设备")
        return
    print(f"{I.warn} 广播无响应，尝试对 {len(known)} 台设备单播...")
    for dev_id, ip in known:
        before = _get_counter(wait_type)
        send_udp(command_str, ip, quiet=True)
        elapsed = wait_for_response(before, 3, wait_type)
        if elapsed is not None:
            print(f"{I.ok} {dev_id} 响应（{elapsed:.1f}s）")
            return
    print(f"{I.warn} 所有设备未响应")

def choose_targets_multi(prompt="选择目标 (编号/逗号/all/回车全选/IP): "):
    with _state_lock: devices = dict(DISCOVERED_DEVICES)
    if devices:
        print(f"\n当前已知设备（{len(devices)} 台）：")
        dev_list = list(devices.items())
        now = time.time()
        for i, (dev_id, info) in enumerate(dev_list, 1):
            ip = info.get("source_ip_lan") or info.get("source_ip") or info.get("ip", "?")
            batt = f"{info.get('battery', '?')}%"
            last = max(info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0))
            ago = max(0, int(now - last))
            ago_str = f"{ago}秒前" if ago < 60 else (f"{ago//60}分钟前" if ago < 3600 else f"{ago//3600}小时前")
            tag = "" if info.get("last_seen", 0) > 0 else " [仅UDP]"
            print(f"  {i}. {dev_id:<26} ({ip:<15}){tag:<10} {batt:<8} {ago_str}")
    else:
        print("\n当前没有已知设备")
        dev_list = []
    print("\n请选择目标: 编号(1,2)/all/回车全选/IP/scan")
    choice = input(prompt).strip()
    if not choice:
        return ["255.255.255.255"] if not devices else [(info.get("source_ip_lan") or info.get("source_ip") or info.get("ip")) for info in devices.values() if (info.get("source_ip_lan") or info.get("source_ip") or info.get("ip")) not in (None, "", "0.0.0.0")]
    if choice.lower() == "scan":
        scan_lan_devices()
        return choose_targets_multi(prompt)
    if choice.lower() == "all":
        return ["255.255.255.255"] if not devices else [(info.get("source_ip_lan") or info.get("source_ip") or info.get("ip")) for info in devices.values() if (info.get("source_ip_lan") or info.get("source_ip") or info.get("ip")) not in (None, "", "0.0.0.0")]
    if is_valid_ip(choice): return [choice]
    try:
        idxs = [int(x.strip()) for x in choice.split(",") if x.strip().isdigit()]
        return [(dev_list[i - 1][1].get("source_ip_lan") or dev_list[i - 1][1].get("source_ip") or dev_list[i - 1][1].get("ip")) for i in idxs if 0 < i <= len(dev_list)]
    except Exception: return []

# ===== HTTP 接收端 =====
class LogReceiver(BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            with _state_lock: n = len(DISCOVERED_DEVICES); r = RECEIVED_REPORTS; l = RECEIVED_LOGS
            body = f"DeviceFinder receiver OK\ndevices={n}\nreports={r}\nlogs={l}\nlog_dir={os.path.abspath(LOG_DIR)}\n"
            data = body.encode("utf-8")
            self.send_response(200); self.send_header("Content-Type", "text/plain; charset=utf-8"); self.send_header("Content-Length", str(len(data))); self.end_headers()
            self.wfile.write(data)
        except Exception: pass

    def do_POST(self):
        try:
            if self.path.startswith("/api/upload_log"): self._handle_upload_log()
            elif self.path.startswith("/api/report"): self._handle_report()
            else: self.send_response(404); self.end_headers()
        except Exception as e: print(f"{I.err} HTTP 接收异常: {e}")

    def _handle_upload_log(self):
        global RECEIVED_LOGS
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        query = parse_qs(urlparse(self.path).query)
        if query.get("token", [""])[0] != RESCUE_SECRET:
            self.send_response(403); self.end_headers(); return
        try: data = gzip.decompress(body)
        except Exception: data = body
        device_id = query.get("device_id", ["unknown"])[0]
        safe_id = "".join(c for c in device_id if c.isalnum() or c in "_-")
        filename = f"{safe_id}_{datetime.now().strftime('%Y%m%d_%H%M%S_%f')}.log"
        filepath = os.path.join(LOG_DIR, filename)
        os.makedirs(LOG_DIR, exist_ok=True)
        with open(filepath, "wb") as f: f.write(data)
        with _state_lock:
            RECEIVED_LOGS += 1
            info = DISCOVERED_DEVICES.setdefault(device_id, {})
            client_ip = self.client_address[0]
            info["source_ip"] = client_ip
            if _is_private_ip(client_ip): info["source_ip_lan"] = client_ip
            info["last_log"] = time.time()
        print(f"\n{I.recv} 收到日志: {filename} ({len(data)} bytes) -> {os.path.abspath(filepath)}")
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def _handle_report(self):
        global RECEIVED_REPORTS
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode('utf-8', errors='ignore')
        try:
            data = _json.loads(body)
            if data.get("token") != RESCUE_SECRET:
                self.send_response(403); self.end_headers(); return
            dev_id = data.get("device_id", "?")
            batt = data.get("battery", {}).get("level", "?")
            is_chg = data.get("battery", {}).get("is_charging", False)
            wifi = data.get("network", {}).get("wifi_ssid", "?")
            local_ip = data.get("network", {}).get("local_ip", "?")
            loc = data.get("location", {})
            lat, lng = loc.get("latitude"), loc.get("longitude")
            acc, provider = loc.get("accuracy", -1), loc.get("provider", "?")
            loc_str = f"{lat},{lng} (精度 {float(acc):.0f}m, {provider})" if lat else "无定位"
            chg_str = "充电中" if is_chg else "未充电"
            print(f"\n{I.recv} [{dev_id}] 电量 {batt}% ({chg_str}) | WiFi {wifi} | IP {local_ip}")
            print(f"   位置: {loc_str} | 周围热点: {len(data.get('nearby_wifi', []))} 个")
            with _state_lock:
                RECEIVED_REPORTS += 1
                info = DISCOVERED_DEVICES.setdefault(dev_id, {})
                client_ip = self.client_address[0]
                info.update({"ip": local_ip, "source_ip": client_ip, "last_seen": time.time(), "battery": batt, "wifi": wifi, "build_version": data.get("build_version") or "旧版"})
                if _is_private_ip(client_ip): info["source_ip_lan"] = client_ip
        except Exception as e: print(f"{I.warn} 解析上报失败: {e}")
        resp = b'{"status":"success"}'
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(resp))); self.end_headers()
        self.wfile.write(resp)

    def log_message(self, format, *args): pass

def start_log_receiver():
    global RECEIVER_OK
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        server = ThreadingHTTPServer(("0.0.0.0", LOG_PORT), LogReceiver)
        RECEIVER_OK = True
        local_ip = get_local_ip()
        print(f"{I.recv} 内置接收端已启动: http://{local_ip}:{LOG_PORT}/")
        server.serve_forever()
    except Exception as e: print(f"{I.warn} 接收端启动失败: {e}")

def udp_listener():
    global UDP_OK
    while True:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(('0.0.0.0', UDP_PORT))
            UDP_OK = True
            print(f"{I.ok} UDP 监听已启动...")
            while True:
                data, addr = sock.recvfrom(4096)
                msg = data.decode('utf-8', errors='ignore')
                if msg.startswith("FIND_ME_MSG:") or msg.startswith("FIND_ME_CHAT:"):
                    parts = msg.split(":", 3)
                    if len(parts) >= 3 and parts[1] == RESCUE_SECRET:
                        sender = parts[2]
                        content = parts[3] if len(parts) == 4 else ""
                        if sender in ("PC_Terminal", "Web_Console", "Guest_"): continue
                        dedup_key = (sender, content)
                        now_ts = time.time()
                        if now_ts - _LAST_UDP_MSG.get(dedup_key, 0) < 5.0: continue
                        _LAST_UDP_MSG[dedup_key] = now_ts
                        if len(_LAST_UDP_MSG) > 100:
                            for k in [k for k, v in _LAST_UDP_MSG.items() if v < now_ts - _LAST_UDP_MSG_TTL]: _LAST_UDP_MSG.pop(k, None)
                        print(f"\n{I.chat} [{sender}] {content}  (来自 {addr[0]})")
                        with _state_lock:
                            info = DISCOVERED_DEVICES.setdefault(sender, {})
                            info["source_ip"] = addr[0]
                            info["last_chat"] = time.time()
        except Exception:
            UDP_OK = False
            time.sleep(10)

# ===== ARP 查询 / 局域网扫描 =====
def _try_ip_neigh(prefix):
    ips = set()
    try:
        result = subprocess.run(["ip", "neigh"], capture_output=True, encoding="utf-8", errors="ignore", timeout=3)
        for line in result.stdout.split("\n"):
            for token in line.split():
                if token.startswith(prefix + ".") and is_valid_ip(token): ips.add(token)
    except Exception: pass
    return ips

def _try_arp_a(prefix):
    ips = set()
    try:
        result = subprocess.run(["arp", "-a"], capture_output=True, encoding="utf-8", errors="ignore", timeout=3)
        for match in re.finditer(r"(\d+\.\d+\.\d+\.\d+)", result.stdout):
            ip = match.group(1)
            if ip.startswith(prefix + ".") and is_valid_ip(ip): ips.add(ip)
    except Exception: pass
    return ips

def _try_proc_net_arp(prefix):
    ips = set()
    try:
        if not os.path.exists("/proc/net/arp"): return ips
        with open("/proc/net/arp", "r") as f:
            for line in f.readlines()[1:]:
                parts = line.split()
                if len(parts) >= 1 and parts[0].startswith(prefix + ".") and is_valid_ip(parts[0]): ips.add(parts[0])
    except Exception: pass
    return ips

def get_arp_ips(prefix):
    ips = set()
    ips.update(_try_ip_neigh(prefix))
    ips.update(_try_arp_a(prefix))
    ips.update(_try_proc_net_arp(prefix))
    return list(ips)

def _probe_ips(ips, local_ip):
    temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
    cmd = f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}"
    def probe(ip):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.sendto(cmd.encode(), (ip, UDP_PORT))
            s.close()
        except Exception: pass
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as ex:
        list(ex.map(probe, ips))

def scan_lan_devices():
    real_local_ips = [ip for ip in get_all_local_ips() if not ip.startswith("127.")] or ["127.0.0.1"]
    seen_prefix = set()
    targets = []
    for ip in real_local_ips:
        p = get_network_prefix(ip)
        if p not in seen_prefix:
            seen_prefix.add(p)
            targets.append((p, ip))
    print(f"\n{I.ping} 正在扫描局域网 ...")
    print(f"   本机 IP: {', '.join(real_local_ips)}")
    print(f"   网段: {', '.join(p + '.x' for p, _ in targets)}")
    scan_start = time.time()
    arp_hit = False
    for prefix, local_ip in targets:
        arp_ips = [ip for ip in get_arp_ips(prefix) if ip != local_ip]
        if not arp_ips: continue
        arp_hit = True
        print(f"   [{prefix}.x] ARP 表里找到 {len(arp_ips)} 个 IP，先探测...")
        _probe_ips(arp_ips, local_ip)
        time.sleep(3)
    if not arp_hit: print(f"{I.warn} ARP 表为空")
    with _state_lock:
        found = len([d for d in DISCOVERED_DEVICES.values() if d.get("last_seen", 0) >= scan_start])
    if found == 0:
        print(f"{I.warn} ARP 未命中，开始全网段扫描 ...")
        for prefix, local_ip in targets:
            all_ips = [f"{prefix}.{i}" for i in range(1, 255) if f"{prefix}.{i}" != local_ip]
            _probe_ips(all_ips, local_ip)
    print(f"{I.ping} 等待手机响应（10 秒）...")
    time.sleep(10)
    with _state_lock:
        new_devices = {dev_id: info for dev_id, info in DISCOVERED_DEVICES.items() if info.get("last_seen", 0) >= scan_start}
    if not new_devices:
        print(f"\n{I.err} 未发现任何运行中的手机")
        return
    print(f"\n{I.ok} 发现 {len(new_devices)} 台手机：")
    for dev_id, info in new_devices.items():
        ip = info.get("source_ip_lan") or info.get("source_ip") or info.get("ip", "?")
        print(f"  {dev_id:<28} {ip:<16} {info.get('battery', '?')}%")

def periodic_probe(target_ip):
    s = input("探测间隔秒数（回车默认 60）: ").strip()
    interval = int(s) if s.isdigit() and int(s) > 0 else 60
    s = input("总探测时长秒数（回车默认 600）: ").strip()
    duration = int(s) if s.isdigit() and int(s) > 0 else 600
    local_ip = get_local_ip()
    cmd = f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:http://{local_ip}:{LOG_PORT}/api/report"
    print(f"\n{I.ping} 每 {interval} 秒探测一次，持续 {duration} 秒...")
    end = time.time() + duration
    probe_count = respond_count = 0
    try:
        while time.time() < end:
            probe_count += 1
            before = _get_counter("report")
            print(f"\n--- 第 {probe_count} 次探测 ---")
            send_udp(cmd, target_ip, quiet=True)
            wait_until = time.time() + 10
            got = False
            while time.time() < wait_until:
                time.sleep(0.2)
                if _get_counter("report") > before: got = True; break
            if got: respond_count += 1; print(f"   {I.ok} 收到响应")
            else: print(f"   {I.warn} 未响应")
            remaining = end - time.time()
            if remaining > interval: time.sleep(interval)
            elif remaining > 0: time.sleep(remaining)
    except KeyboardInterrupt: print("\n探测已手动停止")
    print(f"\n{I.ok} 探测结果: {probe_count} 次发送，{respond_count} 次响应")

def pull_logs_multi(target_ips, temp_url, clear_after):
    if not target_ips or target_ips == ["255.255.255.255"]:
        print(f"{I.warn} 未选择有效目标或使用了广播，已取消操作")
        return
    cmd = f"FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}:{temp_url}" + (":clear" if clear_after else "")
    send_time = time.time()
    print(f"\n{I.send} 向 {len(target_ips)} 台设备发送拉日志指令...")
    for ip in target_ips:
        send_udp(cmd, ip, quiet=True, no_retry=True)
        time.sleep(0.1)
    print(f"{I.ping} 等待手机上传（最多 30 秒）...")     
    deadline = time.time() + 30                   
    received_ips = set()
    while time.time() < deadline:
        time.sleep(0.5)
        with _state_lock:
            for dev_id, info in DISCOVERED_DEVICES.items():
                src_ip = info.get("source_ip_lan") or info.get("source_ip")
                if src_ip in target_ips and src_ip not in received_ips and info.get("last_log", 0) >= send_time:
                    received_ips.add(src_ip)
                    print(f"   {I.ok} {src_ip} 已上传 ({dev_id})")
        if len(received_ips) >= len(target_ips): break
    print(f"\n{I.ping} 完成: {len(received_ips)}/{len(target_ips)} 台成功")
    missing = [ip for ip in target_ips if ip not in received_ips]
    if missing: print(f"{I.warn} 未响应: {', '.join(missing)}")

# ===== ADB 核心模块 =====
def get_adb_devices():
    try:
        result = subprocess.run(["adb", "devices"], capture_output=True, encoding="utf-8", errors="ignore", timeout=5)
        return [line.split("\t")[0] for line in result.stdout.strip().split("\n")[1:] if "\tdevice" in line]
    except Exception: return []

def choose_adb_device():
    devices = get_adb_devices()
    if not devices:
        print(f"{I.err} ADB 未连接或未授权！")
        return None
    if len(devices) == 1: return devices[0]
    print("\n多个 ADB 设备：")
    for i, dev in enumerate(devices, 1): print(f"  {i}. {dev}")
    choice = input("选择 (回车默认 1): ").strip()
    return devices[int(choice)-1] if choice.isdigit() and 0 < int(choice) <= len(devices) else devices[0]

def run_adb(device, shell_cmd, timeout=15):
    cmd = ["adb", "-s", device, "shell"] + shell_cmd
    try:
        if ":" in device:
            check = subprocess.run(["adb", "-s", device, "get-state"], capture_output=True, encoding="utf-8", errors="ignore", timeout=3)
            if "device" not in check.stdout:
                subprocess.run(["adb", "connect", device], capture_output=True, timeout=5)
                time.sleep(1)
        subprocess.run(cmd, check=True, capture_output=True, timeout=timeout)
        return True, ""
    except subprocess.CalledProcessError as e:
        return False, e.stderr.decode('utf-8', errors='ignore').strip() if e.stderr else "未知错误"
    except Exception as e: return False, str(e)

def get_apk_info(apk_path):
    for tool in ["aapt", "aapt2"]:
        try:
            res = subprocess.run([tool, "dump", "badging", apk_path], capture_output=True, encoding="utf-8", errors="ignore", timeout=5)
            if res.returncode == 0:
                for line in res.stdout.split('\n'):
                    if line.startswith("package:"):
                        match = re.search(r"name='([^']+)'", line)
                        if match: return match.group(1), f"({tool})"
        except Exception: pass
    try:
        with zipfile.ZipFile(apk_path, 'r') as z:
            data = z.read('AndroidManifest.xml')
            if PACKAGE_NAME.encode('utf-8') in data: return PACKAGE_NAME, "(特征匹配)"
            return "未知", "(zip匹配失败)"
    except Exception: return "读取失败", "异常"

def adb_collect_system_info(device):
    print(f"\n{I.doc} 设备环境深度采集... (请稍候，正在扫描底层硬件、网络与全部状态)")
    
    def get_prop(key):
        try: return subprocess.run(["adb", "-s", device, "shell", "getprop", key], capture_output=True, encoding="utf-8", errors="ignore", timeout=5).stdout.strip() or "未知"
        except: return "获取失败"

    def run_cmd(cmd):
        try: return subprocess.run(["adb", "-s", device, "shell"] + cmd, capture_output=True, encoding="utf-8", errors="ignore", timeout=8).stdout.strip()
        except: return ""

    def get_setting(namespace, key):
        val = run_cmd(["settings", "get", namespace, key])
        return val if val and val != "null" else "未知"

    try:
        brand = get_prop("ro.product.brand")
        model = get_prop("ro.product.model")
        android_ver = get_prop("ro.build.version.release")
        sdk_ver = get_prop("ro.build.version.sdk")
        patch = get_prop("ro.build.version.security_patch")
        cpu_abi = get_prop("ro.product.cpu.abi")
        
        mem_info = run_cmd(["cat", "/proc/meminfo"])
        mem_total, mem_avail = "未知", "未知"
        for line in mem_info.split("\n"):
            if "MemTotal" in line: mem_total = f"{int(line.split()[1]) // 1024} MB"
            if "MemAvailable" in line: mem_avail = f"{int(line.split()[1]) // 1024} MB"
            
        df_info = run_cmd(["df", "-h", "/data"]).split("\n")
        storage = "未知"
        if len(df_info) > 1:
            parts = df_info[1].split()
            if len(parts) >= 4: storage = f"{parts[2]} 已用 / {parts[1]} 总量 (剩 {parts[3]})"

        bat_info = run_cmd(["dumpsys", "battery"])
        bat_level, bat_temp, bat_status = "未知", "未知", "未知"
        for line in bat_info.split("\n"):
            if "level:" in line: 
                lvl = line.split(":")[1].strip()
                bat_level = f"{lvl}%" if lvl != "-1" else "读取异常(-1)"
            if "temperature:" in line: bat_temp = f"{int(line.split(':')[1].strip()) / 10.0} °C"
            if "status:" in line: bat_status = line.split(":")[1].strip()

        screen_size = run_cmd(["wm", "size"]).replace("Physical size: ", "")
        screen_state = "亮屏" if "mWakefulness=Awake" in run_cmd(["dumpsys", "power"]) else "息屏"
        lock_state = "已锁屏" if ("mDreamingLockscreen=true" in run_cmd(["dumpsys", "window"]) or "isStatusBarKeyguard=true" in run_cmd(["dumpsys", "window"])) else "未锁屏"

        airplane = get_setting("global", "airplane_mode_on")
        air_str = "开启" if airplane == "1" else "关闭" if airplane == "0" else "未知"
        mobile_data = get_setting("global", "mobile_data")
        mob_str = "开启" if mobile_data == "1" else "关闭" if mobile_data == "0" else "未知"
        bluetooth = get_setting("global", "bluetooth_on")
        bt_str = "开启" if bluetooth == "1" else "关闭" if bluetooth == "0" else "未知"
        nfc = get_setting("global", "nfc_on")
        nfc_str = "开启" if nfc == "1" else "关闭" if nfc == "0" else "未知"
        gps_mode = get_setting("secure", "location_mode")
        gps_str = {"0": "全关", "1": "仅GPS", "2": "仅网络", "3": "高精度"}.get(gps_mode, "未知")
        dnd_mode = get_setting("global", "zen_mode")
        dnd_str = "关闭" if dnd_mode == "0" else "已开启" if dnd_mode in ("1", "2", "3") else "未知"
        alarm_vol = get_setting("system", "volume_alarm")
        alarm_str = f"{alarm_vol}/15" if alarm_vol != "未知" else "未知"

        ip_addr = "无"
        ip_info = run_cmd(["ip", "addr", "show", "wlan0"])
        for line in ip_info.split("\n"):
            if "inet " in line: ip_addr = line.split()[1].split("/")[0]; break
            
        wifi_ssid = "未连接"
        wifi_rssi = ""
        wifi_status = run_cmd(["cmd", "wifi", "status"])
        if "connected to" in wifi_status:
            match = re.search(r'connected to "([^"]+)"', wifi_status)
            if match: wifi_ssid = match.group(1)
            match_rssi = re.search(r'RSSI:\s*(-\d+)', wifi_status)
            if match_rssi: wifi_rssi = match_rssi.group(1) + " dBm"
        else:
            wifi_info = run_cmd(["dumpsys", "wifi"])
            for line in wifi_info.split("\n"):
                if "mWifiInfo" in line and "SSID:" in line:
                    match = re.search(r'SSID:\s*"?([^",]+)"?', line)
                    if match and match.group(1).strip() != "<unknown ssid>":
                        wifi_ssid = match.group(1).strip()
                        match_rssi = re.search(r'RSSI:\s*(-\d+)', line)
                        if match_rssi: wifi_rssi = match_rssi.group(1) + " dBm"
                        break

        pkg_out = run_cmd(["dumpsys", "package", PACKAGE_NAME])
        ver, stopped, signature = "未安装", "未知", "未知"
        for i, line in enumerate(pkg_out.split("\n")):
            if "versionName=" in line: ver = line.strip().split("versionName=")[1].split(" ")[0]
            if "stopped=" in line: stopped = line.strip().split("stopped=")[1].split(" ")[0]
            if "signatures=" in line: signature = line.split("signatures=")[1].strip()
            
        apk_paths = [l.split(":")[1] for l in run_cmd(["pm", "path", PACKAGE_NAME]).split("\n") if l.startswith("package:")]
        app_size = "未知"
        if apk_paths:
            du_res = run_cmd(["ls", "-lh", apk_paths[0]]).split()
            if len(du_res) >= 5: app_size = du_res[4] + " (仅APK)"

        sensors_out = run_cmd(["dumpsys", "sensorservice"])
        sensor_list = []
        for line in sensors_out.split("\n"):
            line = line.strip()
            if any(k in line.upper() for k in ["ACCELEROMETER", "GYROSCOPE", "LIGHT", "PROXIMITY", "PRESSURE", "STEP_COUNTER", "SIGNIFICANT_MOTION"]):
                if "|" not in line and "handle" not in line and len(line) < 100 and "pid=" not in line:
                    sensor_list.append(line)
        sensor_list = list(set(sensor_list))

        tele_out = run_cmd(["dumpsys", "telephony.registry"])
        cell_lines = [l.strip() for l in tele_out.split("\n") if "CellIdentity" in l or "cid=" in l or "lac=" in l]
        
        loc_out = run_cmd(["dumpsys", "location"])
        last_loc = []
        capture = False
        for line in loc_out.split("\n"):
            if "last location" in line.lower() or "last known" in line.lower(): capture = True
            if capture:
                last_loc.append(line.strip())
                if len(last_loc) >= 4: break

        print("\n" + "=" * 55)
        print("【 硬件与系统 】")
        print(f"  品牌型号 : {brand} {model}")
        print(f"  安卓版本 : Android {android_ver} (API {sdk_ver}) | 补丁: {patch}")
        print(f"  CPU 架构 : {cpu_abi}")
        
        print("\n【 存储与内存 】")
        print(f"  运行内存 : 可用 {mem_avail} / 总计 {mem_total}")
        print(f"  存储空间 : {storage}")

        print("\n【 电池、屏幕与系统开关 】")
        print(f"  电池状态 : {bat_level} | {bat_temp} | 状态码:{bat_status}")
        print(f"  屏幕状态 : {screen_state} | {lock_state} | 分辨率: {screen_size}")
        print(f"  勿扰模式 : {dnd_str}")
        print(f"  闹钟音量 : {alarm_str}")
        print(f"  飞行模式 : {air_str} | 移动数据: {mob_str}")
        print(f"  蓝牙/NFC : 蓝牙 {bt_str} | NFC {nfc_str}")

        print("\n【 网络与定位 】")
        print(f"  WiFi状态 : SSID: {wifi_ssid} {wifi_rssi}")
        print(f"  WiFi IP  : {ip_addr}")
        print(f"  GPS 开关 : {gps_str}")

        print("\n【 App 运行状态 】")
        print(f"  版本号   : {ver}")
        print(f"  冻结状态 : {'已被冻结 (stopped=true)' if stopped == 'true' else '正常运行' if stopped == 'false' else '未知'}")
        print(f"  占用空间 : {app_size}")
        print(f"  应用签名 : {signature}")

        print("\n【 硬件传感器 (手环预演) 】")
        if sensor_list:
            for s in sensor_list[:10]: print(f"  {I.ok} {s}")
        else: print(f"  {I.err} 未发现核心传感器")

        print("\n【 基站与定位缓存 (降级预演) 】")
        if cell_lines:
            for c in cell_lines[:2]: print(f"  {I.send} {c[:80]}...")
        else: print(f"  {I.err} 无法获取基站")
            
        if last_loc:
            for l in last_loc[:2]: print(f"  {I.loc} {l[:80]}...")
        else: print(f"  {I.err} 无底层定位缓存")
            
        print("=" * 55)
        
    except Exception as e:
        print(f"{I.err} 采集失败: {e}")

def adb_check_permissions(device):
    print(f"\n{I.doc} 检查 {device} 权限状态...")
    try:
        subprocess.run(["adb", "connect", device], capture_output=True, timeout=5)
        time.sleep(0.5)
        pkg_res = subprocess.run(["adb", "-s", device, "shell", "dumpsys", "package", PACKAGE_NAME], stdout=subprocess.PIPE, encoding="utf-8", errors="ignore", timeout=10)
        pkg_out = pkg_res.stdout or ""
        if not pkg_out.strip():
            print(f"{I.err} 读取 Package 信息为空！")
            return
        if "stopped=true" in pkg_out:
            print(f"{I.warn} App 处于冻结状态！请先按 7 启动。")
            print("-" * 50)
    except Exception as e:
        print(f"{I.err} 获取状态失败: {e}")
        return

    runtime_perms = {
        "精确定位": "ACCESS_FINE_LOCATION", "粗略定位": "ACCESS_COARSE_LOCATION",
        "后台定位": "ACCESS_BACKGROUND_LOCATION", "通知权限": "POST_NOTIFICATIONS",
        "读取电话": "READ_PHONE_STATE", "接收短信": "RECEIVE_SMS", "发送短信": "SEND_SMS",
        "活动识别": "ACTIVITY_RECOGNITION", "周围WiFi": "NEARBY_WIFI_DEVICES",
        "读存储(旧)": "READ_EXTERNAL_STORAGE", "写存储(旧)": "WRITE_EXTERNAL_STORAGE",
        "修改安全": "WRITE_SECURE_SETTINGS"
    }
    app_ops = {
        "悬浮窗权限": "SYSTEM_ALERT_WINDOW", "前台豁免": "START_FOREGROUND",
        "后台运行": "RUN_IN_BACKGROUND", "瞬间前台": "INSTANT_APP_START_FOREGROUND",
        "所有文件": "MANAGE_EXTERNAL_STORAGE"
    }

    print("-" * 50)
    granted_section = False
    for line in pkg_out.split("\n"):
        if "grantedPermissions:" in line: granted_section = True
        elif "requested permissions:" in line or "install permissions:" in line or "runtime permissions:" in line: granted_section = False
        if granted_section:
            for name, pkg_perm in list(runtime_perms.items()):
                if pkg_perm in line and pkg_perm in runtime_perms.values():
                    print(f"  {I.ok} {name:<10} : 已授权")
                    del runtime_perms[pkg_perm]
                    
    for name, pkg_perm in runtime_perms.items():
        print(f"  {I.err} {name:<10} : 未授权")

    for name, op in app_ops.items():
        res = subprocess.run(["adb", "-s", device, "shell", "cmd", "appops", "get", PACKAGE_NAME, op], stdout=subprocess.PIPE, encoding="utf-8", errors="ignore", timeout=5)
        out_str = (res.stdout or "").lower()
        found = "allow" in out_str or "allowed" in out_str
        print(f"  {I.ok if found else I.err} {name:<10} : {'已授权' if found else '未授权'}")

    doze_res = subprocess.run(["adb", "-s", device, "shell", "dumpsys", "deviceidle", "whitelist"], stdout=subprocess.PIPE, encoding="utf-8", errors="ignore", timeout=5)
    doze_ok = PACKAGE_NAME in (doze_res.stdout or "")
    print(f"  {I.ok if doze_ok else I.err} {'电池白名单':<10} : {'已加入' if doze_ok else '未加入'}")

    acc_res = subprocess.run(["adb", "-s", device, "shell", "settings", "get", "secure", "enabled_accessibility_services"], stdout=subprocess.PIPE, encoding="utf-8", errors="ignore", timeout=5)
    acc_ok = PACKAGE_NAME in (acc_res.stdout or "")
    print(f"  {I.ok if acc_ok else I.err} {'无障碍服务':<10} : {'已开启' if acc_ok else '未开启'}")

    dpm_res = subprocess.run(["adb", "-s", device, "shell", "dumpsys", "device_policy"], stdout=subprocess.PIPE, encoding="utf-8", errors="ignore", timeout=5)
    dpm_ok = PACKAGE_NAME in (dpm_res.stdout or "")
    print(f"  {I.ok if dpm_ok else I.err} {'防卸载装甲':<10} : {'已激活' if dpm_ok else '未激活'}")

    print("-" * 50)
    print(f"{I.ping} 提示：如果有未授权项，请直接按 E -> 1 给这台设备一键注入权限！")

def download_and_install_apk(device):
    url = input("\n下载链接 (URL): ").strip()
    if not url: return
    token = input("GitHub Token (回车跳过): ").strip() if "github" in url else ""
    filename = url.split("/")[-1].split("?")[0] or "downloaded_file"
    local_path = os.path.abspath(filename)
    headers = {"User-Agent": "Mozilla/5.0"}
    if token: headers["Authorization"] = f"Bearer {token}"
    print(f"{I.ping} 下载至: {local_path}")
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=60) as response, open(local_path, 'wb') as out_file: shutil.copyfileobj(response, out_file)
        print(f"{I.ok} 下载完成。")
    except Exception as e:
        print(f"{I.err} 下载失败: {e}")
        return
    apk_to_install = None
    if filename.lower().endswith(".zip"):
        print(f"{I.ping} 解压 ZIP 寻找 APK...")
        extract_dir = tempfile.mkdtemp(prefix="apk_extract_")
        try:
            with zipfile.ZipFile(local_path, 'r') as zip_ref: zip_ref.extractall(extract_dir)
            apk_files = [os.path.join(root, file) for root, dirs, files in os.walk(extract_dir) for file in files if file.lower().endswith(".apk")]
            if apk_files: apk_to_install = sorted(apk_files, key=os.path.getsize, reverse=True)[0]
        except Exception: pass
    elif filename.lower().endswith(".apk"): apk_to_install = local_path
    if apk_to_install:
        print(f"{I.ping} 安装至 {device} ...")
        try:
            subprocess.run(["adb", "-s", device, "install", "-r", "-g", apk_to_install], check=True, capture_output=True, timeout=60)
            print(f"{I.ok} 安装成功！")
            subprocess.run(["adb", "-s", device, "shell", "am", "start", "-n", f"{PACKAGE_NAME}/.MainActivity"], capture_output=True)
        except Exception as e: print(f"{I.err} 安装失败: {e}")

def menu_e_adb_manager():
    while True:
        print("\n" + "=" * 50)
        print("  E. ADB 设备管理控制台")
        print("=" * 50)
        print("  [1] 一键注入保活与隐藏权限")
        print("  [2] 安装/覆盖安装 APK")
        print("  [3] 强制停止 (Force-Stop)")
        print("  [4] 清除 App 数据")
        print("  [5] 截屏")
        print("  [6] 录屏 10 秒")
        print("  [7] 启动 App 主界面")
        print("  [8] 发送 ACTION 调试指令")
        print("  [9] 模拟系统环境 (断网/恢复/开机广播)")
        print("  [10] 抓取最近 50 行运行日志")
        print("  [11] 设备环境深度体检")
        print("  [12] ADB 强制拉取全量日志")
        print("  [13] 在线下载并安装 APK")
        print("  [14] 检查全量关键权限状态")
        print("  [0] 返回上一级")
        print("=" * 50)
        c = input("请输入编号 (0-14): ").strip()
        if c == '0': return
        if c == '1':
            device = choose_adb_device()
            if not device: continue
            print(f"\n{I.term} 注入权限中...")
            cmds = [["cmd", "appops", "set", PACKAGE_NAME, "START_FOREGROUND", "allow"], ["cmd", "appops", "set", PACKAGE_NAME, "RUN_IN_BACKGROUND", "allow"], ["cmd", "appops", "set", PACKAGE_NAME, "INSTANT_APP_START_FOREGROUND", "allow"], ["pm", "grant", PACKAGE_NAME, "android.permission.WRITE_SECURE_SETTINGS"], ["dumpsys", "deviceidle", "whitelist", f"+{PACKAGE_NAME}"], ["cmd", "appops", "write-settings"]]
            for cmd in cmds:
                if subprocess.run(["adb", "-s", device, "shell"] + cmd, capture_output=True).returncode == 0: print(f"  {I.ok} 成功: {cmd[-2]} {cmd[-1]}")
            print(f"\n{I.ok} 完成。")

        elif c == '2':
            device = choose_adb_device()
            if not device: continue
            apks = [f for f in os.listdir('.') if f.lower().endswith('.apk')]
            if not apks: print(f"{I.err} 未找到 APK。"); continue
            apks.sort(key=lambda x: os.path.getmtime(x), reverse=True)
            print("\n找到以下 APK：")
            apk_infos = []
            for i, apk in enumerate(apks, 1):
                pkg_name, tool = get_apk_info(apk)
                apk_infos.append((apk, pkg_name))
                tag = f"{I.ok} 包名匹配" if pkg_name == PACKAGE_NAME else (f"{I.warn} 未知包名(放行)" if pkg_name in ("未知", "读取失败") else f"{I.err} 包名不符({pkg_name})")
                print(f"  {i}. {apk} ({os.path.getsize(apk)/1024/1024:.1f}MB)  {tag}")
            idx_str = input("\n选择 (默认1): ").strip()
            idx = int(idx_str) - 1 if idx_str.isdigit() and 0 < int(idx_str) <= len(apks) else 0
            apk_path = os.path.abspath(apks[idx])
            selected_pkg = apk_infos[idx][1]
            if selected_pkg not in (PACKAGE_NAME, "未知", "读取失败"):
                if input(f"\n{I.warn} 警告：包名不符！继续安装？(y/N): ").lower() != 'y': continue

            print(f"{I.ping} 正在安装 {apks[idx]} ...")
            try:
                subprocess.run(["adb", "-s", device, "install", "-r", "-g", "-d", apk_path], check=True, capture_output=True, timeout=60)
                print(f"{I.ok} 安装成功！")
                print(f"{I.term} 静默授权...")
                for cmd in [["pm", "grant", PACKAGE_NAME, "android.permission.ACCESS_FINE_LOCATION"], ["pm", "grant", PACKAGE_NAME, "android.permission.ACCESS_COARSE_LOCATION"], ["pm", "grant", PACKAGE_NAME, "android.permission.RECEIVE_SMS"], ["cmd", "appops", "set", PACKAGE_NAME, "SYSTEM_ALERT_WINDOW", "allow"], ["dumpsys", "deviceidle", "whitelist", f"+{PACKAGE_NAME}"]]:
                    subprocess.run(["adb", "-s", device, "shell"] + cmd, capture_output=True)
                print(f"{I.ok} 核心权限已授予。")
                print(f"{I.term} 拉起 App...")
                subprocess.run(["adb", "-s", device, "shell", "am", "start", "-n", f"{PACKAGE_NAME}/.MainActivity"], capture_output=True)
                print(f"{I.ping} 请在手机上完成：后台定位/无障碍授权。")
            except subprocess.CalledProcessError as e:
                out_err = (e.stdout or b"").decode('utf-8', errors='ignore') + "\n" + (e.stderr or b"").decode('utf-8', errors='ignore')
                if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out_err:
                    print(f"\n{I.err} 安装失败：签名冲突！")
                    if input("是否卸载旧版重装？(y/N): ").lower() == 'y':
                        print(f"{I.warn} 正在尝试卸载旧版本...")
                        res = subprocess.run(["adb", "-s", device, "uninstall", PACKAGE_NAME], capture_output=True, encoding="utf-8", errors="ignore")
                        uninstall_msg = (res.stdout or "") + (res.stderr or "")
                        if "Success" not in uninstall_msg:
                            if "DEVICE_POLICY_MANAGER" in uninstall_msg:
                                print(f"  {I.warn} 检测到防卸载装甲！尝试 Shell 级特权强卸...")
                                pm_un_res = subprocess.run(["adb", "-s", device, "shell", "pm", "uninstall", PACKAGE_NAME], capture_output=True, encoding="utf-8", errors="ignore")
                                pm_un_msg = (pm_un_res.stdout or "") + (pm_un_res.stderr or "")
                                if "Success" not in pm_un_msg:
                                    print(f"  {I.warn} Shell 强卸失败，尝试冰冻封印...")
                                    freeze_res = subprocess.run(["adb", "-s", device, "shell", "pm", "disable-user", "--user", "0", PACKAGE_NAME], capture_output=True, encoding="utf-8", errors="ignore")
                                    if "disabled-user" in (freeze_res.stdout or "") or "new state: disabled" in (freeze_res.stdout or ""):
                                        print(f"  {I.ok} 应用已成功冰冻失效！可以直接尝试覆盖安装了。")
                                    else: print(f"  {I.err} 冰冻失败。")
                                else: print(f"  {I.ok} 通过 Shell 级 pm uninstall 成功卸载！")
                        else: print(f"  {I.ok} 旧版本已成功卸载。")

                        print(f"{I.ping} 正在重新安装 (使用 -t -g --no-streaming)...")
                        try:
                            subprocess.run(["adb", "-s", device, "install", "-r", "-t", "-g", "--no-streaming", apk_path], check=True, capture_output=True, timeout=60)
                            print(f"{I.ok} 重装成功！")
                        except Exception as ex: print(f"{I.err} 重装失败: {ex}")
                else: print(f"{I.err} 安装失败: {out_err}")

        elif c == '3':
            device = choose_adb_device()
            if device and input(f"{I.warn} 强杀 App？(y/N): ").lower() == 'y': subprocess.run(["adb", "-s", device, "shell", "am", "force-stop", PACKAGE_NAME])
        elif c == '4':
            device = choose_adb_device()
            if device and input(f"{I.warn} 清空数据？(y/N): ").lower() == 'y': subprocess.run(["adb", "-s", device, "shell", "pm", "clear", PACKAGE_NAME])
        elif c in ['5', '6']:
            device = choose_adb_device()
            if not device: continue
            os.makedirs("screenshots", exist_ok=True)
            ts = time.strftime("%Y%m%d_%H%M%S")
            if c == '5':
                remote, local = "/sdcard/tmp_screen.png", os.path.abspath(f"screenshots/screen_{ts}.png")
                print(f"{I.ping} 截屏中...")
                subprocess.run(["adb", "-s", device, "shell", "screencap", "-p", remote])
                subprocess.run(["adb", "-s", device, "pull", remote, local], capture_output=True)
                subprocess.run(["adb", "-s", device, "shell", "rm", remote])
                print(f"{I.ok} 保存至: {local}")
            else:
                remote, local = "/sdcard/tmp_screen.mp4", os.path.abspath(f"screenshots/screen_{ts}.mp4")
                print(f"{I.ping} 录屏 10 秒...")
                subprocess.run(["adb", "-s", device, "shell", "screenrecord", "--time-limit", "10", remote])
                subprocess.run(["adb", "-s", device, "pull", remote, local], capture_output=True)
                subprocess.run(["adb", "-s", device, "shell", "rm", remote])
                print(f"{I.ok} 保存至: {local}")
        elif c == '7':
            device = choose_adb_device()
            if device:
                subprocess.run(["adb", "-s", device, "shell", "am", "start", "-n", f"{PACKAGE_NAME}/.MainActivity"], capture_output=True)
                print(f"{I.ok} 已拉起主界面。")
        elif c == '8':
            device = choose_adb_device()
            if not device: continue
            print("\n--- ACTION 调试指令 ---")
            print("  1. 强制上报\n  2. 定位+上报\n  3. 扫描 WiFi\n  4. 拉日志上传\n  5. 播放报警\n  8. 改上报间隔为 1 分钟")
            ac = input("请选择: ").strip()
            action_map = {'1': ["am", "startservice", "-n", f"{PACKAGE_NAME}/.TrackingService", "-a", "ACTION_FORCE_REPORT"], '2': ["am", "startservice", "-n", f"{PACKAGE_NAME}/.TrackingService", "-a", "ACTION_LOCATE_ONCE"], '3': ["am", "startservice", "-n", f"{PACKAGE_NAME}/.TrackingService", "-a", "ACTION_SCAN_WIFI"], '4': ["am", "startservice", "-n", f"{PACKAGE_NAME}/.TrackingService", "-a", "ACTION_UPLOAD_LOG"], '5': ["am", "startservice", "-n", f"{PACKAGE_NAME}/.TrackingService", "-a", "ACTION_PLAY_ALARM"], '8': ["am", "startservice", "-n", f"{PACKAGE_NAME}/.TrackingService", "-a", "ACTION_UPDATE_RUNTIME_CONFIG", "--es", "report_interval_seconds", "60"]}
            if ac in action_map:
                subprocess.run(["adb", "-s", device, "shell"] + action_map[ac], capture_output=True)
                print(f"{I.ok} 指令已发送。")
        elif c == '9':
            device = choose_adb_device()
            if not device: continue
            print("\n--- 模拟系统环境 ---")
            print("  1. 断网\n  2. 恢复网络\n  3. 模拟开机广播")
            ec = input("请选择: ").strip()
            if ec == '1': subprocess.run(["adb", "-s", device, "shell", "svc", "wifi", "disable"]); subprocess.run(["adb", "-s", device, "shell", "svc", "data", "disable"])
            elif ec == '2': subprocess.run(["adb", "-s", device, "shell", "svc", "wifi", "enable"]); subprocess.run(["adb", "-s", device, "shell", "svc", "data", "enable"])
            elif ec == '3': subprocess.run(["adb", "-s", device, "shell", "am", "broadcast", "-a", "android.intent.action.BOOT_COMPLETED", "-p", PACKAGE_NAME])
            print(f"{I.ok} 完成。")
        elif c == '10':
            device = choose_adb_device()
            if not device: continue
            print(f"{I.ping} 抓取最后 50 行日志...")
            res = subprocess.run(["adb", "-s", device, "shell", "tail", "-n", "50", f"/sdcard/Android/data/{PACKAGE_NAME}/files/device_log.txt"], stdout=subprocess.PIPE, encoding="utf-8", errors="ignore")
            print(res.stdout if res.stdout else f"{I.err} 抓取失败或文件为空。")
        elif c == '11':
            device = choose_adb_device()
            if device: adb_collect_system_info(device)
        elif c == '12':
            device = choose_adb_device()
            if not device: continue
            print(f"{I.ping} 强制拉取全量日志...")
            remote = f"/sdcard/Android/data/{PACKAGE_NAME}/files/device_log.txt"
            os.makedirs("received_logs", exist_ok=True)
            local = os.path.abspath(f"received_logs/adb_pull_{time.strftime('%Y%m%d_%H%M%S')}.log")
            res = subprocess.run(["adb", "-s", device, "exec-out", "cat", remote], stdout=subprocess.PIPE, timeout=30)
            if res.returncode == 0 and res.stdout:
                with open(local, "wb") as f: f.write(res.stdout)
                print(f"{I.ok} 拉取成功！大小: {len(res.stdout)/1024:.1f}KB -> {local}")
            else: print(f"{I.err} 拉取失败，文件不存在或无权限。")
        elif c == '13':
            device = choose_adb_device()
            if device: download_and_install_apk(device)
        elif c == '14':
            device = choose_adb_device()
            if device: adb_check_permissions(device)

# ===== 其他二级菜单 =====
def menu_a_server_config():
    while True:
        print("\n" + "=" * 50 + "\n  A. 服务器与配置\n" + "=" * 50)
        print("  [1] 换主服务器\n  [2] 换配置中心\n  [3] 改上报间隔（给出 ADB 命令）\n  [4] 临时服务器上报\n  [0] 返回")
        c = input("请选择 (0-4): ").strip()
        if c == '0': return
        if c == '1':
            url = input(f"新主服务器 URL (回车默认 {DEFAULT_SERVER_URL}): ").strip() or DEFAULT_SERVER_URL
            if is_valid_url(url): send_udp(f"FIND_ME_SERVER:{RESCUE_SECRET}:{url}", choose_targets_multi())
        elif c == '2':
            url = input(f"新配置中心 URL (回车默认 {DEFAULT_CONFIG_URL}): ").strip() or DEFAULT_CONFIG_URL
            if is_valid_url(url): send_udp(f"FIND_ME_CONFIG:{RESCUE_SECRET}:{url}", choose_targets_multi())
        elif c == '3':
            sec = input("上报间隔秒数（默认 1800）: ").strip()
            if sec.isdigit(): print(f"\n{I.warn} 需 ADB 执行：\nadb shell am startservice -n {PACKAGE_NAME}/.TrackingService -a ACTION_UPDATE_RUNTIME_CONFIG --es report_interval_seconds {sec}")
        elif c == '4':
            url = input(f"临时 URL (回车默认 {DEFAULT_SERVER_URL}): ").strip() or DEFAULT_SERVER_URL
            if is_valid_url(url): send_udp(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{url}", choose_targets_multi())

def menu_b_cloud_switch():
    while True:
        print("\n" + "=" * 50 + "\n  B. 云端与开关\n" + "=" * 50)
        print("  [1] 开启云端日志\n  [2] 关闭云端日志\n  [3] 锁定聊天\n  [4] 解锁聊天\n  [0] 返回")
        c = input("请选择 (0-4): ").strip()
        if c == '0': return
        if c == '1': send_udp(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:1", choose_targets_multi())
        elif c == '2': send_udp(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:0", choose_targets_multi())
        elif c == '3': send_udp(f"FIND_ME_CHAT_LOCK:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '4': send_udp(f"FIND_ME_CHAT_UNLOCK:{RESCUE_SECRET}", choose_targets_multi())

def menu_c_message_cleanup(local_ip):
    while True:
        print("\n" + "=" * 50 + "\n  C. 消息与清理\n" + "=" * 50)
        print("  [1] 悬浮文字（需手机亮屏）\n  [2] 清空留言\n  [3] 设置紧急号码\n  [4] 拉日志（自定义 URL）\n  [0] 返回")
        c = input("请选择 (0-4): ").strip()
        if c == '0': return
        if c == '1':
            msg = input("悬浮文字内容: ").strip()
            if msg:
                send_udp(f"FIND_ME_FLOAT:{RESCUE_SECRET}:{msg}", choose_targets_multi())
                if ":" not in msg: send_udp(f"FIND_ME_MSG:{RESCUE_SECRET}:{msg}", choose_targets_multi())
        elif c == '2': send_udp(f"FIND_ME_CLEAR_HISTORY:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '3':
            num = input("报警短信手机号: ").strip()
            if num: send_udp(f"FIND_ME_SET_SMS_NUM:{RESCUE_SECRET}:{num}", choose_targets_multi())
        elif c == '4':
            url = input(f"接收 URL (回车默认 http://{local_ip}:{LOG_PORT}/api/report): ").strip() or f"http://{local_ip}:{LOG_PORT}/api/report"
            if is_valid_url(url):
                targets = choose_targets_multi()
                if targets:
                    clear = input("上传后清空手机本地日志？(y/N): ").strip().lower() == 'y'
                    pull_logs_multi(targets, url, clear)

def menu_d_diagnostic():
    while True:
        print("\n" + "=" * 50 + "\n  D. 诊断工具\n" + "=" * 50)
        print("  [1] 扫描 WiFi\n  [2] 重启 UDP\n  [3] 模拟低电量\n  [4] 定时探测\n  [5] 扫描局域网\n  [0] 返回")
        c = input("请选择 (0-5): ").strip()
        if c == '0': return
        if c == '1': send_udp(f"FIND_ME_SCAN_WIFI:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '2': send_udp(f"FIND_ME_RESTART_UDP:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '3': send_udp(f"FIND_ME_TEST_LOW_BATTERY:{RESCUE_SECRET}", choose_targets_multi())
        elif c == '4': periodic_probe(choose_targets_multi())
        elif c == '5': scan_lan_devices()

def menu_f_todo():
    print("\n" + "=" * 50 + "\n  未来功能规划 (TODO)\n" + "=" * 50)
    print("  [已完成] H1b 串行化离线补发\n  [已完成] ADB 一键保活与设备控制\n  [计划中] FMD 定位降级\n  [计划中] Cell 基站双源反查\n  [计划中] Veto 紧急宏指令\n  [计划中] Traccar 运动状态机\n  [计划中] HealthMonitor 手环化\n" + "=" * 50)
    input("按回车返回...")

# ===== 主菜单 =====
def show_devices():
    with _state_lock: devices = dict(DISCOVERED_DEVICES)
    if not devices: print("\n暂无已发现设备"); return
    now = time.time()
    print(f"\n已发现设备（{len(devices)} 台）：")
    print(f"{'设备ID':<28} {'IP':<16} {'电量':<6} {'WiFi':<20} {'最后活跃':<10}")
    print("-" * 90)
    for dev_id, info in devices.items():
        last = max(info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0))
        ago = max(0, int(now - last))
        ago_str = (f"{ago}秒前" if ago < 60 else f"{ago // 60}分钟前" if ago < 3600 else f"{ago // 3600}小时前" if ago < 86400 else "很久前") if last > 0 else "从未"
        ip = info.get("source_ip_lan") or info.get("source_ip") or info.get("ip", "?")
        batt = f"{info.get('battery', '?')}%"
        wifi = str(info.get("wifi", "?"))[:18]
        tag = "" if info.get("last_seen", 0) > 0 else " [仅UDP]"
        _bv = str(info.get("build_version", "旧版"))[:20]
        print(f"{dev_id:<28} {ip:<16}{tag:<10} {batt:<8} {wifi:<20} {ago_str:<10} {_bv}")
    print()

def status_line():
    with _state_lock:
        dev_count = len(DISCOVERED_DEVICES)
        latest = max([max(info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0)) for info in DISCOVERED_DEVICES.values()] or [0])
    ago_str = "无"
    if latest > 0:
        ago = max(0, int(time.time() - latest))
        ago_str = f"{ago}秒前" if ago < 60 else f"{ago // 60}分钟前" if ago < 3600 else f"{ago // 3600}小时前"
    
    recv_str = I.ok if RECEIVER_OK else I.err
    udp_str = I.ok if UDP_OK else I.err
    
    return f"{I.ping} 设备 {dev_count} 台 | 接收端 {LOG_PORT} {recv_str} | UDP {udp_str} | 最近活动 {ago_str}"

def print_main_menu(local_ip):
    print("\n" + "=" * 50)
    print(f"  {I.term} DeviceFinder 救援终端 (Ultimate)")
    print(f"  环境: {env_name()}  |  IP: {local_ip}")
    print("=" * 50)
    
    print("\n【 高频操作 】")
    print("  [1] 强制上报        [2] 定位+上报")
    print("  [3] 拉取日志        [4] 查看设备")
    print("  [5] 播放警报        [6] 对讲机")
    
    print("\n【 系统级保活与紧急指令 】")
    print("  [7] 注入 ADB 保活   [8] 紧急寻找")
    
    print("\n【 设备管理与规划 】")
    print("  [E] ADB 控制台      [T] 未来规划 (TODO)")
    
    print("\n【 更多分类 】")
    if is_termux():
        print("  [A] 服务器/配置     [B] 云端与开关")
        print("  [C] 消息与清理      [D] 诊断工具")
    else:
        print("  [A] 服务器与配置    -> 换 URL / 改间隔 / 临时上报")
        print("  [B] 云端与开关      -> 云端日志 / 聊天锁")
        print("  [C] 消息与清理      -> 悬浮 / 留言 / 短信 / 自定义拉日志")
        print("  [D] 诊断工具        -> 扫描 / 探测 / 重启")
        
    print("\n  [X] 扫描局域网      [0] 退出")
    print("=" * 50)
    print(status_line())
    print("=" * 50)

def send_emergency():
    print(f"\n{I.warn} 即将发送紧急宏指令！")
    targets = choose_targets_multi("请选择紧急呼叫目标: ")
    if targets:
        send_udp(f"FIND_ME_EMERGENCY:{RESCUE_SECRET}", targets)
        print(f"{I.ok} 紧急指令已下发。")

def main():
    local_ip = get_local_ip()
    while True:
        print_main_menu(local_ip)
        choice = input("请输入 (0-8, A-E, T, X): ").strip().upper().rstrip(".、,，")
        if choice == '0': print("退出。"); break
        elif choice == 'X': scan_lan_devices()
        elif choice == 'A': menu_a_server_config()
        elif choice == 'B': menu_b_cloud_switch()
        elif choice == 'C': menu_c_message_cleanup(local_ip)
        elif choice == 'D': menu_d_diagnostic()
        elif choice == 'E': menu_e_adb_manager()
        elif choice == 'T': menu_f_todo()
        elif choice == '1':
            send_udp_and_wait(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:http://{local_ip}:{LOG_PORT}/api/report", choose_targets_multi(), 15)
        elif choice == '2':
            target = choose_targets_multi()
            send_udp(f"FIND_ME_LOCATE_ONCE:{RESCUE_SECRET}", target)
            time.sleep(5)
            send_udp_and_wait(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:http://{local_ip}:{LOG_PORT}/api/report", target, 15)
        elif choice == '3':
            targets = choose_targets_multi()
            if targets:
                clear = input("上传后清空手机本地日志？(y/N): ").strip().lower() == 'y'
                pull_logs_multi(targets, f"http://{local_ip}:{LOG_PORT}/api/report", clear)
        elif choice == '4': show_devices()
        elif choice == '5': send_udp(f"FIND_ME_ALARM:{RESCUE_SECRET}", choose_targets_multi())
        elif choice == '6':
            chat = input("对讲消息内容: ").strip()
            if chat:
                send_udp(f"FIND_ME_CHAT:{RESCUE_SECRET}:PC_Terminal:{chat}", choose_targets_multi())
                send_udp(f"FIND_ME_MSG:{RESCUE_SECRET}:PC_Terminal:{chat}", choose_targets_multi())
        elif choice == '7':
            device = choose_adb_device()
            if device:
                cmds = [["cmd", "appops", "set", PACKAGE_NAME, "START_FOREGROUND", "allow"], ["cmd", "appops", "set", PACKAGE_NAME, "RUN_IN_BACKGROUND", "allow"], ["cmd", "appops", "set", PACKAGE_NAME, "INSTANT_APP_START_FOREGROUND", "allow"], ["pm", "grant", PACKAGE_NAME, "android.permission.WRITE_SECURE_SETTINGS"], ["dumpsys", "deviceidle", "whitelist", f"+{PACKAGE_NAME}"], ["cmd", "appops", "write-settings"]]
                for cmd in cmds: subprocess.run(["adb", "-s", device, "shell"] + cmd)
                print(f"{I.ok} 权限已注入。")
        elif choice == '8': send_emergency()
        else: print(f"{I.err} 无效输入: {choice!r}，请输入 0-8, A-E, T, X")

if __name__ == "__main__":
    print(f"[RS] rescue2.py Ultimate {BUILD_VERSION} (协议 v{PROTOCOL_VERSION})")
    print("\n正在启动内置服务...")
    print(f"   环境: {env_name()}")
    print(f"   本机 IP: {get_local_ip()}")

    if is_termux():
        print("\n" + "!" * 50)
        print("[Termux 运行提示]")
        missing_deps = []
        if not shutil.which("adb"): missing_deps.append("android-tools")
        if not shutil.which("ip"): missing_deps.append("iproute2")
        if not shutil.which("arp"): missing_deps.append("net-tools")
        if missing_deps:
            print(f"{I.warn} 检测到缺少依赖包: {', '.join(missing_deps)}")
            print(f"请先执行: pkg install {' '.join(missing_deps)}")
        else:
            print(f"{I.ok} 依赖环境检测通过 (adb, ip, arp 已安装)")
        print(f"{I.warn} 警告：手机息屏后 Termux 可能会被系统挂起！")
        print(f"强烈建议在另一个 Termux 窗口执行: termux-wake-lock")
        if not get_adb_devices():
            print(f"{I.send} 提示：当前未检测到 ADB 设备。如使用无线调试，请执行:")
            print("   adb connect <手机IP>:<端口>")
        print("!" * 50 + "\n")

    threading.Thread(target=start_log_receiver, daemon=True).start()
    time.sleep(0.3)
    threading.Thread(target=udp_listener, daemon=True).start()
    time.sleep(0.3)
    try:
        main()
    except KeyboardInterrupt:
        print("\n手动中断退出。")
        sys.exit(0)
