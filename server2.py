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
        return f"{dt.strftime('%m-%d %H:%M:%S')} ({ago})"
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
                    loc_str = f"{lat:.6f}, {lng:.6f}" if lat is not None and lng is not None else "未获取"

                    print("\n" + "="*50)
                    print(f"收到上报 | 设备: {dev_id}")
                    print(f"状态: {os_ver} | {screen_on} | 已开机 {uptime_hours} 小时")
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
                elif action == "toggle_chat":
                    success = send_udp_broadcast(f"FIND_ME_CHAT_TOGGLE:{RESCUE_SECRET}", target_ip)
                elif action == "clean_offline":
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

                resp = json.dumps({"status": "ok" if success else "error"})
                client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp)}\r\n\r\n{resp}".encode('utf-8'))
            except Exception as e: 
                with print_lock: print(f"控制指令处理异常: {e}")
                try:
                    resp = json.dumps({"status": "error", "msg": str(e)})
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
<html><head><meta charset='utf-8'><title>终端设备管理</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;margin:20px;background:#f4f5f7;color:#333;}
h2{font-size: 20px;font-weight: 600;margin-bottom: 20px;}
.panel{background:#fff;padding:16px;border-radius:8px;margin-bottom:20px;box-shadow:0 1px 3px rgba(0,0,0,0.1);border: 1px solid #e5e7eb;}
button{padding:6px 14px;background:#fff;color:#374151;border:1px solid #d1d5db;border-radius:6px;cursor:pointer;margin-right:10px;font-size:14px;transition:all 0.2s;}
button:hover{background:#f3f4f6;border-color:#9ca3af;}
.btn-primary{background:#1f2937;color:#fff;border-color:#1f2937;}
.btn-primary:hover{background:#111827;}
.btn-danger{color:#ef4444;border-color:#fca5a5;}
.btn-danger:hover{background:#fef2f2;border-color:#ef4444;}
button.btn-sm{padding:4px 8px;font-size:12px;margin-bottom:6px;width:100%;border-radius:4px;}
input[type="text"]{padding:6px 12px;border:1px solid #d1d5db;border-radius:6px;margin-right:10px;width:300px;font-size:14px;}
input[type="text"]:focus{outline:none;border-color:#1f2937;box-shadow:0 0 0 2px rgba(31,41,55,0.1);}
table{border-collapse:collapse;width:100%;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,0.1);border: 1px solid #e5e7eb;}
th,td{border-bottom:1px solid #e5e7eb;padding:12px 16px;text-align:left;font-size:14px;}
th{background:#f9fafb;font-weight:600;color:#4b5563;}
tr:last-child td{border-bottom:none;}
tr:hover{background:#f9fafb;}
details { cursor: pointer; color: #3b82f6; }
ul { margin: 5px 0 0 0; padding-left: 20px; font-size: 12px; color: #6b7280; }
#mapContainer { display: none; margin-top: 20px;}
#map { height: 500px; width: 100%; border-radius: 8px; box-shadow:0 1px 3px rgba(0,0,0,0.1); border: 1px solid #e5e7eb;}
#msgModal { display: none; position: fixed; z-index: 9999; left: 0; top: 0; width: 100%; height: 100%; background-color: rgba(0,0,0,0.4); backdrop-filter: blur(2px); }
#msgContent { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); background: #fff; padding: 24px; border-radius: 12px; width: 80%; max-width: 600px; max-height: 80%; overflow-y: auto; box-shadow: 0 10px 25px rgba(0,0,0,0.1); }
.close-btn { float: right; cursor: pointer; font-weight: bold; color: #9ca3af; font-size: 20px; transition: color 0.2s;}
.close-btn:hover { color: #ef4444; }
</style>
</head>
<body>
<h2>📱 终端设备状态同步中心</h2>
<div class="panel">
    <button class="btn-primary" onclick="sendCmd('force_report')">全局同步状态</button>
    <button class="btn-danger" onclick="sendCmd('play_alarm')">全局测试音频</button>
    <button onclick="sendCmd('toggle_chat')">切换聊天入口</button>
    <button onclick="sendCmd('clean_offline')">清理离线设备</button>
    <button onclick="showServerMessages()">查看云端留言</button>
    <button onclick="sendCmd('clear_history')">清空手机历史</button>
    <button id="toggleMapBtn" onclick="toggleMap()" style="float:right; font-weight:600;">🗺️ 展开地图</button>
    <br><br>
    <input type="text" id="msgText" placeholder="输入要群发到所有手机屏幕上的文字...">
    <button class="btn-primary" onclick="sendScreenMsg('255.255.255.255')">发送群发提示</button>
</div>
<div class="panel" style="padding:0; border:none; box-shadow:none; background:transparent;">
    <table>
        <tr><th>设备ID</th><th>系统 / 状态</th><th>电量</th><th>Wi-Fi / IP</th><th>经纬度</th><th>定位时间</th><th>收到时间</th><th>周围热点</th><th>操作</th></tr>
"""
            for dev_id, info in list(ALL_DEVICES_STATUS.items()):
                os_ver = info.get('os_version', 'Unknown').replace("Android ", "A")
                screen_on = "亮屏" if info.get('is_screen_on') else "息屏"
                sys_status = f"{os_ver} <br><span style='font-size:12px;color:#6b7280;'>{screen_on}</span>"
                
                lat = info.get('location', {}).get('latitude')
                lng = info.get('location', {}).get('longitude')
                if lat is not None and lng is not None:
                    coord = f"<a href='https://uri.amap.com/marker?position={lng},{lat}' target='_blank' style='color:#3b82f6;text-decoration:none;'>{lat:.6f}, {lng:.6f}</a>"
                else:
                    coord = "无定位"

                ssid = info.get('network', {}).get('wifi_ssid', '?')
                local_ip = info.get('network', {}).get('local_ip', '0.0.0.0')
                wifi_info = f"{ssid}<br><span style='font-size:12px;color:#6b7280;'>{local_ip}</span>"

                nearby = info.get('nearby_wifi', [])
                ap_list_html = ""
                if nearby:
                    ap_list_html = "<details><summary><b>" + str(len(nearby)) + " 个</b></summary><ul>"
                    for ap in nearby:
                        ap_list_html += f"<li>{ap.get('ssid')} ({ap.get('rssi')} dBm)</li>"
                    ap_list_html += "</ul></details>"
                else:
                    ap_list_html = "<span style='color:#9ca3af;'>0 个</span>"

                html += f"<tr><td><b>{dev_id}</b></td>"
                html += f"<td>{sys_status}</td>"
                html += f"<td>{info.get('battery', {}).get('level', '?')}%</td>"
                html += f"<td>{wifi_info}</td>"
                html += f"<td>{coord}</td>"
                html += f"<td>{format_loc_time(info.get('location', {}).get('time', 0))}</td>"
                html += f"<td>{format_loc_time(info.get('received_at', 0))}</td>"
                html += f"<td>{ap_list_html}</td>"
                
                html += f"<td style='min-width:100px;'>"
                html += f"<button class='btn-sm' onclick=\"sendCmd('force_report', '', '{local_ip}')\">单独同步</button>"
                html += f"<button class='btn-sm' onclick=\"sendScreenMsg('{local_ip}')\">单独提示</button>"
                html += f"<button class='btn-sm btn-danger' onclick=\"sendCmd('play_alarm', '', '{local_ip}')\">单独响铃</button>"
                html += f"<button class='btn-sm' onclick=\"sendCmd('toggle_chat', '', '{local_ip}')\">开关聊天</button>"
                html += f"<button class='btn-sm' onclick=\"showTrack('{dev_id}')\">历史轨迹</button>"
                html += f"</td></tr>"
                
            if not ALL_DEVICES_STATUS:
                html += "<tr><td colspan='9' align='center' style='padding:30px;color:#6b7280;'>暂无设备上报</td></tr>"

            html += """</table></div>
<div id="mapContainer">
    <div id="map"></div>
</div>
<div id="msgModal">
    <div id="msgContent">
        <span class="close-btn" onclick="document.getElementById('msgModal').style.display='none'">✖</span>
        <h3 style="margin-top:0;">📜 云端留言备份</h3>
        <div id="msgList" style="margin-top: 15px; line-height: 1.6; font-size: 15px;"></div>
    </div>
</div>
<script>
var map = null;
var markersLayer = null;
var polyline = null;
var isMapVisible = false;

function toggleMap() {
    var container = document.getElementById('mapContainer');
    var btn = document.getElementById('toggleMapBtn');
    
    if (isMapVisible) {
        container.style.display = 'none';
        btn.innerHTML = '🗺️ 展开地图';
    } else {
        container.style.display = 'block';
        btn.innerHTML = '⬆️ 收起地图';
        
        // 如果地图还没初始化，现在初始化
        if (!map) {
            map = L.map('map').setView([39.9, 116.4], 13);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19}).addTo(map);
            markersLayer = L.layerGroup().addTo(map);
        }
        // 解决隐藏容器展开后地图显示不全（灰块）的问题
        setTimeout(function() { map.invalidateSize(); }, 200);
    }
    isMapVisible = !isMapVisible;
}

// 修改轨迹显示逻辑，自动拉起地图
async function showTrack(devId) {
    if (!isMapVisible) toggleMap(); // 没展开就先展开

    const res = await fetch(`/api/history?device_id=${devId}`);
    const data = await res.json();
    
    if (data.length === 0) return alert("暂无该设备的轨迹数据");
    
    // 延迟一下，确保地图已经渲染
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
        polyline = L.polyline(latlngs, {color: '#1f2937', weight: 4}).addTo(map);
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
        const data = await res.json();
        if(data.status === 'ok') {
            if(target_ip !== "255.255.255.255") console.log("已向设备 " + target_ip + " 发送指令！");
            else alert("指令发送成功！");
        } else {
            alert("指令执行失败: " + (data.msg || "未知错误"));
        }
    } catch(e) { alert("请求失败，可能服务器未响应或网络断开。\\n错误信息: " + e); }
}

async function sendScreenMsg(target_ip) {
    let msg = document.getElementById('msgText').value;
    if(!msg) { alert("请先在输入框输入文字！"); return; }
    await sendCmd('custom_msg', msg, target_ip);
}

async function showServerMessages() {
    try {
        const res = await fetch('/api/messages');
        const data = await res.json();
        const listDiv = document.getElementById('msgList');
        
        if(data.length === 0) {
            listDiv.innerHTML = "<p style='color:#6b7280;'>服务器还没有记录哦~</p>";
        } else {
            let html = "";
            data.forEach(item => {
                html += `<div style="border-bottom:1px solid #f3f4f6; padding:12px 0;">
                            <div style="color:#9ca3af; font-size:12px; margin-bottom:4px;">[${item[1]}] - ${item[0]}</div>
                            <div style="font-size:15px; color:#1f2937;">${item[2]}</div>
                         </div>`;
            });
            listDiv.innerHTML = html;
        }
        document.getElementById('msgModal').style.display = 'block';
    } catch(e) { alert("拉取云端数据失败: " + e); }
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
