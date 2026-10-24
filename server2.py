import socket
import json
import datetime
import threading
import time
import sqlite3

PORT = 8080  # 如果端口冲突请修改
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

# 初始化 SQLite 数据库存轨迹
def init_db():
    conn = sqlite3.connect('track_history.db')
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS history
                 (device_id TEXT, lat REAL, lng REAL, timestamp INTEGER)''')
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

def send_udp_broadcast(command_str):
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        with print_lock: print(f"\n📡 [控制台] 正在发送广播: {command_str}")
        for i in range(3):
            sock.sendto(command_str.encode('utf-8'), ('255.255.255.255', 8888))
            time.sleep(0.2) 
        sock.close()
        return True
    except: return False

def handle_client(client_conn, client_addr):
    try:
        client_conn.settimeout(5.0)
        request = client_conn.recv(16384).decode('utf-8', errors='ignore')
        if not request: return

        # 1. 接收上报
        if "POST /api/report" in request:
            try:
                body = request.split("\r\n\r\n")[1]
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
                    
                    # 终端打印也统一保留 6 位小数
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

        # 2. 网页指令控制
        elif "POST /api/control" in request:
            try:
                body = request.split("\r\n\r\n")[1]
                cmd_data = json.loads(body)
                action = cmd_data.get("action")
                payload = cmd_data.get("payload", "")
                
                success = False
                if action == "force_report": 
                    success = send_udp_broadcast(f"FIND_ME_REPORT:{RESCUE_SECRET}")
                elif action == "play_alarm": 
                    success = send_udp_broadcast(f"FIND_ME_ALARM:{RESCUE_SECRET}")
                elif action == "change_server": 
                    success = send_udp_broadcast(f"FIND_ME_SERVER:{RESCUE_SECRET}:{payload}")
                elif action == "clean_offline":
                    now_ts = int(datetime.datetime.now().timestamp() * 1000)
                    to_delete = []
                    for k, v in ALL_DEVICES_STATUS.items():
                        if now_ts - v.get('received_at', 0) > 3600000: 
                            to_delete.append(k)
                    for k in to_delete:
                        del ALL_DEVICES_STATUS[k]
                    with print_lock: print(f"\n已清理 {len(to_delete)} 台超时未上报的设备。")
                    success = True

                resp = json.dumps({"status": "ok" if success else "error"})
                client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp)}\r\n\r\n{resp}".encode('utf-8'))
            except: pass

        # 3. 获取轨迹数据
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
            except: pass

        # 4. 网页 HTML
        elif "GET /" in request:
            html = """<!DOCTYPE html>
<html><head><meta charset='utf-8'><title>终端设备管理</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
body{font-family:sans-serif;margin:20px;background:#f4f6f9;}
h2{color:#333;font-size: 20px;}
.panel{background:#fff;padding:15px;border-radius:8px;margin-bottom:20px;box-shadow:0 2px 4px rgba(0,0,0,0.1);}
button{padding:6px 12px;background:#007bff;color:#fff;border:none;border-radius:4px;cursor:pointer;margin-right:10px;font-size:14px;}
button:hover{opacity:0.8;}
table{border-collapse:collapse;width:100%;background:#fff;}
th,td{border:1px solid #ddd;padding:10px;text-align:left;font-size:14px;}
th{background:#f0f0f0;}
details { cursor: pointer; color: #007bff; }
ul { margin: 5px 0 0 0; padding-left: 20px; font-size: 12px; color: #555; }
#map { height: 450px; width: 100%; border-radius: 8px; margin-top: 10px; }
</style>
</head>
<body>
<h2>📱 终端设备状态同步中心</h2>
<div class="panel">
    <button onclick="sendCmd('force_report')">同步最新状态</button>
    <button onclick="sendCmd('play_alarm')" style="background:#dc3545;">测试音频提示</button>
    <button onclick="sendCmd('clean_offline')" style="background:#6c757d;">清理离线数据</button>
</div>
<div class="panel">
    <table>
        <tr><th>设备ID</th><th>系统 / 状态</th><th>电量</th><th>Wi-Fi</th><th>经纬度</th><th>定位时间</th><th>收到时间</th><th>周围热点</th><th>操作</th></tr>
"""
            for dev_id, info in ALL_DEVICES_STATUS.items():
                os_ver = info.get('os_version', 'Unknown').replace("Android ", "A")
                screen_on = "亮屏" if info.get('is_screen_on') else "息屏"
                sys_status = f"{os_ver} <br><span style='font-size:12px;color:#888;'>{screen_on}</span>"
                
                lat = info.get('location', {}).get('latitude')
                lng = info.get('location', {}).get('longitude')
                if lat is not None and lng is not None:
                    # 【修改点】网页显示统一保留 6 位小数，且带高德地图跳转
                    coord = f"<a href='https://uri.amap.com/marker?position={lng},{lat}' target='_blank' style='color:#007bff;'>{lat:.6f}, {lng:.6f}</a>"
                else:
                    coord = "无定位"

                # 恢复：展开列表查看周围 Wi-Fi
                nearby = info.get('nearby_wifi', [])
                ap_list_html = ""
                if nearby:
                    ap_list_html = "<details><summary><b>" + str(len(nearby)) + " 个</b></summary><ul>"
                    for ap in nearby:
                        ap_list_html += f"<li>{ap.get('ssid')} ({ap.get('rssi')} dBm)</li>"
                    ap_list_html += "</ul></details>"
                else:
                    ap_list_html = "0 个"

                html += f"<tr><td><b>{dev_id}</b></td>"
                html += f"<td>{sys_status}</td>"
                html += f"<td>{info.get('battery', {}).get('level', '?')}%</td>"
                html += f"<td>{info.get('network', {}).get('wifi_ssid', '?')}</td>"
                html += f"<td>{coord}</td>"
                html += f"<td>{format_loc_time(info.get('location', {}).get('time', 0))}</td>"
                html += f"<td>{format_loc_time(info.get('received_at', 0))}</td>"
                html += f"<td>{ap_list_html}</td>"
                html += f"<td><button onclick=\"showTrack('{dev_id}')\" style=\"background:#17a2b8;\">历史轨迹</button></td></tr>"
                
            if not ALL_DEVICES_STATUS:
                html += "<tr><td colspan='9' align='center'>暂无设备上报</td></tr>"

            html += """</table></div>
<div id="map"></div>
<script>
var map = L.map('map').setView([39.9, 116.4], 13);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19}).addTo(map);
var markersLayer = L.layerGroup().addTo(map);
var polyline = null;

async function sendCmd(action, payload = "") {
    if(payload === "" && action === 'change_server') { alert("请输入新服务器地址！"); return; }
    try {
        const res = await fetch('/api/control', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({action: action, payload: payload})});
        const data = await res.json();
        if(data.status === 'ok') alert("指令已发送！");
    } catch(e) { alert("请求失败: " + e); }
}

async function showTrack(devId) {
    const res = await fetch(`/api/history?device_id=${devId}`);
    const data = await res.json();
    markersLayer.clearLayers();
    if (polyline) map.removeLayer(polyline);

    if (data.length === 0) return alert("暂无该设备的轨迹数据");

    var latlngs = [];
    data.reverse().forEach((row, index) => {
        let lat = row[0], lng = row[1];
        latlngs.push([lat, lng]);
        if (index === data.length - 1) {
            L.marker([lat, lng]).addTo(markersLayer).bindPopup("最新位置").openPopup();
            map.setView([lat, lng], 16);
        }
    });
    polyline = L.polyline(latlngs, {color: 'blue', weight: 3}).addTo(map);
}
</script>
</body></html>"""
            client_conn.sendall(f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(html.encode('utf-8'))}\r\n\r\n{html}".encode('utf-8'))
    except Exception as e:
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
