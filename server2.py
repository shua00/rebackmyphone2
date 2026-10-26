import socket
import json
import datetime
import threading
import time
import sqlite3

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
print_lock = threading.Lock()

def init_db():
    conn = sqlite3.connect('track_history.db')
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS history
                 (device_id TEXT, lat REAL, lng REAL, timestamp INTEGER)''')
    c.execute('''CREATE TABLE IF NOT EXISTS messages
                 (device_id TEXT, msg_time TEXT, message TEXT, timestamp INTEGER)''')
    conn.commit()
    conn.close()

init_db()

def save_history(dev_id, lat, lng, ts):
    if lat is None or lng is None: return
    try:
        conn = sqlite3.connect('track_history.db')
        c = conn.cursor()
        c.execute("INSERT INTO history VALUES (?, ?, ?, ?)", (dev_id, lat, lng, ts))
        conn.commit()
        conn.close()
    except: pass

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
    except: return "N/A"

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]; s.close(); return ip
    except: return "127.0.0.1"

def send_udp_broadcast(command_str, target_ip="255.255.255.255"):
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        with print_lock: print(f"\n📡 [控制台] 正在发送指令至 {target_ip}: {command_str}")
        send_times = 3 if target_ip == "255.255.255.255" else 1
        for i in range(send_times):
            sock.sendto(command_str.encode('utf-8'), (target_ip, 8888))
            if send_times > 1: time.sleep(0.2) 
        sock.close()
        return True
    except Exception as e: 
        with print_lock: print(f"发送失败: {e}")
        return False

def handle_client(client_conn, client_addr):
    try:
        client_conn.settimeout(5.0)
        request = client_conn.recv(16384).decode('utf-8', errors='ignore')
        if not request: return

        if request.startswith("OPTIONS"):
            client_conn.sendall(b"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: 0\r\n\r\n")
            return

        if "POST /api/report" in request:
            try:
                body = request.split("\r\n\r\n", 1)[1]
                data = json.loads(body)
                if data.get("token") != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\n"); return
                dev_id = data.get("device_id", "unknown_device")
                data["received_at"] = int(datetime.datetime.now().timestamp() * 1000)
                ALL_DEVICES_STATUS[dev_id] = data

                loc = data.get('location', {})
                lat, lng = loc.get('latitude'), loc.get('longitude')
                if lat is not None and lng is not None:
                    save_history(dev_id, lat, lng, data["received_at"])

                with print_lock:
                    uptime_ms = data.get('uptime_ms', 0)
                    uptime_hours = round(uptime_ms / (1000 * 60 * 60), 1) if uptime_ms else 0
                    screen_on = "亮屏" if data.get('is_screen_on') else "息屏"
                    os_ver = data.get('os_version', 'Unknown')
                    chat_lock = "🔒 已锁" if data.get('chat_locked') else "🔓 未锁"
                    loc_str = f"{lat:.6f}, {lng:.6f}" if lat is not None and lng is not None else "未获取"

                    print("\n" + "="*50)
                    print(f"收到上报 | 设备: {dev_id}")
                    print(f"状态: {os_ver} | {screen_on} | {chat_lock} | 已开机 {uptime_hours} 小时")
                    print(f"Wi-Fi: {data.get('network', {}).get('wifi_ssid')} (IP: {data.get('network', {}).get('local_ip')})")
                    print(f"电量: {data.get('battery', {}).get('level')}%")
                    print(f"定位: {loc_str}")
                    print("="*50)

                response_body = json.dumps({"status": "success", "config": GLOBAL_DEFAULT_CONFIG})
                client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(response_body)}\r\n\r\n{response_body}".encode('utf-8'))
            except Exception as e: 
                with print_lock: print(f"解析异常: {e}")
                
        elif "POST /api/message" in request:
            try:
                body = request.split("\r\n\r\n", 1)[1]
                data = json.loads(body)
                if data.get("token") != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\n"); return
                
                dev_id = data.get("device_id", "unknown")
                msg_time = data.get("time_str", "")
                msg = data.get("message", "")
                ts = data.get("timestamp", 0)
                
                conn = sqlite3.connect('track_history.db')
                c = conn.cursor()
                c.execute("INSERT INTO messages VALUES (?, ?, ?, ?)", (dev_id, msg_time, msg, ts))
                conn.commit()
                conn.close()

                with print_lock:
                    print(f"\n💬 [收到留言备份] 设备: {dev_id} | 时间: {msg_time}")
                    print(f"内容: {msg}")

                client_conn.sendall(b"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\n\r\n")
            except Exception as e:
                with print_lock: print(f"留言处理异常: {e}")

        elif "POST /api/control" in request:
            try:
                body = request.split("\r\n\r\n", 1)[1]
                cmd_data = json.loads(body)
                action = cmd_data.get("action")
                payload = cmd_data.get("payload", "")
                target_ip = cmd_data.get("target_ip", "255.255.255.255")
                
                success = False
                if action == "force_report": 
                    success = send_udp_broadcast(f"FIND_ME_REPORT:{RESCUE_SECRET}", target_ip)
                elif action == "play_alarm": 
                    success = send_udp_broadcast(f"FIND_ME_ALARM:{RESCUE_SECRET}", target_ip)
                elif action == "custom_msg": 
                    success = send_udp_broadcast(f"FIND_ME_MSG:{RESCUE_SECRET}:{payload}", target_ip)
                elif action == "change_server": 
                    success = send_udp_broadcast(f"FIND_ME_SERVER:{RESCUE_SECRET}:{payload}", target_ip)
                elif action == "clear_history": 
                    success = send_udp_broadcast(f"FIND_ME_CLEAR_HISTORY:{RESCUE_SECRET}", target_ip)
                elif action == "lock_chat":
                    success = send_udp_broadcast(f"FIND_ME_CHAT_LOCK:{RESCUE_SECRET}", target_ip)
                elif action == "unlock_chat":
                    success = send_udp_broadcast(f"FIND_ME_CHAT_UNLOCK:{RESCUE_SECRET}", target_ip)
                elif action == "set_sms_num":
                    success = send_udp_broadcast(f"FIND_ME_SET_SMS_NUM:{RESCUE_SECRET}:{payload}", target_ip)
                elif action == "clean_offline":
                    try:
                        now_ts = int(datetime.datetime.now().timestamp() * 1000)
                        to_delete = []
                        for k, v in list(ALL_DEVICES_STATUS.items()):
                            if now_ts - v.get('received_at', 0) > 3600000: 
                                to_delete.append(k)
                        for k in to_delete:
                            if k in ALL_DEVICES_STATUS:
                                del ALL_DEVICES_STATUS[k]
                        with print_lock: print(f"\n已清理 {len(to_delete)} 台超时未上报的设备。")
                        success = True
                    except Exception as clean_err:
                        with print_lock: print(f"清理设备异常: {clean_err}")
                        success = False

                resp = json.dumps({"status": "ok" if success else "error", "msg": "指令执行失败" if not success else ""})
                client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp)}\r\n\r\n{resp}".encode('utf-8'))
            except Exception as e: 
                with print_lock: print(f"控制指令处理异常: {e}")
                try:
                    resp = json.dumps({"status": "error", "msg": f"服务器内部错误: {str(e)}"})
                    client_conn.sendall(f"HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp)}\r\n\r\n{resp}".encode('utf-8'))
                except: pass

        elif "GET /api/history" in request:
            try:
                dev_id = request.split("device_id=")[1].split(" ")[0]
                conn = sqlite3.connect('track_history.db')
                c = conn.cursor()
                c.execute("SELECT lat, lng, timestamp FROM history WHERE device_id=? ORDER BY timestamp DESC LIMIT 50", (dev_id,))
                rows = c.fetchall()
                conn.close()
                resp = json.dumps(rows)
                client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp)}\r\n\r\n{resp}".encode('utf-8'))
            except Exception as e:
                with print_lock: print(f"获取历史轨迹异常: {e}")

        elif "GET /api/messages" in request:
            try:
                conn = sqlite3.connect('track_history.db')
                c = conn.cursor()
                c.execute("SELECT device_id, msg_time, message FROM messages ORDER BY timestamp DESC LIMIT 50")
                rows = c.fetchall()
                conn.close()
                resp = json.dumps(rows)
                client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp)}\r\n\r\n{resp}".encode('utf-8'))
            except Exception as e:
                with print_lock: print(f"获取留言异常: {e}")

        elif "GET /" in request:
            html = """<!DOCTYPE html>
<html><head><meta charset='utf-8'>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>终端设备管理</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
/* GitHub 风格配色 */
:root {
    --color-canvas-default: #ffffff;
    --color-canvas-subtle: #f6f8fa;
    --color-border-default: #d0d7de;
    --color-border-muted: #d8dee4;
    --color-fg-default: #24292f;
    --color-fg-muted: #57606a;
    --color-accent-fg: #0969da;
    --color-btn-bg: #f6f8fa;
    --color-btn-border: rgba(27,31,36,0.15);
    --color-btn-hover-bg: #f3f4f6;
    --color-btn-primary-bg: #24292f;
    --color-btn-primary-hover-bg: #1b1f24;
    --color-btn-danger-bg: #cf222e;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
    margin: 24px; 
    background: var(--color-canvas-subtle); 
    color: var(--color-fg-default);
}
h2 { font-size: 20px; font-weight: 600; margin-bottom: 20px; }
.panel {
    background: var(--color-canvas-default);
    padding: 16px;
    border-radius: 6px;
    border: 1px solid var(--color-border-default);
    margin-bottom: 24px;
}

/* 按钮 */
button {
    padding: 5px 16px;
    font-size: 14px;
    font-weight: 500;
    line-height: 20px;
    white-space: nowrap;
    cursor: pointer;
    border-radius: 6px;
    border: 1px solid var(--color-btn-border);
    background-color: var(--color-btn-bg);
    color: var(--color-fg-default);
    transition: 0.2s;
}
button:hover { background-color: var(--color-btn-hover-bg); }
.btn-primary { background-color: var(--color-btn-primary-bg); color: #fff; border-color: rgba(27,31,36,0.15); }
.btn-primary:hover { background-color: var(--color-btn-primary-hover-bg); }
.btn-danger { background-color: var(--color-canvas-default); color: var(--color-btn-danger-bg); border-color: var(--color-border-default); }
.btn-danger:hover { background-color: var(--color-btn-danger-bg); color: #fff; border-color: var(--color-btn-danger-bg); }
.btn-warning { background-color: var(--color-canvas-default); color: #9a6700; border-color: var(--color-border-default); }
.btn-warning:hover { background-color: #9a6700; color: #fff; }

button.btn-sm {
    padding: 3px 12px; font-size: 12px; line-height: 18px;
}

/* 输入框 */
input[type="text"] {
    padding: 5px 12px; font-size: 14px; line-height: 20px;
    border: 1px solid var(--color-border-default); border-radius: 6px;
    outline: none; transition: border-color 0.2s, box-shadow 0.2s;
}
input[type="text"]:focus { border-color: var(--color-accent-fg); box-shadow: 0 0 0 3px rgba(9,105,218,0.3); }

/* 表格 */
.table-responsive { width: 100%; overflow-x: auto; background: var(--color-canvas-default); border-radius: 6px; border: 1px solid var(--color-border-default); }
table { border-collapse: collapse; width: 100%; min-width: 900px; }
th, td { border-bottom: 1px solid var(--color-border-muted); padding: 12px 16px; text-align: left; font-size: 14px; vertical-align: middle; }
th { background-color: var(--color-canvas-subtle); font-weight: 600; color: var(--color-fg-default); }
tr:last-child td { border-bottom: none; }
tr:hover { background-color: var(--color-canvas-subtle); }
a { color: var(--color-accent-fg); text-decoration: none; }
a:hover { text-decoration: underline; }

details { cursor: pointer; color: var(--color-fg-default); font-weight: 600; user-select: none; outline: none; }
details summary::-webkit-details-marker { display: none; }
details summary { display: flex; align-items: center; }
details summary::before { content: '▶'; font-size: 10px; margin-right: 8px; transition: transform 0.2s; }
details[open] summary::before { transform: rotate(90deg); }

/* 布局 */
.btn-group { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.btn-group button { margin: 0; }
.input-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 12px; }
.input-row input[type="text"] { flex: 1; min-width: 200px; }
.input-row button { margin: 0; }

#mapContainer { display: none; margin-top: 24px; }
#map { height: 500px; width: 100%; border-radius: 6px; border: 1px solid var(--color-border-default); }
#msgModal { display: none; position: fixed; z-index: 9999; left: 0; top: 0; width: 100%; height: 100%; background-color: rgba(0,0,0,0.5); }
#msgContent { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); background: var(--color-canvas-default); border-radius: 6px; border: 1px solid var(--color-border-default); width: 90%; max-width: 600px; max-height: 80%; overflow-y: auto; box-shadow: 0 8px 24px rgba(140,149,159,0.2); }
.modal-header { padding: 16px; border-bottom: 1px solid var(--color-border-muted); display: flex; justify-content: space-between; align-items: center; }
.modal-header h3 { margin: 0; font-size: 16px; font-weight: 600; }
.close-btn { cursor: pointer; color: var(--color-fg-muted); font-size: 20px; line-height: 1; }
.close-btn:hover { color: var(--color-btn-danger-bg); }
.modal-body { padding: 16px; line-height: 1.6; font-size: 14px; }

#toast {
    visibility: hidden; min-width: 250px; background-color: var(--color-fg-default); color: #fff;
    text-align: center; border-radius: 6px; padding: 12px; position: fixed; z-index: 10000;
    left: 50%; bottom: 30px; transform: translateX(-50%); font-size: 14px; opacity: 0; transition: opacity 0.3s, bottom 0.3s;
}
#toast.show { visibility: visible; opacity: 1; bottom: 50px; }

/* 移动端布局适配 */
@media (max-width: 768px) {
    body { margin: 12px; }
    .input-row { flex-direction: column; align-items: stretch; }
    .input-row button { width: 100%; padding: 8px; margin-top: 4px; }
    
    /* 高频操作网格 */
    .quick-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
    .quick-actions button { width: 100%; padding: 8px 4px; text-align: center; }
    
    /* 高级菜单网格 */
    .btn-group { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .btn-group button { width: 100%; text-align: center; padding: 8px 4px; font-size: 13px;}
    
    .hide-mobile { display: none !important; }
    button.btn-sm { display: block; width: 100%; margin-bottom: 6px; text-align: center; padding: 6px; }
}
</style>
</head>
<body>
<h2>📱 终端设备状态同步中心</h2>

<div class="panel">
    <!-- 核心高频交互 -->
    <div class="input-row">
        <input type="text" id="msgText" placeholder="输入要群发到所有手机屏幕上的文字...">
        <button class="btn-primary" onclick="sendScreenMsg('255.255.255.255')">发送群发提示</button>
    </div>
    
    <!-- 常用快捷操作 -->
    <div class="quick-actions">
        <button class="btn-primary" onclick="sendCmd('force_report')">全局同步状态</button>
        <button onclick="showServerMessages()">查看云端留言</button>
    </div>

    <!-- 低频高级工具 (折叠) -->
    <details style="margin-top: 16px; border-top: 1px solid var(--color-border-muted); padding-top: 12px;">
        <summary>⚙️ 高级设置与系统工具</summary>
        <div class="btn-group" style="margin-top: 12px;">
            <button onclick="sendCmd('lock_chat')">🔒 锁定聊天</button>
            <button onclick="sendCmd('unlock_chat')">🔓 解锁聊天</button>
            <button class="btn-danger" onclick="sendCmd('play_alarm')">全局测试音频</button>
            <button id="toggleMapBtn" onclick="toggleMap()">🗺️ 展开地图</button>
            <button onclick="sendCmd('clean_offline')">清理离线设备</button>
            <button onclick="sendCmd('clear_history')">清空手机历史</button>
        </div>
        <div class="input-row" style="margin-top: 12px;">
            <input type="text" id="smsNumText" placeholder="输入紧急接收短信的手机号...">
            <button onclick="sendSmsNum('255.255.255.255')">📱 设置报警号码</button>
        </div>
    </details>
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
            <th style="min-width: 160px;">操作</th>
        </tr>
"""
            for dev_id, info in list(ALL_DEVICES_STATUS.items()):
                os_ver = info.get('os_version', 'Unknown').replace("Android ", "A")
                screen_on = "亮屏" if info.get('is_screen_on') else "息屏"
                
                is_locked = info.get('chat_locked', False)
                chat_lock_str = "<span style='color:#cf222e;'>🔒 已锁</span>" if is_locked else "<span style='color:#2da44e;'>🔓 未锁</span>"
                sys_status = f"{os_ver} <br><span style='font-size:12px;color:var(--color-fg-muted);'>{screen_on} | {chat_lock_str}</span>"
                
                lat = info.get('location', {}).get('latitude')
                lng = info.get('location', {}).get('longitude')
                if lat is not None and lng is not None:
                    coord = f"<a href='https://uri.amap.com/marker?position={lng},{lat}' target='_blank'>{lat:.6f}, {lng:.6f}</a>"
                else:
                    coord = "<span style='color:var(--color-fg-muted);'>无定位</span>"

                ssid = info.get('network', {}).get('wifi_ssid', '?')
                local_ip = info.get('network', {}).get('local_ip', '0.0.0.0')
                wifi_info = f"{ssid}<br><span style='font-size:12px;color:var(--color-fg-muted);'>{local_ip}</span>"

                nearby = info.get('nearby_wifi', [])
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
                html += f"<td>{info.get('battery', {}).get('level', '?')}%</td>"
                html += f"<td>{wifi_info}</td>"
                html += f"<td>{coord}</td>"
                html += f"<td class='hide-mobile'>{format_loc_time(info.get('location', {}).get('time', 0))}</td>"
                html += f"<td>{format_loc_time(info.get('received_at', 0))}</td>"
                html += f"<td class='hide-mobile'>{ap_list_html}</td>"
                
                html += f"<td>"
                html += f"<button class='btn-sm' onclick=\"sendCmd('force_report', '', '{local_ip}')\">同步</button>"
                html += f"<button class='btn-sm' onclick=\"sendScreenMsg('{local_ip}')\">提示</button>"
                html += f"<button class='btn-sm btn-danger' onclick=\"sendCmd('play_alarm', '', '{local_ip}')\">响铃</button>"
                
                if is_locked:
                    html += f"<button class='btn-sm btn-warning' onclick=\"sendCmd('unlock_chat', '', '{local_ip}')\">🔓 解锁</button>"
                else:
                    html += f"<button class='btn-sm' onclick=\"sendCmd('lock_chat', '', '{local_ip}')\">🔒 锁定</button>"
                    
                html += f"<button class='btn-sm' onclick=\"showTrack('{dev_id}')\">轨迹</button>"
                html += f"</td></tr>"
                
            if not ALL_DEVICES_STATUS:
                html += "<tr><td colspan='9' align='center' style='padding:40px;color:var(--color-fg-muted);'>暂无设备上报</td></tr>"

            html += """</table></div>
<div id="mapContainer">
    <div id="map"></div>
</div>
<div id="msgModal">
    <div id="msgContent">
        <div class="modal-header">
            <h3>📜 云端留言备份</h3>
            <span class="close-btn" onclick="document.getElementById('msgModal').style.display='none'">✖</span>
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

function showToast(msg) {
    var toast = document.getElementById("toast");
    toast.innerText = msg;
    toast.className = "show";
    setTimeout(function(){ toast.className = toast.className.replace("show", ""); }, 3000);
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
            map = L.map('map').setView([39.9, 116.4], 13);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19}).addTo(map);
            markersLayer = L.layerGroup().addTo(map);
        }
        setTimeout(function() { map.invalidateSize(); }, 200);
    }
    isMapVisible = !isMapVisible;
}

async function showTrack(devId) {
    if (!isMapVisible) toggleMap();
    const res = await fetch(`/api/history?device_id=${devId}`);
    const data = await res.json();
    if (data.length === 0) return showToast("暂无该设备的轨迹数据");
    
    setTimeout(() => {
        markersLayer.clearLayers();
        if (polyline) map.removeLayer(polyline);
        var latlngs = [];
        data.reverse().forEach((row, index) => {
            let lat = row[0], lng = row[1];
            latlngs.push([lat, lng]);
            if (index === data.length - 1) {
                L.marker([lat, lng]).addTo(markersLayer).bindPopup("最新位置").openPopup();
                map.setView([lat, lng], 16);
            }
        });
        polyline = L.polyline(latlngs, {color: '#0969da', weight: 4}).addTo(map);
        map.invalidateSize();
    }, 250);
}

async function sendCmd(action, payload = "", target_ip = "255.255.255.255") {
    try {
        const res = await fetch('/api/control', {
            method: 'POST', 
            headers: {'Content-Type': 'application/json'}, 
            body: JSON.stringify({action: action, payload: payload, target_ip: target_ip})
        });
        
        if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
        const text = await res.text();
        let data;
        try {
            data = JSON.parse(text);
        } catch (e) {
            throw new Error("服务器返回了无效的格式");
        }

        if(data.status === 'ok') {
            showToast("✅ 指令发送成功");
            setTimeout(() => location.reload(), 1500);
        } else {
            showToast("❌ 失败: " + (data.msg || "未知错误"));
        }
    } catch(e) { 
        showToast("❌ 请求失败: " + e.message); 
    }
}

async function sendScreenMsg(target_ip) {
    let msg = document.getElementById('msgText').value;
    if(!msg) { showToast("请先在输入框输入文字！"); return; }
    await sendCmd('custom_msg', msg, target_ip);
}

async function sendSmsNum(target_ip) {
    let num = document.getElementById('smsNumText').value.trim();
    if(!num) { showToast("请先在输入框输入手机号！"); return; }
    if(!/^1[3-9]\\d{9}$/.test(num)) { showToast("手机号格式好像不太对哦！"); return; }
    await sendCmd('set_sms_num', num, target_ip);
}

async function showServerMessages() {
    try {
        const res = await fetch('/api/messages');
        const data = await res.json();
        const listDiv = document.getElementById('msgList');
        
        if(data.length === 0) {
            listDiv.innerHTML = "<p style='color:var(--color-fg-muted);'>服务器还没有记录哦~</p>";
        } else {
            let html = "";
            data.forEach(item => {
                html += `<div style="border-bottom:1px solid var(--color-border-muted); padding:12px 0;">
                            <div style="color:var(--color-fg-muted); font-size:12px; margin-bottom:6px;">[${item[1]}] - ${item[0]}</div>
                            <div>${item[2]}</div>
                         </div>`;
            });
            listDiv.innerHTML = html;
        }
        document.getElementById('msgModal').style.display = 'block';
    } catch(e) { showToast("拉取云端数据失败: " + e.message); }
}
</script>
</body></html>"""
            client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(html.encode('utf-8'))}\r\n\r\n{html}".encode('utf-8'))
    except Exception:
        pass
    finally:
        try: client_conn.close()
        except: pass

def start_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: server.bind(('0.0.0.0', PORT))
    except Exception as e: print(f"❌ 端口 {PORT} 绑定失败: {e}"); return
    
    server.listen(20)
    with print_lock:
        print("="*50)
        print(f"🚀 设备同步服务已启动: http://{get_local_ip()}:{PORT}")
        print("="*50)

    while True:
        try:
            conn, addr = server.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
        except KeyboardInterrupt: break

if __name__ == "__main__":
    start_server()
