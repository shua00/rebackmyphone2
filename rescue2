#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DeviceFinder 局域网救援/调试终端
支持环境：Windows / Linux(Armbian) / Termux(Android)

【本次修复】
A. 扫描局域网时，_probe_ips 从 FIND_ME_REPORT 改成 FIND_ME_REPORT_TEMP，
   指向 PC 的 9090 内置接收端。之前 FIND_ME_REPORT 让手机上报到公网服务器，
   PC 的 9090 收不到，导致扫描永远 0 台。

【配套手机端修改】
AndroidManifest.xml 需加：
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
TrackingService.java 的 playAlarm() 需配合修改，才能让静音/震动模式下警报响。

【Termux 加速提示】
如果扫描局域网很慢（15 秒+），可以执行：
    pkg install iproute2 net-tools
这样 ip neigh 和 arp -a 都能用，扫描从 15 秒降到 3 秒。

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
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
from datetime import datetime

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


def get_local_ip():
    """4 级降级获取本机内网 IP"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127.") and ip != "0.0.0.0":
            return ip
    except Exception:
        pass

    try:
        result = subprocess.run(["ip", "-4", "addr"], capture_output=True,
                                text=True, timeout=3, errors='ignore')
        for line in result.stdout.split("\n"):
            line = line.strip()
            if line.startswith("inet ") and "127.0.0.1" not in line:
                parts = line.split()
                if len(parts) >= 2:
                    ip = parts[1].split("/")[0]
                    if is_valid_ip(ip):
                        return ip
    except Exception:
        pass

    try:
        result = subprocess.run(["ifconfig"], capture_output=True,
                                text=True, timeout=3, errors='ignore')
        for line in result.stdout.split("\n"):
            if "inet " in line and "127.0.0.1" not in line:
                parts = line.split()
                for i, p in enumerate(parts):
                    if p == "inet" and i + 1 < len(parts):
                        ip = parts[i + 1]
                        if is_valid_ip(ip) and not ip.startswith("127."):
                            return ip
    except Exception:
        pass

    if os.name == "nt":
        try:
            result = subprocess.run(["ipconfig"], capture_output=True,
                                    text=True, timeout=3, errors='ignore')
            for match in re.finditer(r"IPv4.*?[:：]\s*(\d+\.\d+\.\d+\.\d+)", result.stdout):
                ip = match.group(1)
                if is_valid_ip(ip) and not ip.startswith("127."):
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
def send_udp(command_str, target_ip="255.255.255.255", quiet=False):
    if not quiet:
        print(f"\n📡 发送指令至 {target_ip}: {command_str}")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    send_times = 3 if target_ip == "255.255.255.255" else 1
    for i in range(send_times):
        try:
            sock.sendto(command_str.encode('utf-8'), (target_ip, 8888))
        except Exception as e:
            if not quiet:
                print(f"   [{i+1}/{send_times}] 发送失败: {e}")
            break
        if send_times > 1 and not quiet:
            print(f"   [{i+1}/{send_times}] 发送中...")
            time.sleep(0.5)
        elif send_times == 1 and not quiet:
            print(f"   [1/1] 发送成功 (单播模式)")
    sock.close()
    if not quiet:
        print("✅ 发送完毕。")


def wait_for_response(before, wait_sec, wait_type):
    start = time.time()
    while time.time() - start < wait_sec:
        time.sleep(0.1)
        if wait_type == "report" and RECEIVED_REPORTS > before:
            return time.time() - start
        if wait_type == "log" and RECEIVED_LOGS > before:
            return time.time() - start
    return None


def send_udp_and_wait(command_str, target_ip="255.255.255.255", wait_sec=15, wait_type="report"):
    """先广播，5 秒无响应自动对已知设备单播"""
    def counter():
        return RECEIVED_REPORTS if wait_type == "report" else RECEIVED_LOGS

    if wait_type is None:
        send_udp(command_str, target_ip)
        return

    before = counter()
    send_udp(command_str, target_ip)

    if target_ip != "255.255.255.255":
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
        before = counter()
        send_udp(command_str, ip, quiet=True)
        elapsed = wait_for_response(before, 3, wait_type)
        if elapsed is not None:
            print(f"✅ {dev_id} 响应（{elapsed:.1f}s）")
            return
        else:
            print(f"⚠️ {dev_id} 未响应")

    print(f"⚠️ 所有已知设备均未响应")


def choose_target(prompt="🎯 选择目标 (编号/IP/回车广播): "):
    """大部分指令用：显示已知设备，选编号/IP/回车广播"""
    with _state_lock:
        devices = dict(DISCOVERED_DEVICES)

    if devices:
        print(f"\n📋 当前已知设备（{len(devices)} 台）：")
        dev_list = list(devices.items())
        now = time.time()
        for i, (dev_id, info) in enumerate(dev_list, 1):
            ip = info.get("source_ip") or info.get("ip", "?")
            batt = str(info.get("battery", "?")) + "%"
            last = max(info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0))
            ago = int(now - last) if last > 0 else -1
            ago_str = (f"{ago}秒前" if 0 <= ago < 60 else
                       f"{ago // 60}分钟前" if ago < 3600 else "")
            print(f"  {i}. {dev_id:<26} ({ip:<15}) {batt:<5} {ago_str}")
    else:
        dev_list = []

    choice = input(prompt).strip()
    if not choice:
        return "255.255.255.255"
    if is_valid_ip(choice):
        return choice
    try:
        idx = int(choice)
        if 1 <= idx <= len(dev_list):
            return dev_list[idx - 1][1].get("source_ip") or dev_list[idx - 1][1].get("ip")
    except Exception:
        pass
    print(f"⚠️ 无效输入，使用广播")
    return "255.255.255.255"


# ===== HTTP 接收端 =====
class LogReceiver(BaseHTTPRequestHandler):
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
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"{safe_id}_{ts}.log"
        filepath = os.path.join(LOG_DIR, filename)

        os.makedirs(LOG_DIR, exist_ok=True)
        with open(filepath, "wb") as f:
            f.write(data)

        with _state_lock:
            RECEIVED_LOGS += 1
            DISCOVERED_DEVICES.setdefault(device_id, {})
            DISCOVERED_DEVICES[device_id]["source_ip"] = self.client_address[0]
            DISCOVERED_DEVICES[device_id]["last_log"] = time.time()

        print(f"\n📥 收到日志: {filename} ({len(data)} bytes) → {os.path.abspath(filepath)}")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

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
                loc_str = f"{lat},{lng} (精度 {acc:.0f}m, {provider})"
            else:
                loc_str = "无定位"
            chg_str = "充电中" if is_chg else "未充电"

            print(f"\n📥 [{dev_id}] 电量 {batt}% ({chg_str}) | WiFi {wifi} | IP {local_ip}")
            print(f"   位置: {loc_str} | 周围热点: {len(nearby)} 个")

            with _state_lock:
                RECEIVED_REPORTS += 1
                DISCOVERED_DEVICES[dev_id] = {
                    "ip": local_ip if local_ip and local_ip != "0.0.0.0" else self.client_address[0],
                    "source_ip": self.client_address[0],
                    "last_seen": time.time(),
                    "battery": batt,
                    "wifi": wifi,
                }
        except Exception as e:
            print(f"⚠️ 解析上报失败: {e}")

        resp = b'{"status":"success","config":{"report_interval_seconds":1800,"accuracy_mode":"coarse"}}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, format, *args):
        pass


def start_log_receiver():
    global RECEIVER_OK
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        server = HTTPServer(("0.0.0.0", LOG_PORT), LogReceiver)
        RECEIVER_OK = True
        local_ip = get_local_ip()
        print(f"📥 内置接收端已启动: http://{local_ip}:{LOG_PORT}/")
        print(f"   /api/report      → 实时接收上报")
        print(f"   /api/upload_log  → 接收日志，存到 {os.path.abspath(LOG_DIR)}")
        server.serve_forever()
    except OSError as e:
        if "Address already in use" in str(e) or e.errno in (98, 10048):
            print(f"⚠️ 端口 {LOG_PORT} 被占用，内置接收端未启动")
        else:
            print(f"⚠️ 内置接收端启动失败: {e}")


# ===== UDP 监听 =====
def udp_listener():
    global UDP_OK
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(('0.0.0.0', 8888))
        UDP_OK = True
        print("🎧 UDP 监听已启动，等待手机对讲消息...")
        while True:
            try:
                data, addr = sock.recvfrom(1024)
                msg = data.decode('utf-8', errors='ignore')
                if msg.startswith("FIND_ME_MSG:"):
                    parts = msg.split(":", 4)
                    if len(parts) == 4 and parts[1] == RESCUE_SECRET:
                        sender = parts[2]
                        content = parts[3]
                        print(f"\n🗣️ [{sender}] {content}  (来自 {addr[0]})")
                        with _state_lock:
                            DISCOVERED_DEVICES.setdefault(sender, {})
                            DISCOVERED_DEVICES[sender]["source_ip"] = addr[0]
                            DISCOVERED_DEVICES[sender]["last_chat"] = time.time()
            except Exception as e:
                print(f"⚠️ UDP 监听异常: {e}")
                time.sleep(1)
    except OSError as e:
        if e.errno in (98, 10048):
            print(f"⚠️ UDP 8888 被占用（server2.py 可能在跑），跳过对讲消息监听")
        else:
            print(f"⚠️ UDP 监听失败: {e}")


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
            ago = int(now - last)
            ago_str = (f"{ago}秒前" if ago < 60 else
                       f"{ago // 60}分钟前" if ago < 3600 else
                       f"{ago // 3600}小时前" if ago < 86400 else "很久前")
        else:
            ago_str = "-"
        ip = info.get("source_ip") or info.get("ip", "?")
        batt = str(info.get("battery", "?")) + "%"
        wifi = str(info.get("wifi", "?"))[:18]
        print(f"{dev_id:<28} {ip:<16} {batt:<6} {wifi:<20} {ago_str:<10}")
    print()


def status_line():
    with _state_lock:
        dev_count = len(DISCOVERED_DEVICES)
        latest = 0
        for info in DISCOVERED_DEVICES.values():
            latest = max(latest, info.get("last_seen", 0), info.get("last_log", 0), info.get("last_chat", 0))

    if latest > 0:
        ago = int(time.time() - latest)
        ago_str = (f"{ago}秒前" if ago < 60 else
                   f"{ago // 60}分钟前" if ago < 3600 else
                   f"{ago // 3600}小时前")
    else:
        ago_str = "无"

    if is_termux():
        return f"💡 {dev_count}台 | 收{'✅' if RECEIVER_OK else '❌'} | UDP{'✅' if UDP_OK else '❌'} | {ago_str}"
    return f"💡 设备 {dev_count} 台  |  接收端 {LOG_PORT} {'✅' if RECEIVER_OK else '❌'}  |  UDP {'✅' if UDP_OK else '❌'}  |  上次上报 {ago_str}"


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
def _probe_ips(ips, local_ip):
    """
    [本次修复 A] 用 FIND_ME_REPORT_TEMP 指向 PC 内置接收端。
    原代码发 FIND_ME_REPORT 让手机上报到公网服务器，PC 的 9090 收不到，扫描永远 0 台。
    """
    temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
    cmd = f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}"

    def probe(ip):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.5)
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

    global RECEIVED_REPORTS
    local_ip = get_local_ip()
    temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
    # [本次修复] 用 FIND_ME_REPORT_TEMP 才能收到 PC 的上报
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
            before = RECEIVED_REPORTS
            print(f"\n--- 第 {probe_count} 次探测 ---")

            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            for _ in range(3):
                try:
                    sock.sendto(cmd.encode(), (target_ip, 8888))
                    time.sleep(0.05)
                except Exception:
                    pass
            sock.close()

            wait_until = time.time() + 10
            got = False
            while time.time() < wait_until:
                time.sleep(0.2)
                if RECEIVED_REPORTS > before:
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


# ===== 多选目标 + 多台拉日志 =====
def select_targets():
    """拉日志专用：显示已知设备，支持编号/all/IP/回车/scan"""
    with _state_lock:
        devices = dict(DISCOVERED_DEVICES)

    if devices:
        print(f"\n📋 当前已知设备（{len(devices)} 台）：")
        dev_list = list(devices.items())
        now = time.time()
        for i, (dev_id, info) in enumerate(dev_list, 1):
            ip = info.get("source_ip") or info.get("ip", "?")
            batt = str(info.get("battery", "?")) + "%"
            wifi = str(info.get("wifi", "?"))[:16]
            last = max(info.get("last_seen", 0), info.get("last_log", 0))
            ago = int(now - last) if last > 0 else -1
            ago_str = f"{ago}秒前" if 0 <= ago < 60 else (f"{ago // 60}分钟前" if ago < 3600 else "")
            print(f"  {i}. {dev_id:<26} ({ip:<15}) {batt:<5} {wifi:<18} {ago_str}")
    else:
        print("\n📭 当前没有已知设备")
        dev_list = []

    print("\n请选择目标：")
    print("  编号 (1/2/..., 逗号分隔多选)  → 单播到指定设备")
    print("  all                          → 所有已知设备")
    print("  IP (如 192.168.1.38)         → 单播到指定 IP")
    print("  回车                          → 广播（失败后自动降级单播）")
    print("  scan                         → 先扫描局域网再选择")

    choice = input("> ").strip()

    if not choice:
        return ["255.255.255.255"]
    if choice.lower() == "scan":
        scan_lan_devices()
        return select_targets()
    if choice.lower() == "all":
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


def pull_logs_multi(target_ips, temp_url, clear_after):
    if not target_ips:
        print("⚠️ 未选择任何目标")
        return

    global RECEIVED_LOGS
    print(f"\n📡 拉取 {len(target_ips)} 台设备的日志（逐台执行）")
    print(f"   ⚠️ 手机必须亮屏，否则 HTTP 上传被限制")
    cmd = f"FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}:{temp_url}" + (":clear" if clear_after else "")

    success = 0
    for i, ip in enumerate(target_ips, 1):
        print(f"\n--- [{i}/{len(target_ips)}] {ip} ---")
        before = RECEIVED_LOGS
        send_udp(cmd, ip, quiet=True)
        print(f"   已发送，等待上传...")
        elapsed = wait_for_response(before, 30, "log")
        if elapsed is not None:
            print(f"   ✅ 收到日志（{elapsed:.1f}s）")
            success += 1
        else:
            print(f"   ⚠️ 超时未收到")

    print(f"\n📊 完成: {success}/{len(target_ips)} 台成功")


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
            send_udp(f"FIND_ME_SERVER:{RESCUE_SECRET}:{url}", choose_target())
        elif c == '2':
            url = input(f"新配置中心 URL (回车默认 {DEFAULT_CONFIG_URL}): ").strip() or DEFAULT_CONFIG_URL
            if not is_valid_url(url):
                print(f"❌ URL 非法: {url}")
                continue
            send_udp(f"FIND_ME_CONFIG:{RESCUE_SECRET}:{url}", choose_target())
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
            send_udp(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{url}", choose_target())


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
            send_udp(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:1", choose_target())
        elif c == '2':
            send_udp(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:0", choose_target())
        elif c == '3':
            send_udp(f"FIND_ME_CHAT_LOCK:{RESCUE_SECRET}", choose_target())
        elif c == '4':
            send_udp(f"FIND_ME_CHAT_UNLOCK:{RESCUE_SECRET}", choose_target())


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
                send_udp(f"FIND_ME_MSG:{RESCUE_SECRET}:{msg}", choose_target())
        elif c == '2':
            send_udp(f"FIND_ME_CLEAR_HISTORY:{RESCUE_SECRET}", choose_target())
        elif c == '3':
            num = input("报警短信手机号: ").strip()
            if num:
                send_udp(f"FIND_ME_SET_SMS_NUM:{RESCUE_SECRET}:{num}", choose_target())
        elif c == '4':
            url = input(f"接收 URL (回车默认 http://{local_ip}:{LOG_PORT}/api/report): ").strip()
            if not url:
                url = f"http://{local_ip}:{LOG_PORT}/api/report"
            if not is_valid_url(url):
                print(f"❌ URL 非法: {url}")
                continue
            targets = select_targets()
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
            send_udp(f"FIND_ME_SCAN_WIFI:{RESCUE_SECRET}", choose_target())
        elif c == '2':
            send_udp(f"FIND_ME_RESTART_UDP:{RESCUE_SECRET}", choose_target())
        elif c == '3':
            send_udp(f"FIND_ME_TEST_LOW_BATTERY:{RESCUE_SECRET}", choose_target())
        elif c == '4':
            periodic_probe(choose_target())
        elif c == '5':
            scan_lan_devices()


# ===== 主菜单 =====
def print_main_menu(local_ip):
    print("\n" + "=" * 50)
    print("🛠️  DeviceFinder 救援终端")
    print(f"   {env_name()}  |  {local_ip}")
    print("=" * 50)
    print("\n📱 高频操作")
    print("  1. 📍 强制上报")
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
            send_udp_and_wait(f"FIND_ME_REPORT:{RESCUE_SECRET}", choose_target(), 15, "report")
        elif choice == '2':
            # [本次修复] 定位+上报：先让手机重新定位，再拉取最新位置到 PC
            target = choose_target()
            temp_url = f"http://{local_ip}:{LOG_PORT}/api/report"
            send_udp(f"FIND_ME_LOCATE_ONCE:{RESCUE_SECRET}", target, quiet=True)
            print("⏳ 等待手机定位（10 秒）...")
            time.sleep(10)
            send_udp_and_wait(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}", target, 15, "report")
        elif choice == '3':
            url = f"http://{local_ip}:{LOG_PORT}/api/report"
            print(f"📥 内置接收端: {url}")
            print(f"   保存目录: {os.path.abspath(LOG_DIR)}")
            targets = select_targets()
            if not targets:
                continue
            clear = input("上传后清空手机本地日志？(y/N): ").strip().lower() == 'y'
            pull_logs_multi(targets, url, clear)
        elif choice == '4':
            show_devices()
        elif choice == '5':
            print("\n⚠️ 手机将以最大音量播放 30 秒警报（静音/震动也会响）")
            send_udp(f"FIND_ME_ALARM:{RESCUE_SECRET}", choose_target())
        elif choice == '6':
            chat = input("对讲消息内容: ").strip()
            if chat:
                send_udp(f"FIND_ME_MSG:{RESCUE_SECRET}:PC_Terminal:{chat}", choose_target())


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
