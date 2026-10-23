import socket
import json
import datetime
import threading
import time

PORT = 8000  # 如果冲突请改成 8080
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

# 全局打印锁
print_lock = threading.Lock()

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
        print(f"\n📡 [控制台] 正在发送广播: {command_str}")
        for i in range(3):
            sock.sendto(command_str.encode('utf-8'), ('255.255.255.255', 8888))
            time.sleep(0.2) 
        sock.close()
        return True
    except Exception as e:
        print(f"❌ 广播发送失败: {e}")
        return False

def handle_client(client_conn, client_addr):
    try:
        client_conn.settimeout(5.0)
        request = client_conn.recv(16384).decode('utf-8', errors='ignore')
        if not request: return

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
                nearby = data.get('nearby_wifi', [])
                
                with print_lock:
                    print("\n" + "="*50)
                    print(f"📥 [收到上报] 设备: 【{dev_id}】")
                    print(f"📶 Wi-Fi: {data.get('network', {}).get('wifi_ssid')} (IP: {data.get('network', {}).get('local_ip')})")
                    print(f"🔋 电量: {data.get('battery', {}).get('level')}% (充电中: {data.get('battery', {}).get('is_charging')})")
                    print(f"📍 定位: {loc.get('latitude')}, {loc.get('longitude')} [{loc.get('provider')}, 精度 {loc.get('accuracy')}m]")
                    print(f"🕒 定位时间: {format_loc_time(loc.get('time', 0))}")
                    print(f"📥 上报时间: {format_loc_time(data.get('received_at', 0))}")
                    print(f"📡 周围热点: {len(nearby)} 个")
                    
                    # 【新增】终端打印周围 Wi-Fi 列表
                    for ap in nearby[:5]:
                        print(f"   - {ap.get('ssid')} ({ap.get('rssi')} dBm)")
                    if len(nearby) > 5:
                        print(f"   ... 其余 {len(nearby) - 5} 个省略")
                        
                    print("="*50)

                response_body = json.dumps({"status": "success", "config": GLOBAL_DEFAULT_CONFIG})
                response = f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(response_body)}\r\n\r\n{response_body}"
                client_conn.sendall(response.encode('utf-8'))
            except Exception as e:
                with print_lock:
                    print(f"❌ 上报解析异常: {e}")
                client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")

        elif "POST /api/control" in request:
            try:
                body = request.split("\r\n\r\n")[1]
                cmd_data = json.loads(body)
                action = cmd_data.get("action")
                payload = cmd_data.get("payload", "")
                
                success = False
                if action == "force_report":
                    success = send_udp_broadcast(f"FIND_ME_REPORT:{RESCUE_SECRET}")
                elif action == "temp_report":
                    success = send_udp_broadcast(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{payload}")
                elif action == "change_server":
                    success = send_udp_broadcast(f"FIND_ME_SERVER:{RESCUE_SECRET}:{payload}")
                elif action == "change_config":
                    success = send_udp_broadcast(f"FIND_ME_CONFIG:{RESCUE_SECRET}:{payload}")

                resp = json.dumps({"status": "ok" if success else "error"})
                response = f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(resp)}\r\n\r\n{resp}"
                client_conn.sendall(response.encode('utf-8'))
            except Exception as e:
                client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")

        elif "GET /" in request:
            html = """<!DOCTYPE html>
<html><head><meta charset='utf-8'><title>设备监控中心</title>
<style>
body{font-family:sans-serif;margin:20px;background:#f4f6f9;}
h2{margin-bottom:5px;}
.panel{background:#fff;padding:15px;border-radius:8px;margin-bottom:20px;box-shadow:0 2px 4px rgba(0,0,0,0.1);}
input{padding:6px;border:1px solid #ccc;border-radius:4px;width:300px;margin-right:10px;}
button{padding:6px 12px;background:#007bff;color:#fff;border:none;border-radius:4px;cursor:pointer;}
button:hover{background:#0056b3;}
table{border-collapse:collapse;width:100%;background:#fff;box-shadow:0 2px 4px rgba(0,0,0,0.1);}
th,td{border:1px solid #ddd;padding:10px;text-align:left;font-size:14px;}
th{background:#f0f0f0;}
/* 新增弹出列表的样式 */
details { cursor: pointer; color: #007bff; }
ul { margin: 5px 0 0 0; padding-left: 20px; font-size: 12px; color: #555; }
</style>
</head>
<body>
<h2>🛡️ 备用机矩阵控制台</h2>

<div class="panel">
    <h3>⚡ 快捷操作 (UDP 广播)</h3>
    <button onclick="sendCmd('force_report')">强制手机上报一次</button>
    <br><br>
    <input type="text" id="tempUrl" placeholder="输入临时上报 URL，如 http://192.168.1.50:8000/...">
    <button onclick="sendCmd('temp_report', document.getElementById('tempUrl').value)">发往临时URL</button>
    <br><br>
    <input type="text" id="newServer" placeholder="输入新服务器地址进行热切换">
    <button onclick="sendCmd('change_server', document.getElementById('newServer').value)">更换主服务器</button>
</div>

<div class="panel">
    <h3>📱 设备状态列表 <button onclick="location.reload()" style="float:right;background:#28a745;">手动刷新</button></h3>
    <table>
        <tr><th>设备ID</th><th>电量</th><th>Wi-Fi</th><th>经纬度</th><th>定位时间</th><th>收到时间</th><th>周围热点</th></tr>
"""

            for dev_id, info in ALL_DEVICES_STATUS.items():
                nearby = info.get('nearby_wifi', [])
                
                # 【新增】生成展开列表 HTML
                ap_list_html = ""
                if nearby:
                    ap_list_html = "<details><summary><b>" + str(len(nearby)) + " 个</b></summary><ul>"
                    for ap in nearby:
                        ap_list_html += f"<li>{ap.get('ssid')} ({ap.get('rssi')} dBm)</li>"
                    ap_list_html += "</ul></details>"
                else:
                    ap_list_html = "0 个"

                html += f"<tr><td><b>{dev_id}</b></td><td>{info.get('battery', {}).get('level', '?')}%</td>"
                html += f"<td>{info.get('network', {}).get('wifi_ssid', '?')}</td>"
                html += f"<td>{info.get('location', {}).get('latitude')}, {info.get('location', {}).get('longitude')}</td>"
                html += f"<td>{format_loc_time(info.get('location', {}).get('time', 0))}</td>"
                html += f"<td>{format_loc_time(info.get('received_at', 0))}</td>"
                # 替换为可展开的 HTML
                html += f"<td>{ap_list_html}</td></tr>"
                
            if not ALL_DEVICES_STATUS:
                html += "<tr><td colspan='7' align='center'>暂无设备上报</td></tr>"

            html += """</table></div>
<script>
async function sendCmd(action, payload = "") {
    if(payload === "" && (action === 'temp_report' || action === 'change_server')) {
        alert("请先填写输入框内容！"); return;
    }
    try {
        const res = await fetch('/api/control', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({action: action, payload: payload})
        });
        const data = await res.json();
        if(data.status === 'ok') {
            alert("指令发送成功！请观察手机响应。");
        } else {
            alert("发送失败，请检查服务器控制台。");
        }
    } catch(e) { alert("网络错误: " + e); }
}
</script>
</body></html>"""
            response = f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(html.encode('utf-8'))}\r\n\r\n{html}"
            client_conn.sendall(response.encode('utf-8'))
            
        else:
            client_conn.sendall(b"HTTP/1.1 404 Not Found\r\n\r\n")
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
        print(f"🚀 全功能控制中心已启动: http://{get_local_ip()}:{PORT}")
        print("👉 请在浏览器打开该地址，体验可视化控制面板！")
        print("="*50)

    while True:
        try:
            conn, addr = server.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
        except KeyboardInterrupt: break
        except Exception: pass

if __name__ == "__main__":
    start_server()
