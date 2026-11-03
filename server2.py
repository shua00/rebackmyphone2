import socket
import json
import datetime
import threading
import time
import sqlite3
import gzip
import os
import re
from urllib.parse import urlparse, parse_qs

"""
============================================================
DeviceFinder server2.py — 全功能同步服务
============================================================
【已实现功能】
- reports 表完整持久化（含扩展字段 ext_json、SIM 状态等）
- HTTP 下行队列（pending_commands 双通道下发）
- UDP 监听 + 聊天室去重
- 离线设备管理（单设备删除、批量清理 >24h 设备）
- 地图三图层（天地图无偏移 + 高德自动坐标转换 + 谷歌国际版备用）
- 地图容器延迟初始化（解决手机端白屏）
- 全前端 JS 已 ES5 化，完美兼容所有浏览器 / 微信 / WebView

【安全性 / 健壮性修复】
- 修复 .get("key", {}).get() 链式调用遇到 null 崩溃的问题，统一改用 (get() or {}).get()
- 修复 has_internet 等字段为 0 时被误判为 None 的 Bug，新增严格 _parse_bool_val 解析
- GET / 增加 try-except 保护，防止个别设备脏数据导致全页面白屏
- 修复 Python 字符串与 JS 换行符转义冲突导致的 SyntaxError
============================================================
"""

# ===== 上报数据保留策略 =====
REPORTS_KEEP_RAW_DAYS = 30        # raw_json 保留天数
REPORTS_KEEP_NEARBY_DAYS = 90     # nearby_wifi_json 保留天数
REPORTS_RAW_MAX_BYTES = 8192      # raw_json 单条最大字节
REPORTS_EXT_MAX_BYTES = 4096      # ext_json 单条最大字节

# ===== 清理策略 =====
CLEAN_OFFLINE_THRESHOLD_MS = 24 * 3600 * 1000   # "清理全部离线设备"阈值：24 小时
MANAGE_OFFLINE_THRESHOLD_S = 30 * 60            # "管理离线设备"弹窗展示阈值：30 分钟

PORT = 8080
AUTH_TOKEN = "your_private_secret_key"
RESCUE_SECRET = "your_private_secret_key"

GLOBAL_DEFAULT_CONFIG = {
    "report_interval_seconds": 1800,
    "accuracy_mode": "coarse",
    "next_server_url": "",
    "next_config_url": "",
    "hide_icon": True
}

ALL_DEVICES_STATUS = {}
CHAT_MESSAGES = []
print_lock = threading.Lock()

LAST_MSG_INFO = {}
LAST_MSG_INFO_MAX = 100
LAST_MSG_INFO_TTL = 10.0

UPLOAD_LOG_DIR = "uploads/logs"
os.makedirs(UPLOAD_LOG_DIR, exist_ok=True)

# ===== HTTP 下行指令队列 =====
PENDING_COMMANDS = {}
CMD_TTL_SECONDS = 86400
CMD_MAX_PER_DEVICE = 50
CMD_SEQ = 0
_cmd_lock = threading.Lock()

HTTP_SUPPORTED_ACTIONS = {
    "force_report", "play_alarm", "custom_msg", "chat_msg",
    "change_server", "clear_history", "clear_chat",
    "lock_chat", "unlock_chat", "set_sms_num", "set_cloud_log",
    "upload_log", "locate_once", "scan_wifi", "restart_udp", "test_low_battery"
}

# ===== reports 表字段声明 =====
REPORTS_COLUMNS = [
    ("id",                     "INTEGER PRIMARY KEY AUTOINCREMENT"),
    ("device_id",              "TEXT NOT NULL"),
    ("timestamp",              "INTEGER NOT NULL"),
    ("received_at",            "INTEGER NOT NULL"),
    ("day_str",                "TEXT"),
    ("hour",                   "INTEGER"),
    ("weekday",                "INTEGER"),
    ("battery_level",          "INTEGER"),
    ("is_charging",            "INTEGER"),
    ("battery_temp_c",         "REAL"),
    ("battery_voltage_mv",     "INTEGER"),
    ("wifi_ssid",              "TEXT"),
    ("wifi_bssid",             "TEXT"),
    ("wifi_rssi",              "INTEGER"),
    ("local_ip",               "TEXT"),
    ("source_ip",              "TEXT"),
    ("network_type",           "TEXT"),
    ("network_subtype",        "TEXT"),
    ("has_internet",           "INTEGER"),
    ("sim_state",              "TEXT"),
    ("sim_operator",           "TEXT"),
    ("is_screen_on",           "INTEGER"),
    ("is_screen_locked",       "INTEGER"),
    ("airplane_mode",          "INTEGER"),
    ("ringer_mode",            "TEXT"),
    ("volume_alarm",           "INTEGER"),
    ("unlock_count_since_boot","INTEGER"),
    ("chat_locked",            "INTEGER"),
    ("elder_mode",             "INTEGER"),
    ("os_version",             "TEXT"),
    ("uptime_ms",              "INTEGER"),
    ("lat",                    "REAL"),
    ("lng",                    "REAL"),
    ("accuracy",               "REAL"),
    ("location_provider",      "TEXT"),
    ("location_time",          "INTEGER"),
    ("location_age_s",         "INTEGER"),
    ("recent_locations_json",  "TEXT"),
    ("nearby_wifi_count",      "INTEGER"),
    ("nearby_wifi_json",       "TEXT"),
    ("raw_json",               "TEXT"),
    ("ext_json",               "TEXT"),
]

REPORTS_INDEXES = [
    ("idx_reports_dev_ts",  "ON reports(device_id, timestamp)"),
    ("idx_reports_dev_day", "ON reports(device_id, day_str)"),
    ("idx_reports_ts",      "ON reports(timestamp)"),
    ("idx_reports_day",     "ON reports(day_str)"),
]


def _parse_bool_val(v):
    """严格解析布尔/整型状态，避免 0 == False 导致 0 被转为 None"""
    if v is None:
        return None
    if v is True or v == 1:
        return 1
    if v is False or v == 0:
        return 0
    return None


def init_db():
    conn = sqlite3.connect('track_history.db', timeout=10)
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS history
                 (device_id TEXT, lat REAL, lng REAL, timestamp INTEGER)''')
    c.execute('''CREATE TABLE IF NOT EXISTS messages
                 (device_id TEXT, msg_time TEXT, message TEXT, timestamp INTEGER)''')
    col_defs = ", ".join(f"{name} {typ}" for name, typ in REPORTS_COLUMNS)
    c.execute(f"CREATE TABLE IF NOT EXISTS reports ({col_defs})")
    try:
        existing = {row[1] for row in c.execute("PRAGMA table_info(reports)").fetchall()}
        for name, typ in REPORTS_COLUMNS:
            if name in existing:
                continue
            if "PRIMARY KEY" in typ.upper() or "NOT NULL" in typ.upper():
                continue
            try:
                c.execute(f"ALTER TABLE reports ADD COLUMN {name} {typ}")
            except Exception as e:
                print(f"⚠️ [reports] 迁移列 {name} 失败: {e}")
    except Exception as e:
        print(f"⚠️ [reports] PRAGMA table_info 失败: {e}")
    for idx_name, idx_def in REPORTS_INDEXES:
        try:
            c.execute(f"CREATE INDEX IF NOT EXISTS {idx_name} {idx_def}")
        except Exception:
            pass
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=10000")
    except Exception:
        pass
    conn.commit()
    conn.close()


init_db()


# ==================== save_report ====================

def save_report(dev_id, data, source_ip):
    """完整上报落库。失败不影响 /api/report 的 200 响应。"""
    try:
        received_at = data.get("received_at", 0) or int(datetime.datetime.now().timestamp() * 1000)
        ts = data.get("timestamp", 0) or 0
        if not isinstance(ts, (int, float)) or ts < 1000000000000 or ts > received_at + 86400000:
            ts = received_at
        ts = int(ts)
        received_at = int(received_at)
        dt = datetime.datetime.fromtimestamp(ts / 1000.0)

        battery = data.get("battery") or {}
        batt_level = battery.get("level")
        if not isinstance(batt_level, int):
            try: batt_level = int(batt_level)
            except (TypeError, ValueError): batt_level = None
        is_charging = _parse_bool_val(battery.get("is_charging"))
        batt_temp = battery.get("temp_c")
        if not isinstance(batt_temp, (int, float)): batt_temp = None
        batt_volt = battery.get("voltage_mv")
        if not isinstance(batt_volt, int): batt_volt = None

        network = data.get("network") or {}
        wifi_ssid = (network.get("wifi_ssid") or "").strip()
        wifi_bssid = (network.get("wifi_bssid") or "").strip() or None
        local_ip = (network.get("local_ip") or "").strip() or None
        wifi_rssi = network.get("wifi_rssi")
        if not isinstance(wifi_rssi, int):
            try: wifi_rssi = int(wifi_rssi)
            except (TypeError, ValueError): wifi_rssi = None
        net_subtype = network.get("network_subtype") or None
        has_internet = _parse_bool_val(network.get("has_internet"))

        if wifi_ssid and wifi_ssid not in ("UNKNOWN", "OFF", "<unknown ssid>"):
            net_type = "WiFi"
        elif local_ip and not local_ip.startswith("127.") and local_ip != "0.0.0.0":
            net_type = "MOBILE"
        else:
            net_type = "离线"

        sim_state = data.get("sim_state")
        if not isinstance(sim_state, str): sim_state = None
        sim_operator = data.get("sim_operator")
        if not isinstance(sim_operator, str): sim_operator = None

        ringer_mode = data.get("ringer_mode")
        if not isinstance(ringer_mode, str): ringer_mode = None
        volume_alarm = data.get("volume_alarm")
        if not isinstance(volume_alarm, int): volume_alarm = None
        unlock_cnt = data.get("unlock_count_since_boot")
        if not isinstance(unlock_cnt, int): unlock_cnt = None
        
        is_screen_locked = _parse_bool_val(data.get("is_screen_locked"))
        airplane_mode = _parse_bool_val(data.get("airplane_mode"))

        loc = data.get("location") or {}
        def _num(v):
            if v is None: return None
            try:
                f = float(v)
                if f != f or f in (float('inf'), float('-inf')): return None
                return f
            except (TypeError, ValueError): return None

        lat = _num(loc.get("latitude")); lng = _num(loc.get("longitude"))
        if lat is None or not (-90 <= lat <= 90): lat = None
        if lng is None or not (-180 <= lng <= 180): lng = None
        if lat is None or lng is None: lat = lng = None
        accuracy = _num(loc.get("accuracy"))
        if accuracy is not None and (accuracy < 0 or accuracy > 100000): accuracy = None
        loc_time = loc.get("time")
        if not isinstance(loc_time, int) or loc_time <= 0: loc_time = None
        loc_age_s = loc.get("age_s")
        if not isinstance(loc_age_s, int) or loc_age_s < 0: loc_age_s = None

        recent_locs = data.get("recent_locations")
        recent_locs_json = None
        if isinstance(recent_locs, list) and recent_locs:
            try: recent_locs_json = json.dumps(recent_locs, ensure_ascii=False)[:4000]
            except Exception: pass

        nearby = data.get("nearby_wifi") or []
        if not isinstance(nearby, list): nearby = []
        nearby_json = None
        if nearby:
            try: nearby_json = json.dumps(nearby, ensure_ascii=False)[:8000]
            except Exception: pass

        raw_json = None
        try: raw_json = json.dumps(data, ensure_ascii=False)[:REPORTS_RAW_MAX_BYTES]
        except Exception: pass

        declared_keys = {
            "device_id", "timestamp", "received_at", "battery", "network",
            "location", "nearby_wifi", "recent_locations", "is_screen_on",
            "chat_locked", "elder_mode", "os_version", "uptime_ms",
            "sim_state", "sim_operator", "ringer_mode", "volume_alarm",
            "unlock_count_since_boot", "is_screen_locked", "airplane_mode",
            "token", "last_executed_cmd_ids"
        }
        extra = {k: v for k, v in data.items() if k not in declared_keys}
        ext_json = None
        if extra:
            try: ext_json = json.dumps(extra, ensure_ascii=False)[:REPORTS_EXT_MAX_BYTES]
            except Exception: pass

        conn = sqlite3.connect('track_history.db', timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            conn.execute("""INSERT INTO reports
                (device_id, timestamp, received_at, day_str, hour, weekday,
                 battery_level, is_charging, battery_temp_c, battery_voltage_mv,
                 wifi_ssid, wifi_bssid, wifi_rssi, local_ip, source_ip,
                 network_type, network_subtype, has_internet,
                 sim_state, sim_operator,
                 is_screen_on, is_screen_locked, airplane_mode,
                 ringer_mode, volume_alarm, unlock_count_since_boot,
                 chat_locked, elder_mode, os_version, uptime_ms,
                 lat, lng, accuracy, location_provider, location_time, location_age_s,
                 recent_locations_json,
                 nearby_wifi_count, nearby_wifi_json, raw_json, ext_json)
                VALUES (?,?,?,?,?,?, ?,?,?,?, ?,?,?,?,?, ?,?,?, ?,?, ?,?,?, ?,?,?, ?,?,?,?, ?,?,?,?,?,?, ?, ?,?,?,?)""", (
                dev_id, ts, received_at, dt.strftime("%Y-%m-%d"), dt.hour, dt.weekday(),
                batt_level, is_charging, batt_temp, batt_volt,
                wifi_ssid or None, wifi_bssid, wifi_rssi, local_ip, source_ip,
                net_type, net_subtype, has_internet,
                sim_state, sim_operator,
                1 if data.get("is_screen_on") else 0, is_screen_locked, airplane_mode,
                ringer_mode, volume_alarm, unlock_cnt,
                1 if data.get("chat_locked") else 0,
                1 if data.get("elder_mode") else 0,
                data.get("os_version") if isinstance(data.get("os_version"), str) else None,
                data.get("uptime_ms") if isinstance(data.get("uptime_ms"), int) else None,
                lat, lng, accuracy,
                (loc.get("provider") or None) if isinstance(loc.get("provider"), str) else None,
                loc_time, loc_age_s,
                recent_locs_json,
                len(nearby), nearby_json, raw_json, ext_json
            ))
            conn.commit()
        finally:
            conn.close()
    except Exception as e:
        with print_lock:
            print(f"⚠️ [reports] 写入失败 {dev_id}: {e}")


def cleanup_old_reports():
    """分阶段清理，控制 reports 表膨胀"""
    try:
        now_ms = int(datetime.datetime.now().timestamp() * 1000)
        cleared_raw = cleared_nearby = 0
        conn = sqlite3.connect('track_history.db', timeout=30)
        try:
            conn.execute("PRAGMA busy_timeout=30000")
            c = conn.cursor()
            if REPORTS_KEEP_RAW_DAYS > 0:
                cutoff = now_ms - REPORTS_KEEP_RAW_DAYS * 86400 * 1000
                c.execute("UPDATE reports SET raw_json=NULL WHERE timestamp < ? AND raw_json IS NOT NULL", (cutoff,))
                cleared_raw = c.rowcount
            if REPORTS_KEEP_NEARBY_DAYS > 0:
                cutoff = now_ms - REPORTS_KEEP_NEARBY_DAYS * 86400 * 1000
                c.execute("UPDATE reports SET nearby_wifi_json=NULL WHERE timestamp < ? AND nearby_wifi_json IS NOT NULL", (cutoff,))
                cleared_nearby = c.rowcount
            conn.commit()
        finally:
            conn.close()
        if cleared_raw or cleared_nearby:
            with print_lock:
                print(f"🧹 [reports] 清理完成: raw_json -{cleared_raw}, nearby -{cleared_nearby}")
    except Exception as e:
        with print_lock:
            print(f"⚠️ [reports] 清理失败: {e}")


# ==================== 设备解析 ====================

def _state_lock_snapshot():
    class _Ctx:
        def __enter__(self_inner):
            with print_lock:
                return {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
        def __exit__(self_inner, *args):
            return False
    return _Ctx()


def resolve_target_devices(target_ip):
    with _state_lock_snapshot() as devices:
        if not devices:
            return []
        if target_ip == "all" or target_ip == "255.255.255.255":
            return list(devices.keys())
        if isinstance(target_ip, list):
            ips = set(ip for ip in target_ip if ip and ip != "0.0.0.0")
            if not ips: return []
            result = []
            for dev_id, info in devices.items():
                candidates = {
                    info.get("source_ip"),
                    info.get("udp_source_ip"),
                    (info.get("network") or {}).get("local_ip"),
                }
                if candidates & ips:
                    result.append(dev_id)
            return result
        result = []
        for dev_id, info in devices.items():
            candidates = {
                info.get("source_ip"),
                info.get("udp_source_ip"),
                (info.get("network") or {}).get("local_ip"),
            }
            if target_ip in candidates:
                result.append(dev_id)
        return result


# ==================== HTTP 下行队列 ====================

def enqueue_command(dev_id, action, payload):
    global CMD_SEQ
    if not dev_id: return
    now = time.time()
    payload_str = payload if payload is not None else ""
    with _cmd_lock:
        queue = PENDING_COMMANDS.setdefault(dev_id, [])
        for i, c in enumerate(queue):
            if c["type"] == action and c["payload"] == payload_str:
                queue[i] = {"id": c["id"], "type": action, "payload": payload_str, "ts": now}
                with print_lock:
                    print(f"📋 [HTTP队列] {dev_id} ~{action}({c['id']}) 去重（延后过期）")
                return
        CMD_SEQ += 1
        cmd_id = f"c_{int(now)}_{CMD_SEQ}"
        dropped = 0
        while len(queue) >= CMD_MAX_PER_DEVICE:
            queue.pop(0); dropped += 1
        queue.append({"id": cmd_id, "type": action, "payload": payload_str, "ts": now})
    with print_lock:
        if dropped:
            print(f"📋 [HTTP队列] {dev_id} +{action}({cmd_id}) 队列满丢弃 {dropped} 条旧指令")
        else:
            print(f"📋 [HTTP队列] {dev_id} +{action}({cmd_id})")


def process_executed_commands(dev_id, executed_ids):
    if not executed_ids: return
    id_set = {x for x in executed_ids if isinstance(x, str) and x}
    if not id_set: return
    removed = 0
    with _cmd_lock:
        queue = PENDING_COMMANDS.get(dev_id, [])
        if not queue: return
        new_queue = [c for c in queue if c["id"] not in id_set]
        removed = len(queue) - len(new_queue)
        if new_queue: PENDING_COMMANDS[dev_id] = new_queue
        else: PENDING_COMMANDS.pop(dev_id, None)
    if removed > 0:
        with print_lock:
            print(f"✅ [HTTP队列] {dev_id} 已确认执行 {removed} 条")


def get_pending_commands(dev_id):
    now = time.time()
    with _cmd_lock:
        queue = PENDING_COMMANDS.get(dev_id, [])
        if not queue: return []
        valid = [c for c in queue if now - c["ts"] < CMD_TTL_SECONDS]
        if len(valid) != len(queue):
            if valid: PENDING_COMMANDS[dev_id] = valid
            else: PENDING_COMMANDS.pop(dev_id, None)
        return [dict(c) for c in valid]


def cleanup_expired_commands():
    now = time.time()
    with _cmd_lock:
        for dev_id in list(PENDING_COMMANDS.keys()):
            queue = PENDING_COMMANDS.get(dev_id, [])
            valid = [c for c in queue if now - c["ts"] < CMD_TTL_SECONDS]
            if valid: PENDING_COMMANDS[dev_id] = valid
            else: PENDING_COMMANDS.pop(dev_id, None)


def cmd_cleaner():
    """每小时清理一次过期数据"""
    while True:
        try:
            time.sleep(3600)
            cleanup_expired_commands()
            cleanup_old_reports()
        except Exception as e:
            with print_lock:
                print(f"⚠️ 清理异常: {e}")


# ==================== UDP 监听 ====================

def udp_listener():
    while True:
        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            udp_sock.bind(('0.0.0.0', 8888))
            print("🎧 UDP 监听已启动，等待局域网对讲机消息...")
            while True:
                try:
                    data, addr = udp_sock.recvfrom(4096)
                except Exception as recv_err:
                    print(f"⚠️ UDP recvfrom 异常: {recv_err}")
                    time.sleep(1)
                    continue
                msg = data.decode('utf-8', errors='ignore')
                source_ip = addr[0]
                if msg.startswith("FIND_ME_MSG:"):
                    parts = msg.split(":", 3)
                    if len(parts) == 4 and parts[1] == RESCUE_SECRET:
                        sender = parts[2]
                        content = parts[3]
                        if sender not in ["Web_Console", "PC_Terminal"]:
                            dedup_key = sender + ":" + content
                            now_ts = time.time()
                            if now_ts - LAST_MSG_INFO.get(dedup_key, 0) < 3.0:
                                continue
                            LAST_MSG_INFO[dedup_key] = now_ts
                            if len(LAST_MSG_INFO) > LAST_MSG_INFO_MAX:
                                cutoff = now_ts - LAST_MSG_INFO_TTL
                                stale = [k for k, v in list(LAST_MSG_INFO.items()) if v < cutoff]
                                for k in stale: LAST_MSG_INFO.pop(k, None)
                            now_ms = int(datetime.datetime.now().timestamp() * 1000)
                            with print_lock:
                                if sender not in ALL_DEVICES_STATUS:
                                    ALL_DEVICES_STATUS[sender] = {
                                        "device_id": sender, "received_at": now_ms,
                                        "network": {}, "battery": {"level": "?"}
                                    }
                                info = ALL_DEVICES_STATUS[sender]
                                if not info.get('network'): info['network'] = {}
                                info['udp_source_ip'] = source_ip
                                info["received_at"] = now_ms
                                info.pop("offline_alerted", None)
                                info["last_chat"] = now_ms
                            ts = datetime.datetime.now().strftime("%H:%M:%S")
                            with print_lock:
                                print(f"\n🗣️ [对讲机] {sender} (来自IP: {source_ip}) 说: {content}")
                                CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                                if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
        except OSError as e:
            if e.errno in (98, 10048) or "Address already in use" in str(e):
                print("❌ UDP 8888 被占用（可能 chat.py 正在运行）。")
                print("   server2.py 的 Web 面板仍可用，但无法接收手机对讲机消息。")
                return
            else:
                print(f"❌ UDP 监听绑定失败: {e}")
                time.sleep(5)
                continue
        except Exception as e:
            print(f"❌ UDP 监听异常: {e}")
            time.sleep(5)
            continue
        finally:
            try: udp_sock.close()
            except Exception: pass


def offline_detector():
    while True:
        try:
            time.sleep(300)
            now = int(datetime.datetime.now().timestamp() * 1000)
            with print_lock:
                for dev_id, info in list(ALL_DEVICES_STATUS.items()):
                    last_seen = info.get('received_at', 0)
                    gap_min = (now - last_seen) // 60000
                    already_alerted = info.get('offline_alerted', False)
                    if gap_min > 120 and not already_alerted:
                        info['offline_alerted'] = True
                        print(f"\n🚨 【失联告警】{dev_id} 已 {gap_min // 60} 小时 {gap_min % 60} 分钟未上报！")
                    elif gap_min <= 120 and already_alerted:
                        info['offline_alerted'] = False
                        print(f"\n✅ 【恢复】{dev_id} 已恢复上报")
        except Exception as e:
            with print_lock:
                print(f"⚠️ 失联检测异常: {e}")


def save_history(dev_id, lat, lng, ts):
    if lat is None or lng is None: return
    try:
        conn = sqlite3.connect('track_history.db', timeout=10)
        c = conn.cursor()
        c.execute("INSERT INTO history VALUES (?, ?, ?, ?)", (dev_id, lat, lng, ts))
        conn.commit(); conn.close()
    except Exception: pass


def format_loc_time(ts_ms):
    if not ts_ms: return "N/A"
    try:
        dt = datetime.datetime.fromtimestamp(ts_ms / 1000.0)
        seconds = int((datetime.datetime.now() - dt).total_seconds())
        if seconds < 0: ago = "刚刚"
        elif seconds < 60: ago = f"{seconds}秒前"
        elif seconds < 3600: ago = f"{seconds // 60}分钟前"
        elif seconds < 86400: ago = f"{seconds // 3600}小时前"
        else: ago = f"{seconds // 86400}天前"
        return f"{dt.strftime('%m-%d %H:%M:%S')} <br><span style='font-size:12px;color:#57606a;'>{ago}</span>"
    except Exception: return "N/A"


def get_local_ip():
    for target in [("8.8.8.8", 80), ("114.114.114.114", 53), ("1.1.1.1", 443), ("223.5.5.5", 53)]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(1); s.connect(target)
            ip = s.getsockname()[0]; s.close()
            if (ip and not ip.startswith("127.") and ip != "0.0.0.0" and not ip.startswith("169.254.")):
                return ip
        except Exception: pass
    try:
        with open("/proc/net/fib_trie", "r") as f:
            lines = f.readlines()
        for i, line in enumerate(lines):
            if "LOCAL" in line and i > 0:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", lines[i - 1])
                if m:
                    ip = m.group(1)
                    if (ip != "0.0.0.0" and not ip.startswith("127.") and not ip.startswith("169.254.")):
                        return ip
    except Exception: pass
    return "127.0.0.1"


def get_subnet_broadcast():
    ip = get_local_ip()
    if ip.startswith("127.") or ip == "0.0.0.0": return "255.255.255.255"
    parts = ip.split(".")
    if len(parts) == 4: return ".".join(parts[:3]) + ".255"
    return "255.255.255.255"


def send_udp_broadcast(command_str, target_ip="255.255.255.255"):
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        is_full = (target_ip == "255.255.255.255")
        is_subnet = target_ip.endswith(".255") and not is_full
        if is_full:
            subnet = get_subnet_broadcast()
            if subnet != "255.255.255.255":
                actual = subnet; targets = [subnet, "255.255.255.255"]
            else:
                actual = "255.255.255.255"; targets = ["255.255.255.255"]
            repeats = 3
        elif is_subnet:
            actual = target_ip; targets = [target_ip]; repeats = 3
        else:
            actual = target_ip; targets = [target_ip]; repeats = 2
        mode = " (广播)" if (is_full or is_subnet) else " (单播)"
        with print_lock:
            print(f"\n📡 [控制台] 发送至 {actual}{mode}: {command_str}")
        for t in targets:
            for _ in range(repeats):
                try:
                    sock.sendto(command_str.encode('utf-8'), (t, 8888))
                    time.sleep(0.05)
                except Exception: pass
        sock.close()
        return True
    except Exception as e:
        with print_lock: print(f"发送失败: {e}")
        return False


# ==================== 主处理器 ====================

def handle_client(client_conn, client_addr):
    try:
        client_conn.settimeout(3.0)
        try:
            first_chunk = client_conn.recv(65536)
        except socket.timeout:
            return
        if not first_chunk: return

        client_conn.settimeout(10.0)
        raw = first_chunk
        while b"\r\n\r\n" not in raw:
            chunk = client_conn.recv(65536)
            if not chunk: break
            raw += chunk
            if len(raw) > 10 * 1024 * 1024: break
        header_part, _, body_part = raw.partition(b"\r\n\r\n")
        header_text = header_part.decode('utf-8', errors='ignore')

        content_length = 0
        for line in header_text.split("\r\n"):
            if line.lower().startswith("content-length:"):
                try: content_length = int(line.split(":", 1)[1].strip())
                except Exception: pass
                break
        if content_length > 0:
            while len(body_part) < content_length:
                chunk = client_conn.recv(65536)
                if not chunk: break
                body_part += chunk
            body_part = body_part[:content_length]
        if not header_text: return

        if header_text.startswith("OPTIONS"):
            client_conn.sendall(b"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return

        # ===== POST /api/report =====
        if "POST /api/report" in header_text:
            try:
                data = json.loads(body_part.decode('utf-8', errors='ignore'))
                if data.get("token") != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                dev_id = data.get("device_id", "unknown_device")
                
                if data.get("network") is None: data["network"] = {}
                if data.get("battery") is None: data["battery"] = {}
                if data.get("location") is None: data["location"] = {}
                if data.get("nearby_wifi") is None: data["nearby_wifi"] = []

                executed_ids = data.pop("last_executed_cmd_ids", [])
                if executed_ids:
                    process_executed_commands(dev_id, executed_ids)
                data["received_at"] = int(datetime.datetime.now().timestamp() * 1000)

                with print_lock:
                    if dev_id not in ALL_DEVICES_STATUS:
                        ALL_DEVICES_STATUS[dev_id] = {}
                    prev_info = dict(ALL_DEVICES_STATUS[dev_id])
                    prev_udp_ip = prev_info.get("udp_source_ip")
                    if "cloud_logs" in data:
                        prev_info["cloud_logs"] = data["cloud_logs"]
                        prev_info["cloud_logs_time"] = data["received_at"]
                    elif "cloud_logs" in prev_info:
                        data["cloud_logs"] = prev_info["cloud_logs"]
                        data["cloud_logs_time"] = prev_info.get("cloud_logs_time", 0)
                    data["source_ip"] = client_addr[0]
                    data["local_ip"] = (data.get("network") or {}).get("local_ip", "")
                    if prev_udp_ip:
                        data["udp_source_ip"] = prev_udp_ip
                    if prev_info.get("last_chat"):
                        data["last_chat"] = prev_info["last_chat"]
                    ALL_DEVICES_STATUS[dev_id] = data

                loc = data.get('location') or {}
                lat, lng = loc.get('latitude'), loc.get('longitude')
                if lat is not None and lng is not None:
                    save_history(dev_id, lat, lng, data["received_at"])
                save_report(dev_id, data, client_addr[0])

                pending = get_pending_commands(dev_id)
                next_poll = 30 if pending else 0
                response_body = json.dumps({
                    "status": "success",
                    "config": GLOBAL_DEFAULT_CONFIG,
                    "pending_commands": pending,
                    "next_poll_seconds": next_poll
                })
                response_bytes = response_body.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {len(response_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + response_bytes)
            except Exception as e:
                with print_lock: print(f"❌ POST /api/report 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== POST /api/message =====
        elif "POST /api/message" in header_text:
            try:
                data = json.loads(body_part.decode('utf-8', errors='ignore'))
                if data.get("token") != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                dev_id = data.get("device_id", "unknown")
                msg_time = data.get("time_str", "")
                msg = data.get("message", "")
                ts = data.get("timestamp", 0)
                conn = sqlite3.connect('track_history.db', timeout=10)
                c = conn.cursor()
                c.execute("INSERT INTO messages VALUES (?, ?, ?, ?)", (dev_id, msg_time, msg, ts))
                conn.commit(); conn.close()
                client_conn.sendall(b"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except Exception as e:
                with print_lock: print(f"❌ POST /api/message 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== POST /api/upload_log =====
        elif "POST /api/upload_log" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                query = urlparse(path_with_query).query
                params = parse_qs(query)
                token = params.get("token", [""])[0]
                device_id = params.get("device_id", ["unknown"])[0]
                if token != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
                    return
                try: log_data = gzip.decompress(body_part)
                except Exception: log_data = body_part
                ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S_%f")
                safe_id = "".join(c for c in device_id if c.isalnum() or c in "_-")
                filename = f"{safe_id}_{ts}.log"
                filepath = os.path.join(UPLOAD_LOG_DIR, filename)
                with open(filepath, "wb") as f: f.write(log_data)
                with print_lock: print(f"\n📋 收到日志上传: {device_id} -> {filename} ({len(log_data)} bytes)")
                resp = json.dumps({"status": "ok", "filename": filename, "size": len(log_data)})
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode() + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ upload_log 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/logs =====
        elif "GET /api/logs" in header_text:
            try:
                files = []
                if os.path.isdir(UPLOAD_LOG_DIR):
                    for fn in sorted(os.listdir(UPLOAD_LOG_DIR), reverse=True):
                        if fn.endswith(".log"):
                            fp = os.path.join(UPLOAD_LOG_DIR, fn)
                            files.append({"filename": fn, "size": os.path.getsize(fp), "mtime": int(os.path.getmtime(fp) * 1000)})
                resp = json.dumps(files); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode() + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/logs 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/download_log =====
        elif "GET /api/download_log" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                name = params.get("name", [""])[0]
                safe_name = os.path.basename(name)
                filepath = os.path.join(UPLOAD_LOG_DIR, safe_name)
                if not os.path.isfile(filepath):
                    client_conn.sendall(b"HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
                    return
                with open(filepath, "rb") as f: content = f.read()
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Disposition: attachment; filename=\"{safe_name}\"\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(content)}\r\nConnection: close\r\n\r\n".encode() + content)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/download_log 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/cloud_logs =====
        elif "GET /api/cloud_logs" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                dev_id = params.get("device_id", [""])[0]
                with print_lock:
                    info = ALL_DEVICES_STATUS.get(dev_id, {})
                    resp = json.dumps({"cloud_logs": info.get("cloud_logs", ""), "cloud_logs_time": info.get("cloud_logs_time", 0)})
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/cloud_logs 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/cmd_status =====
        elif "GET /api/cmd_status" in header_text:
            try:
                with print_lock:
                    devices_snapshot = {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
                now = time.time()
                result = {}
                for dev_id, info in devices_snapshot.items():
                    queue = get_pending_commands(dev_id)
                    last_seen = info.get("received_at", 0) / 1000.0
                    ip = (info.get("source_ip") or info.get("udp_source_ip") or (info.get("network") or {}).get("local_ip", ""))
                    battery = info.get("battery") or {}
                    batt_level = battery.get("level")
                    if batt_level == "?" or batt_level is None: batt_level = None
                    loc = info.get("location") or {}
                    loc_lat = loc.get("latitude")
                    loc_lng = loc.get("longitude")
                    loc_age = loc.get("age_s")
                    loc_obj = None
                    if loc_lat is not None and loc_lng is not None:
                        loc_obj = {"lat": loc_lat, "lng": loc_lng, "accuracy": loc.get("accuracy"), "age_s": loc_age if isinstance(loc_age, int) else None}
                    
                    net = info.get("network") or {}
                    wifi_ssid = net.get("wifi_ssid") or ""
                    net_type = "离线"
                    if wifi_ssid and wifi_ssid not in ("UNKNOWN", "<unknown ssid>"): net_type = "WiFi"
                    elif ip and not ip.startswith("127.") and ip != "0.0.0.0":
                        net_type = "MOBILE" if ip.startswith("10.") else "WiFi"

                    result[dev_id] = {
                        "pending": len(queue),
                        "oldest_pending_s": max(0, int(now - queue[0]["ts"])) if queue else 0,
                        "last_report_ago_s": max(0, int(now - last_seen)) if last_seen > 0 else -1,
                        "ip": ip or "",
                        "battery": batt_level,
                        "is_charging": bool(battery.get("is_charging")),
                        "wifi_ssid": wifi_ssid,
                        "network_type": net_type,
                        "location": loc_obj,
                        "commands": [{"id": c["id"], "type": c["type"], "age_s": max(0, int(now - c["ts"]))} for c in queue[:10]],
                    }
                resp = json.dumps(result); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/cmd_status 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== POST /api/control =====
        elif "POST /api/control" in header_text:
            try:
                cmd_data = json.loads(body_part.decode('utf-8', errors='ignore'))
                action = cmd_data.get("action")
                payload = cmd_data.get("payload", "")
                target_ip = cmd_data.get("target_ip", "255.255.255.255")
                success = False
                extra_msg = ""

                target_devs = []
                if action in HTTP_SUPPORTED_ACTIONS:
                    target_devs = resolve_target_devices(target_ip)
                    for dev_id in target_devs:
                        enqueue_command(dev_id, action, payload)

                def smart_send(cmd_prefix):
                    sent_any = False
                    try:
                        local_ip = get_local_ip()
                        local_prefix = ".".join(local_ip.split(".")[:3])
                    except Exception: local_prefix = ""
                    if isinstance(target_ip, list):
                        for ip in target_ip:
                            if ip and ip != '0.0.0.0':
                                send_udp_broadcast(cmd_prefix, ip); sent_any = True
                    elif target_ip == "all" or target_ip == "255.255.255.255":
                        with print_lock:
                            devices_snapshot = {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
                        for dev_id, info in devices_snapshot.items():
                            ip = (info.get("source_ip") or info.get("udp_source_ip") or (info.get("network") or {}).get("local_ip"))
                            if not ip or ip == '0.0.0.0': continue
                            if local_prefix and ip.startswith(local_prefix + "."):
                                send_udp_broadcast(cmd_prefix, ip)
                            else:
                                send_udp_broadcast(cmd_prefix, "255.255.255.255")
                            sent_any = True
                        if not sent_any:
                            send_udp_broadcast(cmd_prefix, "255.255.255.255")
                    else:
                        send_udp_broadcast(cmd_prefix, target_ip); sent_any = True
                    return sent_any

                if action == "force_report": success = smart_send(f"FIND_ME_REPORT:{RESCUE_SECRET}")
                elif action == "play_alarm": success = smart_send(f"FIND_ME_ALARM:{RESCUE_SECRET}")
                elif action == "custom_msg": success = smart_send(f"FIND_ME_MSG:{RESCUE_SECRET}:{payload}")
                elif action == "chat_msg":
                    success = smart_send(f"FIND_ME_MSG:{RESCUE_SECRET}:Web_Console:{payload}")
                    if success:
                        ts = datetime.datetime.now().strftime("%H:%M:%S")
                        with print_lock:
                            CHAT_MESSAGES.append({"time": ts, "sender": "Web_Console", "content": payload})
                            if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
                elif action == "change_server": success = smart_send(f"FIND_ME_SERVER:{RESCUE_SECRET}:{payload}")
                elif action == "clear_history": success = smart_send(f"FIND_ME_CLEAR_HISTORY:{RESCUE_SECRET}")
                elif action == "clear_chat": success = smart_send(f"FIND_ME_CLEAR_CHAT:{RESCUE_SECRET}")
                elif action == "lock_chat": success = smart_send(f"FIND_ME_CHAT_LOCK:{RESCUE_SECRET}")
                elif action == "unlock_chat": success = smart_send(f"FIND_ME_CHAT_UNLOCK:{RESCUE_SECRET}")
                elif action == "set_sms_num": success = smart_send(f"FIND_ME_SET_SMS_NUM:{RESCUE_SECRET}:{payload}")
                elif action == "set_cloud_log": success = smart_send(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:{payload}")
                elif action == "upload_log": success = smart_send(f"FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}")
                elif action == "locate_once": success = smart_send(f"FIND_ME_LOCATE_ONCE:{RESCUE_SECRET}")
                elif action == "scan_wifi": success = smart_send(f"FIND_ME_SCAN_WIFI:{RESCUE_SECRET}")
                elif action == "restart_udp": success = smart_send(f"FIND_ME_RESTART_UDP:{RESCUE_SECRET}")
                elif action == "test_low_battery": success = smart_send(f"FIND_ME_TEST_LOW_BATTERY:{RESCUE_SECRET}")

                elif action == "clean_device":
                    target_dev_id = payload
                    if not isinstance(target_dev_id, str) or not target_dev_id.strip():
                        success = False; extra_msg = "无效的设备 ID"
                    else:
                        target_dev_id = target_dev_id.strip()
                        with print_lock:
                            existed = target_dev_id in ALL_DEVICES_STATUS
                            ALL_DEVICES_STATUS.pop(target_dev_id, None)
                        with _cmd_lock:
                            PENDING_COMMANDS.pop(target_dev_id, None)
                        success = existed
                        extra_msg = f"已删除 {target_dev_id}" if existed else f"设备 {target_dev_id} 不存在（可能已清理）"

                elif action == "clean_offline":
                    try:
                        now_ts = int(datetime.datetime.now().timestamp() * 1000)
                        to_delete = []
                        with print_lock:
                            for k, v in list(ALL_DEVICES_STATUS.items()):
                                if now_ts - v.get('received_at', 0) > CLEAN_OFFLINE_THRESHOLD_MS:
                                    to_delete.append(k)
                            for k in to_delete:
                                if k in ALL_DEVICES_STATUS:
                                    del ALL_DEVICES_STATUS[k]
                        with _cmd_lock:
                            for k in to_delete:
                                PENDING_COMMANDS.pop(k, None)
                        success = len(to_delete) > 0
                        threshold_h = CLEAN_OFFLINE_THRESHOLD_MS // 3600000
                        extra_msg = f"清理了 {len(to_delete)} 台设备（>{threshold_h}小时未活动）" if to_delete else f"没有符合条件的设备（>{threshold_h}小时未活动）"
                    except Exception as e:
                        success = False; extra_msg = f"清理失败: {e}"

                queued_count = len(target_devs) if action in HTTP_SUPPORTED_ACTIONS else 0
                if extra_msg:
                    resp = json.dumps({"status": "ok" if success else "error", "msg": extra_msg})
                elif queued_count > 0 and not success:
                    resp = json.dumps({"status": "ok", "msg": f"UDP 未确认送达，已排队 {queued_count} 条，等手机下次上报时下发"})
                else:
                    resp = json.dumps({"status": "ok" if success else "error", "msg": "" if success else "指令执行失败"})
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ POST /api/control 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/history =====
        elif "GET /api/history" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                dev_id = params.get("device_id", [""])[0]
                conn = sqlite3.connect('track_history.db', timeout=10)
                c = conn.cursor()
                c.execute("SELECT lat, lng, timestamp FROM history WHERE device_id=? ORDER BY timestamp DESC LIMIT 50", (dev_id,))
                rows = c.fetchall(); conn.close()
                resp = json.dumps(rows); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/history 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/messages =====
        elif "GET /api/messages" in header_text:
            try:
                conn = sqlite3.connect('track_history.db', timeout=10)
                c = conn.cursor()
                c.execute("SELECT device_id, msg_time, message FROM messages ORDER BY timestamp DESC LIMIT 50")
                rows = c.fetchall(); conn.close()
                resp = json.dumps(rows); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/messages 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/chat_history =====
        elif "GET /api/chat_history" in header_text:
            try:
                with print_lock:
                    devices_snapshot = {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
                devices_list = []
                for dev_id, info in devices_snapshot.items():
                    ip = (info.get("source_ip") or info.get("udp_source_ip") or (info.get("network") or {}).get("local_ip"))
                    if ip and ip != '0.0.0.0':
                        devices_list.append({"id": dev_id, "ip": ip})
                data_to_send = {"messages": CHAT_MESSAGES, "devices": devices_list}
                with print_lock: resp = json.dumps(data_to_send)
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/chat_history 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET / (Web 控制台) =====
        elif "GET /" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                if path != "/" and path != "/index.html" and not path.startswith("/?"):
                    client_conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return

                chat_options = '<label class="device-tag"><input type="checkbox" class="target-cb" value="all" checked><span>所有人 (群发)</span></label>'
                now_ms = int(datetime.datetime.now().timestamp() * 1000)

                html = """<!DOCTYPE html>
<html><head><meta charset='utf-8'>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>终端设备管理</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
:root {
    --color-canvas-default: #ffffff; --color-canvas-subtle: #f6f8fa;
    --color-border-default: #d0d7de; --color-border-muted: #d8dee4;
    --color-fg-default: #24292f; --color-fg-muted: #57606a;
    --color-accent-fg: #0969da; --color-btn-bg: #f6f8fa;
    --color-btn-primary-bg: #24292f; --color-btn-primary-hover-bg: #1b1f24;
}
* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; margin: 24px; background: var(--color-canvas-subtle); color: var(--color-fg-default); }
h2 { font-size: 20px; font-weight: 600; margin-bottom: 20px; }
.panel { background: var(--color-canvas-default); padding: 16px; border-radius: 6px; border: 1px solid var(--color-border-default); margin-bottom: 24px; }
button { padding: 5px 16px; font-size: 14px; font-weight: 500; line-height: 20px; white-space: nowrap; cursor: pointer; border-radius: 6px; border: 1px solid var(--color-border-default); background-color: var(--color-btn-bg); color: var(--color-fg-default); transition: 0.2s; }
button:hover { background-color: var(--color-border-muted); }
.btn-primary { background-color: var(--color-btn-primary-bg); color: #fff; border-color: var(--color-btn-primary-bg); }
.btn-primary:hover { background-color: var(--color-btn-primary-hover-bg); }
.btn-danger { background-color: #fff; color: #cf222e; border-color: var(--color-border-default); }
.btn-danger:hover { background-color: #cf222e; color: #fff; border-color: #cf222e; }
button.btn-sm { padding: 3px 12px; font-size: 12px; line-height: 18px; }
input[type="text"] { padding: 5px 12px; font-size: 14px; line-height: 20px; border: 1px solid var(--color-border-default); border-radius: 6px; transition: border-color 0.2s, box-shadow 0.2s; }
input[type="text"]:focus { border-color: var(--color-accent-fg); box-shadow: 0 0 0 3px rgba(9,105,218,0.3); }
.table-responsive { width: 100%; overflow-x: auto; background: var(--color-canvas-default); border-radius: 6px; border: 1px solid var(--color-border-default); }
table { border-collapse: collapse; width: 100%; min-width: 900px; }
th, td { border-bottom: 1px solid var(--color-border-muted); padding: 12px 16px; text-align: left; font-size: 14px; vertical-align: middle; }
th { background-color: var(--color-canvas-subtle); font-weight: 600; }
tr:last-child td { border-bottom: none; }
tr:hover { background-color: var(--color-canvas-subtle); }
a { color: var(--color-accent-fg); text-decoration: none; }
details { cursor: pointer; font-weight: 600; user-select: none; }
details summary::-webkit-details-marker { display: none; }
details summary { display: flex; align-items: center; }
details summary::before { content: '▶'; font-size: 10px; margin-right: 8px; transition: transform 0.2s; }
details[open] summary::before { transform: rotate(90deg); }
.btn-group { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.input-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 12px; }
.input-row input[type="text"] { flex: 1; min-width: 200px; }
#mapContainer { display: none; margin-top: 24px; }
#map { height: 500px; width: 100%; border-radius: 6px; border: 1px solid var(--color-border-default); min-height: 300px; }
#msgModal { display: none; position: fixed; z-index: 9999; left: 0; top: 0; width: 100%; height: 100%; background-color: rgba(0,0,0,0.5); }
#msgContent { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); background: var(--color-canvas-default); border-radius: 6px; border: 1px solid var(--color-border-default); width: 90%; max-width: 720px; max-height: 80%; overflow-y: auto; }
.modal-header { padding: 16px; border-bottom: 1px solid var(--color-border-muted); display: flex; justify-content: space-between; }
.modal-body { padding: 16px; line-height: 1.6; font-size: 14px; }
#toast { visibility: hidden; min-width: 250px; background-color: #24292f; color: #fff; text-align: center; border-radius: 6px; padding: 12px; position: fixed; z-index: 10000; left: 50%; bottom: 30px; transform: translateX(-50%); font-size: 14px; opacity: 0; transition: 0.3s; }
#toast.show { visibility: visible; opacity: 1; bottom: 50px; }

td button.btn-sm { display: inline-block; width: 32%; margin-bottom: 6px; margin-right: 1.5%; padding: 5px 0; text-align: center; box-sizing: border-box; font-size: 12px; white-space: nowrap; }
td button.btn-sm:nth-child(3n) { margin-right: 0; }

#chatBox { display: none; margin-top: 24px; background: #fff; border-radius: 6px; border: 1px solid var(--color-border-default); flex-direction: column; height: 450px; }
#chatMessages { flex: 1; overflow-y: auto; padding: 16px; font-size: 14px; line-height: 1.5; }
.chat-msg { margin-bottom: 8px; }
.chat-time { color: #8c959f; font-size: 12px; margin-right: 8px; }
.chat-sender { color: #0969da; font-weight: 600; margin-right: 8px; }
.chat-content { background: #f0f3f6; padding: 6px 12px; border-radius: 6px; display: inline-block; word-break: break-all; }
.chat-input-area { display: flex; padding: 12px; border-top: 1px solid var(--color-border-muted); background: #f6f8fa; gap: 8px; flex-wrap: wrap; }
.chat-input-area input { flex: 1; min-width: 200px; margin: 0; }
.chat-input-area button { margin: 0; }

.device-selector { display: flex; flex-wrap: wrap; gap: 6px; padding: 12px; border-top: 1px solid var(--color-border-muted); background: #fff; }
.device-tag { position: relative; cursor: pointer; }
.device-tag input { position: absolute; opacity: 0; cursor: pointer; height: 0; width: 0; }
.device-tag span { display: inline-block; padding: 4px 10px; font-size: 12px; border-radius: 20px; border: 1px solid var(--color-border-default); background: #fff; color: var(--color-fg-default); transition: 0.2s; user-select: none; }
.device-tag input:checked ~ span { background: #24292f; color: #fff; border-color: #24292f; box-shadow: inset 0 1px 3px rgba(0,0,0,0.5); }

@media (max-width: 768px) {
    body { margin: 12px; }
    .input-row { flex-direction: column; align-items: stretch; }
    .input-row button { width: 100%; padding: 8px; margin-top: 4px; }
    .quick-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
    .quick-actions button { width: 100%; padding: 8px 4px; text-align: center; }
    .btn-group { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .btn-group button { width: 100%; text-align: center; padding: 8px 4px; font-size: 13px;}
    .hide-mobile { display: none !important; }
    .chat-input-area input { min-width: 100px; }
}
</style>
</head>
<body>
<h2>📱 终端设备状态同步中心</h2>
<div class="panel">
    <div class="input-row" style="margin-top: 0;">
        <input type="text" id="msgText" placeholder="输入要群发到所有手机屏幕上的文字...">
        <button class="btn-primary" onclick="sendScreenMsg('255.255.255.255')">发送群发提示</button>
    </div>
    <div class="quick-actions">
        <button class="btn-primary" onclick="sendCmd('force_report')">全局同步状态</button>
        <button onclick="showServerMessages()">查看云端留言</button>
        <button onclick="toggleChatBox()" style="color:#0969da; border-color:#0969da;">🗣️ 局域网对讲机</button>
    </div>
    <details style="margin-top: 16px; border-top: 1px solid var(--color-border-muted); padding-top: 12px;">
        <summary>⚙️ 高级设置与系统工具</summary>
        <div class="btn-group" style="margin-top: 12px;">
            <button onclick="sendCmd('lock_chat')">🔒 锁定聊天</button>
            <button onclick="sendCmd('unlock_chat')">🔓 解锁聊天</button>
            <button onclick="sendCmd('set_cloud_log', '1')">☁️ 开启云端日志</button>
            <button onclick="sendCmd('set_cloud_log', '0')">☁️ 关闭云端日志</button>
            <button class="btn-danger" onclick="sendCmd('play_alarm')">全局测试音频</button>
            <button id="toggleMapBtn" onclick="toggleMap()">🗺️ 展开地图</button>
            <button onclick="showOfflineManager()">🗑️ 管理离线设备</button>
            <button onclick="sendCmd('clear_history')">清空手机历史</button>
            <button class="btn-danger" onclick="sendCmd('clear_chat')">🧹 清空手机聊天</button>
        </div>
        <div class="btn-group" style="margin-top: 12px;">
            <button onclick="sendCmd('upload_log')">📤 拉取日志(主服务器)</button>
            <button onclick="showLogs()">📋 查看手机日志</button>
            <button onclick="sendCmd('locate_once')">📍 手动定位一次</button>
            <button onclick="sendCmd('scan_wifi')">📡 手动扫描 WiFi</button>
            <button onclick="sendCmd('restart_udp')">🔄 重启 UDP 监听</button>
            <button class="btn-danger" onclick="sendCmd('test_low_battery')">🧪 模拟低电量</button>
        </div>
        <div class="input-row" style="margin-top: 12px;">
            <input type="text" id="smsNumText" placeholder="输入紧急接收短信的手机号...">
            <button onclick="sendSmsNum('255.255.255.255')">📱 设置报警号码</button>
        </div>
    </details>
</div>

<div id="chatBox">
    <div class="device-selector" id="deviceSelector">
        """ + chat_options + """
    </div>
    <div id="chatMessages"></div>
    <div class="chat-input-area">
        <input type="text" id="chatInput" placeholder="输入消息..." onkeypress="handleChatEnter(event)">
        <button class="btn-primary" onclick="sendChat()">发送</button>
    </div>
</div>

<div class="table-responsive">
    <table>
        <tr>
            <th>设备ID</th>
            <th>系统 / 状态</th>
            <th>电量</th>
            <th>Wi-Fi / IP</th>
            <th>经纬度</th>
            <th class="hide-mobile">定位时间</th>
            <th>收到时间</th>
            <th class="hide-mobile">周围热点</th>
            <th style="min-width: 180px;">操作</th>
        </tr>
"""
                for dev_id, info in list(ALL_DEVICES_STATUS.items()):
                    try:
                        network = info.get("network") or {}
                        battery = info.get("battery") or {}
                        location = info.get("location") or {}
                        
                        os_ver = info.get('os_version', 'Unknown').replace("Android ", "A")
                        screen_on = "亮屏" if info.get('is_screen_on') else "息屏"

                        is_locked = info.get('chat_locked', False)
                        chat_lock_str = "<span style='color:#cf222e;'>🔒 已锁</span>" if is_locked else "<span style='color:#2da44e;'>🔓 未锁</span>"

                        last_seen = info.get('received_at', 0)
                        gap_min = (now_ms - last_seen) // 60000 if last_seen else 99999
                        if gap_min > 120:
                            offline_tag = f"<br><span style='color:#cf222e;font-weight:bold;'>🚨 失联 {gap_min // 60}h{gap_min % 60}m</span>"
                        elif gap_min > 30:
                            offline_tag = f"<br><span style='color:#bf8700;'>⏸ {gap_min} 分钟未上报</span>"
                        else:
                            offline_tag = ""

                        sys_status = f"{os_ver} <br><span style='font-size:12px;color:var(--color-fg-muted);'>{screen_on} | {chat_lock_str}</span>{offline_tag}"

                        lat = location.get('latitude')
                        lng = location.get('longitude')
                        age_s = location.get('age_s')
                        if lat is not None and lng is not None:
                            coord = f"<a href='https://uri.amap.com/marker?position={lng},{lat}' target='_blank'>{lat:.6f}, {lng:.6f}</a>"
                            if age_s is not None and age_s >= 0:
                                if age_s >= 1800:
                                    coord += f"<br><span style='color:#cf222e;font-size:12px;font-weight:bold;'>🚨 旧 {age_s // 60}min</span>"
                                elif age_s >= 300:
                                    coord += f"<br><span style='color:#bf8700;font-size:12px;'>⚠️ 旧 {age_s // 60}min</span>"
                                elif age_s >= 60:
                                    coord += f"<br><span style='color:#57606a;font-size:12px;'>旧 {age_s // 60}min</span>"
                        else:
                            coord = "<span style='color:var(--color-fg-muted);'>无定位</span>"

                        ssid = network.get('wifi_ssid', '?')
                        source_ip = (info.get("source_ip") or info.get("udp_source_ip") or network.get("local_ip", "0.0.0.0"))
                        local_ip = network.get("local_ip", "0.0.0.0")
                        if source_ip and local_ip and source_ip != local_ip:
                            ip_display = f"<span title='PC 视角: {source_ip}  |  手机自报: {local_ip}' style='color:#0969da;'>{source_ip} ⚠️</span>"
                        else:
                            ip_display = f"<span title='PC 视角: {source_ip}' style='color:var(--color-fg-muted);'>{source_ip}</span>"
                        wifi_info = f"{ssid}<br><span style='font-size:12px;'>{ip_display}</span>"

                        nearby = info.get('nearby_wifi') or []
                        ap_list_html = ""
                        if nearby:
                            ap_list_html = "<details><summary><b>" + str(len(nearby)) + " 个</b></summary><ul>"
                            for ap in nearby:
                                ap_list_html += f"<li>{ap.get('ssid')} ({ap.get('rssi')} dBm)</li>"
                            ap_list_html += "</ul></details>"
                        else:
                            ap_list_html = "<span style='color:var(--color-fg-muted);'>0 个</span>"

                        html += f"<tr><td><b>{dev_id}</b></td>"
                        html += f"<td>{sys_status}</td>"
                        html += f"<td>{battery.get('level', '?')}%</td>"
                        html += f"<td>{wifi_info}</td>"
                        html += f"<td>{coord}</td>"
                        html += f"<td class='hide-mobile'>{format_loc_time(location.get('time', 0))}</td>"
                        html += f"<td>{format_loc_time(info.get('received_at', 0))}</td>"
                        html += f"<td class='hide-mobile'>{ap_list_html}</td>"

                        html += f"<td>"
                        btn_ip = source_ip if source_ip and source_ip != "0.0.0.0" else local_ip
                        html += f"<button class='btn-sm' onclick=\"sendCmd('force_report', '', '{btn_ip}')\">同步</button>"
                        html += f"<button class='btn-sm' onclick=\"sendScreenMsg('{btn_ip}')\">提示</button>"
                        html += f"<button class='btn-sm btn-danger' onclick=\"sendCmd('play_alarm', '', '{btn_ip}')\">响铃</button>"

                        if is_locked:
                            html += f"<button class='btn-sm btn-warning' onclick=\"sendCmd('unlock_chat', '', '{btn_ip}')\">🔓 解锁</button>"
                        else:
                            html += f"<button class='btn-sm' onclick=\"sendCmd('lock_chat', '', '{btn_ip}')\">🔒 锁定</button>"

                        html += f"<button class='btn-sm' onclick=\"showTrack('{dev_id}')\">轨迹</button>"
                        html += f"<button class='btn-sm' onclick=\"showCloudLogs('{dev_id}')\">📄 日志</button>"
                        html += f"</td></tr>"
                    except Exception as e:
                        with print_lock:
                            print(f"⚠️ [HTML] 渲染设备 {dev_id} 异常: {e}")
                        html += f"<tr><td colspan='9' style='color:#cf222e;'>设备 {dev_id} 数据异常，已跳过显示 ({e})</td></tr>"

                if not ALL_DEVICES_STATUS:
                    html += "<tr><td colspan='9' align='center' style='padding:40px;color:var(--color-fg-muted);'>暂无设备上报</td></tr>"

                html += """</table></div>
<div id="mapContainer">
    <div id="map"></div>
</div>
<div id="msgModal">
    <div id="msgContent">
        <div class="modal-header">
            <h3 id="modalTitle">📜 云端留言备份</h3>
            <span class="close-btn" style="cursor:pointer" onclick="document.getElementById('msgModal').style.display='none'">✖</span>
        </div>
        <div class="modal-body" id="msgList"></div>
    </div>
</div>
<div id="toast">提示信息</div>
<script>
var map = null;
var markersLayer = null;
var polyline = null;
var isMapVisible = false;
var isChatVisible = false;
var _offlineDevices = [];

var TIANDITU_KEY = "e669a4b8bc516a2d6e0104f746069b5a"; // 填入你的天地图 Key
var currentMapType = "tianditu";

var PI = 3.1415926535897932384626;
var _a = 6378245.0;
var _ee = 0.00669342162296594323;

function outOfChina(lng, lat) {
    return (lng < 72.004 || lng > 137.8347) || (lat < 0.8293 || lat > 55.8271);
}
function transformLat(lng, lat) {
    var ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
    ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
    ret += (20.0 * Math.sin(lat * PI) + 40.0 * Math.sin(lat / 3.0 * PI)) * 2.0 / 3.0;
    ret += (160.0 * Math.sin(lat / 12.0 * PI) + 320 * Math.sin(lat * PI / 30.0)) * 2.0 / 3.0;
    return ret;
}
function transformLng(lng, lat) {
    var ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
    ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
    ret += (20.0 * Math.sin(lng * PI) + 40.0 * Math.sin(lng / 3.0 * PI)) * 2.0 / 3.0;
    ret += (150.0 * Math.sin(lng / 12.0 * PI) + 300.0 * Math.sin(lng / 30.0 * PI)) * 2.0 / 3.0;
    return ret;
}
function wgs84togcj02(lng, lat) {
    if (outOfChina(lng, lat)) return [lng, lat];
    var dlat = transformLat(lng - 105.0, lat - 35.0);
    var dlng = transformLng(lng - 105.0, lat - 35.0);
    var radlat = lat / 180.0 * PI;
    var magic = Math.sin(radlat);
    magic = 1 - _ee * magic * magic;
    var sqrtmagic = Math.sqrt(magic);
    dlat = (dlat * 180.0) / ((_a * (1 - _ee)) / (magic * sqrtmagic) * PI);
    dlng = (dlng * 180.0) / (_a / sqrtmagic * Math.cos(radlat) * PI);
    return [lng + dlng, lat + dlat];
}

function initMap() {
    map = L.map('map', { center: [39.9, 116.4], zoom: 13, zoomControl: true });
    var layers = {};
    var defaultLayer = null;

    // 1. 天地图 (WGS-84，无偏移)
    if (TIANDITU_KEY) {
        var tdtUrl = function(layer) {
            return 'https://t{s}.tianditu.gov.cn/' + layer + '_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=' + layer + '&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=' + TIANDITU_KEY;
        };
        var tdtGroup = L.layerGroup([
            L.tileLayer(tdtUrl('vec'), { subdomains: '01234567', maxZoom: 18 }),
            L.tileLayer(tdtUrl('cva'), { subdomains: '01234567', maxZoom: 18 })
        ]);
        layers['1. 天地图（无偏移）'] = tdtGroup;
        defaultLayer = tdtGroup;
        currentMapType = "tianditu";
    }

    // 2. 高德地图 (GCJ-02，需自动纠偏)
    var amapVec = L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
        subdomains: '1234', 
        maxZoom: 18,
        attribution: '© 高德'
    });
    layers['2. 高德矢量（已纠偏）'] = amapVec;
    
    // 如果没填天地图 Key，默认使用高德（保证国内访问速度）
    if (!defaultLayer) {
        defaultLayer = amapVec;
        currentMapType = "amap";
    }

    // 3. 谷歌地图 (.com 国际版，WGS-84，无偏移，需网络支持)
    var googleVec = L.tileLayer('https://mt{s}.google.com/vt/lyrs=m&hl=zh-CN&gl=cn&x={x}&y={y}&z={z}', {
        subdomains: '0123', 
        maxZoom: 18,
        attribution: '© Google'
    });
    layers['3. 谷歌地图（无偏移）'] = googleVec;

    // 把默认图层加到地图上
    defaultLayer.addTo(map);

    // 右上角图层切换控件
    L.control.layers(layers, null, { position: 'topright', collapsed: false }).addTo(map);

    // 监听图层切换事件
    map.on('baselayerchange', function(e) {
        if (e.name.indexOf('高德') !== -1) {
            currentMapType = "amap";
        } else if (e.name.indexOf('谷歌') !== -1) {
            currentMapType = "google";
        } else {
            currentMapType = "tianditu";
        }
        
        // 切换底图时，自动重绘轨迹并根据新的底图类型重新计算坐标
        if (window._currentTrackData) {
            renderTrack(window._currentTrackData);
        }
    });

    markersLayer = L.layerGroup().addTo(map);
    setTimeout(function() { map.invalidateSize(); }, 100);
}

function toggleMap() {
    var container = document.getElementById('mapContainer');
    var btn = document.getElementById('toggleMapBtn');
    if (isMapVisible) {
        container.style.display = 'none';
        btn.innerHTML = '🗺️ 展开地图';
    } else {
        container.style.display = 'block';
        btn.innerHTML = '⬆️ 收起地图';
        if (!map) {
            requestAnimationFrame(function() { initMap(); });
        } else {
            setTimeout(function() { map.invalidateSize(); }, 100);
        }
    }
    isMapVisible = !isMapVisible;
}

function showToast(msg) {
    var toast = document.getElementById("toast");
    toast.innerText = msg;
    toast.className = "show";
    setTimeout(function(){ toast.className = toast.className.replace("show", ""); }, 3000);
}

function toggleChatBox() {
    var chatBox = document.getElementById('chatBox');
    isChatVisible = !isChatVisible;
    if (isChatVisible) {
        chatBox.style.display = 'flex';
        fetchChatHistory();
    } else {
        chatBox.style.display = 'none';
    }
}

function handleChatEnter(e) { if (e.key === 'Enter') sendChat(); }

function sendChat() {
    var msg = document.getElementById('chatInput').value.trim();
    if(!msg) return;
    var selectedIps = [];
    var cbs = document.querySelectorAll('.target-cb:checked');
    for (var i = 0; i < cbs.length; i++) { selectedIps.push(cbs[i].value); }
    if(selectedIps.length === 0) return showToast("请至少选择一个发送目标！");
    var targetParam = selectedIps.indexOf("all") !== -1 ? "all" : selectedIps;
    document.getElementById('chatInput').value = "";
    
    fetch('/api/control', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({action: 'chat_msg', payload: msg, target_ip: targetParam})
    }).then(function() {
        setTimeout(fetchChatHistory, 300);
    });
}

function fetchChatHistory() {
    if (!isChatVisible) return;
    fetch('/api/chat_history')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var chatDiv = document.getElementById('chatMessages');
        var html = "";
        data.messages.forEach(function(item) {
            var senderColor = item.sender.indexOf("Web_Console") !== -1 ? "#cf222e" : "#0969da";
            html += '<div class="chat-msg">' +
                '<span class="chat-time">' + item.time + '</span>' +
                '<span class="chat-sender" style="color:' + senderColor + '">[' + item.sender + ']</span>' +
                '<span class="chat-content">' + item.content + '</span>' +
            '</div>';
        });
        if (chatDiv.innerHTML !== html) {
            chatDiv.innerHTML = html;
            chatDiv.scrollTop = chatDiv.scrollHeight;
        }
        var selectorDiv = document.getElementById('deviceSelector');
        var currentChecked = [];
        var cbs = document.querySelectorAll('.target-cb:checked');
        for (var i = 0; i < cbs.length; i++) { currentChecked.push(cbs[i].value); }

        var tagHtml = '<label class="device-tag"><input type="checkbox" class="target-cb" value="all" ' + (currentChecked.indexOf('all') !== -1 || currentChecked.length === 0 ? 'checked' : '') + '><span>所有人 (群发)</span></label>';
        data.devices.forEach(function(dev) {
            var isChecked = currentChecked.indexOf(dev.ip) !== -1 ? 'checked' : '';
            tagHtml += '<label class="device-tag"><input type="checkbox" class="target-cb" value="' + dev.ip + '" ' + isChecked + '><span>' + dev.id + '</span></label>';
        });
        if (selectorDiv.innerHTML !== tagHtml) { selectorDiv.innerHTML = tagHtml; }
    }).catch(function(e) {});
    if (isChatVisible) setTimeout(fetchChatHistory, 2000);
}

function showTrack(devId) {
    if (!isMapVisible) toggleMap();
    fetch('/api/history?device_id=' + encodeURIComponent(devId))
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.length === 0) return showToast("暂无该设备的轨迹数据");
        window._currentTrackData = data.reverse();
        setTimeout(function() {
            renderTrack(window._currentTrackData);
        }, 250);
    });
}

function renderTrack(data) {
    if (!map || !markersLayer) return;
    markersLayer.clearLayers();
    if (polyline) map.removeLayer(polyline);
    
    var latlngs = [];
    data.forEach(function(row, index) {
        var lat = row[0], lng = row[1];
        
        // 核心逻辑：只有高德地图需要 GCJ-02 纠偏；天地图和谷歌地图直接用原坐标
        if (currentMapType === "amap") {
            var converted = wgs84togcj02(lng, lat);
            lng = converted[0];
            lat = converted[1];
        }
        
        latlngs.push([lat, lng]);
        if (index === data.length - 1) {
            L.marker([lat, lng]).addTo(markersLayer).bindPopup("最新位置").openPopup();
            map.setView([lat, lng], 16);
        }
    });
    polyline = L.polyline(latlngs, {color: '#0969da', weight: 4}).addTo(map);
    map.invalidateSize();
}

function showCloudLogs(devId) {
    fetch('/api/cloud_logs?device_id=' + encodeURIComponent(devId))
    .then(function(res) { return res.json(); })
    .then(function(info) {
        var logs = (info && info.cloud_logs) ? info.cloud_logs : "该设备尚未上传日志。\\n请先在【高级设置】中开启「云端日志」，并在手机端等待下一次上报。";
        var logTime = (info && info.cloud_logs_time) ? new Date(info.cloud_logs_time).toLocaleString() : "未知";
        document.getElementById('modalTitle').innerText = "📄 运行日志 - " + devId;
        document.getElementById('msgList').innerHTML = 
            '<div style="color:#57606a; margin-bottom: 10px; font-size: 13px;">最近快照时间: ' + logTime + '</div>' +
            '<pre style="background: #f6f8fa; padding: 12px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; border: 1px solid #d0d7de; color: #24292f; max-height: 400px; overflow-y: auto;">' + logs + '</pre>';
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取日志失败: " + e.message); });
}

function showLogs() {
    fetch('/api/logs')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var listDiv = document.getElementById('msgList');
        document.getElementById('modalTitle').innerText = "📋 手机上传日志列表";
        if (data.length === 0) {
            listDiv.innerHTML = "<p style='color:var(--color-fg-muted);'>还没有上传过日志。请点「📤 拉取日志」让手机上传。</p>";
        } else {
            var html = "";
            data.forEach(function(f) {
                var sizeKB = (f.size / 1024).toFixed(1);
                var time = new Date(f.mtime).toLocaleString();
                html += '<div style="border-bottom:1px solid var(--color-border-muted); padding:12px 0;">' +
                    '<div style="color:var(--color-fg-muted); font-size:12px; margin-bottom:6px;">' + time + ' | ' + sizeKB + 'KB</div>' +
                    '<a href="/api/download_log?name=' + encodeURIComponent(f.filename) + '" target="_blank" style="color:#0969da; font-weight:600;">' + f.filename + '</a>' +
                '</div>';
            });
            listDiv.innerHTML = html;
        }
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取日志列表失败: " + e.message); });
}

function sendCmd(action, payload, target_ip) {
    if (payload === undefined) payload = "";
    if (target_ip === undefined) target_ip = "255.255.255.255";
    
    fetch('/api/control', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({action: action, payload: payload, target_ip: target_ip})
    })
    .then(function(res) {
        if (!res.ok) throw new Error('HTTP error! status: ' + res.status);
        return res.text();
    })
    .then(function(text) {
        var data = JSON.parse(text);
        if(data.status === 'ok') {
            var msg = data.msg || "✅ 指令发送成功";
            showToast(msg);
            if (action === 'clean_device' || action === 'clean_offline') {
                if (document.getElementById('msgModal').style.display === 'block') {
                    setTimeout(showOfflineManager, 300);
                }
            } else if (action !== "chat_msg" && action !== "upload_log") {
                setTimeout(function() { location.reload(); }, 1500);
            }
        } else {
            showToast("❌ 失败: " + (data.msg || "未知错误"));
        }
    }).catch(function(e) { showToast("❌ 请求失败: " + e.message); });
}

function sendScreenMsg(target_ip) {
    var msg = document.getElementById('msgText').value;
    if(!msg) { showToast("请先在输入框输入文字！"); return; }
    sendCmd('custom_msg', msg, target_ip);
}

function sendSmsNum(target_ip) {
    var num = document.getElementById('smsNumText').value.trim();
    if(!num) { showToast("请先在输入框输入手机号！"); return; }
    sendCmd('set_sms_num', num, target_ip);
}

function showServerMessages() {
    fetch('/api/messages')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var listDiv = document.getElementById('msgList');
        document.getElementById('modalTitle').innerText = "📜 云端留言备份";
        if(data.length === 0) {
            listDiv.innerHTML = "<p style='color:var(--color-fg-muted);'>服务器还没有记录哦~</p>";
        } else {
            var html = "";
            data.forEach(function(item) {
                html += '<div style="border-bottom:1px solid var(--color-border-muted); padding:12px 0;">' +
                            '<div style="color:var(--color-fg-muted); font-size:12px; margin-bottom:6px;">[' + item[1] + '] - ' + item[0] + '</div>' +
                            '<div>' + item[2] + '</div>' +
                         '</div>';
            });
            listDiv.innerHTML = html;
        }
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取云端数据失败: " + e.message); });
}

function showOfflineManager() {
    fetch('/api/cmd_status')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var threshold = 30 * 60;
        var offline = [];
        for (var devId in data) {
            var d = data[devId];
            var ago = d.last_report_ago_s;
            if (ago === -1 || ago > threshold) { offline.push({ id: devId, info: d }); }
        }
        if (offline.length === 0) { showToast("当前没有离线设备（>30 分钟未活动）"); return; }
        
        offline.sort(function(a, b) {
            var aa = a.info.last_report_ago_s === -1 ? 999999999 : a.info.last_report_ago_s;
            var bb = b.info.last_report_ago_s === -1 ? 999999999 : b.info.last_report_ago_s;
            return bb - aa;
        });
        _offlineDevices = offline;

        var html = '<div style="background:#fff8c5;border:1px solid #d4a72c;border-radius:6px;padding:10px 12px;margin-bottom:16px;font-size:13px;color:#6b5900;">' +
            '⚠️ <b>删除操作只从当前列表移除，不影响历史数据</b>（reports / history / messages 表全部保留）。<br>' +
            '手机重新上报后会自动回到列表。当前展示标准：<b>' + (threshold/60) + ' 分钟</b>未活动。' +
        '</div>';

        for (var i = 0; i < offline.length; i++) {
            var dev = offline[i];
            var info = dev.info;
            var ago = info.last_report_ago_s;
            var ago_str;
            if (ago === -1) ago_str = "从未上报";
            else if (ago > 86400) ago_str = Math.floor(ago/86400) + " 天前";
            else if (ago > 3600) ago_str = Math.floor(ago/3600) + " 小时前";
            else ago_str = Math.floor(ago/60) + " 分钟前";

            var ipStr = info.ip || "<span style='color:#8c959f;'>未知</span>";
            var battStr;
            if (info.battery === null || info.battery === undefined) { battStr = "<span style='color:#8c959f;'>未上报</span>"; }
            else { battStr = info.battery + "%" + (info.is_charging ? " ⚡" : ""); }
            
            var locStr;
            if (info.location && info.location.lat != null) {
                var loc = info.location;
                var ageTxt = loc.age_s != null ? ' (' + Math.floor(loc.age_s/60) + ' 分钟前)' : "";
                locStr = '<a href="https://uri.amap.com/marker?position=' + loc.lng + ',' + loc.lat + '" target="_blank" style="color:#0969da;">' + loc.lat.toFixed(5) + ', ' + loc.lng.toFixed(5) + '</a>' + ageTxt;
            } else { locStr = "<span style='color:#8c959f;'>无定位</span>"; }
            
            var wifiStr = info.wifi_ssid && info.wifi_ssid !== "UNKNOWN" ? info.wifi_ssid + " (" + (info.network_type || "?") + ")" : "<span style='color:#8c959f;'>无 WiFi</span>";
            var queueStr = info.pending > 0 ? '<span style="color:#bf8700;">📬 ' + info.pending + ' 条指令待执行</span>' : "";

            html += '<div style="border:1px solid var(--color-border-default);border-radius:6px;padding:12px 16px;margin-bottom:10px;background:#fafbfc;">' +
                '<div style="font-weight:600;font-size:15px;margin-bottom:6px;word-break:break-all;">' + escapeHtml(dev.id) + '</div>' +
                '<table style="font-size:13px;border:none;min-width:0;width:100%;">' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;width:80px;">最后活动</td><td style="padding:3px 0;border:none;">' + ago_str + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">上次 IP</td><td style="padding:3px 0;border:none;">' + ipStr + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">上次电量</td><td style="padding:3px 0;border:none;">' + battStr + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">上次定位</td><td style="padding:3px 0;border:none;">' + locStr + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">WiFi</td><td style="padding:3px 0;border:none;">' + wifiStr + '</td></tr>' +
                    (queueStr ? '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">队列</td><td style="padding:3px 0;border:none;">' + queueStr + '</td></tr>' : '') +
                '</table>' +
                '<div style="margin-top:10px;text-align:right;">' +
                    '<button class="btn-danger" onclick="cleanSingleDeviceByIndex(' + i + ')" style="padding:5px 16px;">🗑️ 删除此设备</button>' +
                '</div>' +
            '</div>';
        }

        html += '<div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--color-border-muted);text-align:right;">' +
            '<button class="btn-danger" onclick="confirmCleanAllOffline()" style="padding:6px 20px;">🗑️ 清理全部 >24h 离线设备</button>' +
        '</div>';

        document.getElementById('modalTitle').innerText = '🗑️ 管理离线设备（' + offline.length + ' 台）';
        document.getElementById('msgList').innerHTML = html;
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取设备状态失败: " + e.message); });
}

function cleanSingleDeviceByIndex(idx) {
    var dev = _offlineDevices[idx];
    if (!dev) return;
    var info = dev.info;
    var detailLines = ["设备ID: " + dev.id, "", "上次 IP: " + (info.ip || "未知")];
    if (info.battery !== null && info.battery !== undefined) detailLines.push("上次电量: " + info.battery + "%" + (info.is_charging ? " (充电中)" : ""));
    if (info.location && info.location.lat != null) detailLines.push("上次定位: " + info.location.lat.toFixed(5) + ", " + info.location.lng.toFixed(5));
    if (info.wifi_ssid && info.wifi_ssid !== "UNKNOWN") detailLines.push("WiFi: " + info.wifi_ssid);
    var ago = info.last_report_ago_s;
    if (ago === -1) detailLines.push("最后活动: 从未上报");
    else if (ago > 3600) detailLines.push("最后活动: " + Math.floor(ago/3600) + " 小时前");
    else detailLines.push("最后活动: " + Math.floor(ago/60) + " 分钟前");
    detailLines.push("", "⚠️ 只从列表移除，历史数据完整保留。", "手机重新上报后会自动回到列表。");

    if (!confirm(detailLines.join("\\n"))) return;
    sendCmd('clean_device', dev.id);
}

function confirmCleanAllOffline() {
    if (!confirm("确认清理所有【超过 24 小时未活动】的设备？\\n\\n注意：\\n- 只从列表移除，历史数据完整保留\\n- 30 分钟~24 小时之间的离线设备不会被清理\\n- 如需清理较新的离线设备，请用单设备删除")) return;
    sendCmd('clean_offline');
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
        return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
}
</script>
</body></html>"""
                html_bytes = html.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(html_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + html_bytes)

            except Exception as e:
                with print_lock:
                    print(f"❌ GET / (Web控制台) 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nWeb console generation error.")
                except Exception: pass

        else:
            client_conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

    except Exception as e:
        with print_lock:
            print(f"❌ handle_client 顶层异常: {e}")
    finally:
        try: client_conn.close()
        except Exception: pass


def start_server():
    threading.Thread(target=udp_listener, daemon=True).start()
    threading.Thread(target=offline_detector, daemon=True).start()
    threading.Thread(target=cmd_cleaner, daemon=True).start()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: server.bind(('0.0.0.0', PORT))
    except Exception as e:
        print(f"❌ 端口 {PORT} 绑定失败: {e}")
        return

    server.listen(20)
    with print_lock:
        print("=" * 50)
        print(f"🚀 设备同步服务已启动: http://{get_local_ip()}:{PORT}")
        print(f"📁 日志上传目录: {os.path.abspath(UPLOAD_LOG_DIR)}")
        print(f"🚨 失联检测: 每 5 分钟扫描一次，>2 小时未上报告警")
        print(f"📋 HTTP 下行队列: TTL={CMD_TTL_SECONDS}s, 上限={CMD_MAX_PER_DEVICE}/设备")
        print(f"🗑️  离线设备清理: 弹窗展示>30分钟未活动, 批量清理阈值={CLEAN_OFFLINE_THRESHOLD_MS // 3600000}小时")
        try:
            conn_chk = sqlite3.connect('track_history.db', timeout=5)
            report_cnt = conn_chk.execute("SELECT COUNT(*) FROM reports").fetchone()[0]
            dev_cnt = conn_chk.execute("SELECT COUNT(DISTINCT device_id) FROM reports").fetchone()[0]
            db_size = os.path.getsize('track_history.db') / (1024 * 1024)
            conn_chk.close()
            print(f"📊 上报数据库: {report_cnt} 条 / {dev_cnt} 台设备 / {db_size:.1f}MB")
            print(f"   保留策略: raw_json={REPORTS_KEEP_RAW_DAYS}天, nearby={REPORTS_KEEP_NEARBY_DAYS}天")
        except Exception:
            print("📊 上报数据库: 首次启动，等待上报")
        print("=" * 50)

    while True:
        try:
            conn, addr = server.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
        except KeyboardInterrupt: break
        except Exception as e:
            print(f"accept 异常: {e}")
            time.sleep(1)


if __name__ == "__main__":
    start_server()
