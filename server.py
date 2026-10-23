import socket
import json
import datetime
import threading

PORT = 8000
AUTH_TOKEN = "your_private_secret_key"

GLOBAL_DEFAULT_CONFIG = {
    "report_interval_seconds": 1800,
    "accuracy_mode": "coarse",
    # "next_server_url": "http://192.168.1.200:8000/api/report",  # 需要换服时填这里
    # "next_config_url": "https://your-new-config-url.com/config.txt", # 换配置中心填这里
    "next_server_url": "",  
    "next_config_url": "",
    "hide_icon": True
}

ALL_DEVICES_STATUS = {}

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

                print("\n" + "="*50)
                print(f"📥 [收到上报] 设备: 【{dev_id}】")
                print(f"🔋 电量: {data.get('battery', {}).get('level')}% (充电中: {data.get('battery', {}).get('is_charging')})")
                print(f"🛜 Wi-Fi: {data.get('network', {}).get('wifi_ssid')} (内网IP: {data.get('network', {}).get('local_ip')})")
                loc = data.get('location', {})
                print(f"📍 定位: {loc.get('latitude')}, {loc.get('longitude')} [{loc.get('provider')}, 精度 {loc.get('accuracy')}m]")
                print(f"🕒 定位时间: {format_loc_time(loc.get('time', 0))}")
                nearby = data.get('nearby_wifi', [])
                print(f"📡 周围 Wi-Fi 热点数: {len(nearby)}")
                print("="*50)

                response_body = json.dumps({"status": "success", "config": GLOBAL_DEFAULT_CONFIG})
                response = f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(response_body)}\r\n\r\n{response_body}"
                client_conn.sendall(response.encode('utf-8'))
            except Exception as e:
                print(f"❌ 上报解析异常: {e}")
                client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
        elif "GET /" in request:
            html = "<html><head><meta charset='utf-8'><title>设备监控</title><style>body{font-family:sans-serif;margin:20px;}table{border-collapse:collapse;}th,td{border:1px solid #ccc;padding:8px;}th{background:#f0f0f0;}</style></head><body>"
            html += "<h2>🛡️ 备用机矩阵控制台</h2>"
            html += f"<h3>在线设备总数: {len(ALL_DEVICES_STATUS)}</h3><table><tr><th>设备ID</th><th>电量</th><th>Wi-Fi</th><th>内网IP</th><th>经纬度</th><th>定位时间</th><th>周围热点</th></tr>"
            for dev_id, info in ALL_DEVICES_STATUS.items():
                html += f"<tr><td>{dev_id}</td><td>{info.get('battery', {}).get('level', '?')}%</td><td>{info.get('network', {}).get('wifi_ssid', '?')}</td><td>{info.get('network', {}).get('local_ip', '?')}</td><td>{info.get('location', {}).get('latitude')}, {info.get('location', {}).get('longitude')}</td><td>{format_loc_time(info.get('location', {}).get('time', 0))}</td><td>{len(info.get('nearby_wifi', []))}</td></tr>"
            html += "</table><script>setTimeout(()=>location.reload(), 30000);</script></body></html>"
            response = f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(html.encode('utf-8'))}\r\n\r\n{html}"
            client_conn.sendall(response.encode('utf-8'))
        else: client_conn.sendall(b"HTTP/1.1 404 Not Found\r\n\r\n")
    except socket.timeout: pass
    except Exception as e: print(f"Socket 异常: {e}")
    finally:
        try: client_conn.close()
        except: pass

def start_socket_server():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: server_socket.bind(('0.0.0.0', PORT))
    except PermissionError: print(f"❌ 端口 {PORT} 被占用，请使用 sudo 运行。"); return
    server_socket.listen(20)
    local_ip = get_local_ip()
    print("="*50)
    print(f"🚀 服务器已启动: http://{local_ip}:{PORT}")
    print("="*50)
    try:
        while True:
            conn, addr = server_socket.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt: print("\n🛑 收到退出信号...")
    finally: server_socket.close(); print("✅ 服务器已停止。")

if __name__ == "__main__":
    start_socket_server()
