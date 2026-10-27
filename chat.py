import socket
import threading
import datetime
import json
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

# ================= 配置区 =================
WEB_PORT = 8081
UDP_PORT = 8888
RESCUE_SECRET = "your_private_secret_key"  # ⚠️ 必须与手机端一致
SERVER2_URL = "http://127.0.0.1:8080"      # server2.py 的地址
# ==========================================

PROXY_MODE = False          # 由启动时探测决定
CHAT_MESSAGES = []
print_lock = threading.Lock()

# 极简 HTML 网页（与原来完全一致，无需改动）
HTML_PAGE = """<!DOCTYPE html>
<html><head><meta charset='utf-8'>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>局域网对讲机</title>
<style>
* { -webkit-tap-highlight-color: transparent !important; outline: none !important; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; margin: 0; background: #f6f8fa; color: #24292f; height: 100vh; display: flex; flex-direction: column; }
.header { background: #fff; border-bottom: 1px solid #d0d7de; padding: 12px 20px; font-weight: 600; font-size: 16px; text-align: center; }
#chatBox { flex: 1; display: flex; flex-direction: column; max-width: 900px; margin: 20px auto; width: 95%; background: #fff; border-radius: 8px; border: 1px solid #d0d7de; box-shadow: 0 2px 8px rgba(0,0,0,0.05); overflow: hidden; }
#chatMessages { flex: 1; overflow-y: auto; padding: 20px; font-size: 14px; line-height: 1.6; }
.chat-msg { margin-bottom: 12px; }
.chat-time { color: #8c959f; font-size: 12px; margin-right: 8px; }
.chat-sender { font-weight: 600; margin-right: 8px; }
.chat-content { background: #f0f3f6; padding: 6px 12px; border-radius: 6px; display: inline-block; word-break: break-all; }
.chat-input-area { display: flex; padding: 15px; border-top: 1px solid #d0d7de; background: #f6f8fa; gap: 10px; }
input[type="text"] { flex: 1; padding: 10px 14px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 15px; }
input[type="text"]:focus { border-color: #0969da; box-shadow: 0 0 0 3px rgba(9,105,218,0.3); }
button { padding: 10px 20px; background: #24292f; color: #fff; border: none; border-radius: 6px; font-size: 15px; font-weight: 600; cursor: pointer; }
button:hover { background: #1b1f24; }
</style>
</head>
<body>
<div class="header">🗣️ 局域网对讲机 (轻量版)</div>
<div id="chatBox">
    <div id="chatMessages"></div>
    <div class="chat-input-area">
        <input type="text" id="chatInput" placeholder="输入消息发送..." onkeypress="handleEnter(event)">
        <button onclick="sendChat()">发送</button>
    </div>
</div>
<script>
var lastLen = 0;
const myName = "Guest_" + Math.floor(Math.random() * 900 + 100);

function handleEnter(e) { if (e.key === 'Enter') sendChat(); }

async function sendChat() {
    let msg = document.getElementById('chatInput').value.trim();
    if(!msg) return;
    document.getElementById('chatInput').value = "";
    await fetch('/send', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({content: msg, sender: myName})
    });
    setTimeout(fetchChatHistory, 200);
}

async function fetchChatHistory() {
    try {
        const res = await fetch('/history');
        const data = await res.json();
        const chatDiv = document.getElementById('chatMessages');

        if (data.length === lastLen) return;
        lastLen = data.length;

        let html = "";
        data.forEach(item => {
            let senderColor = item.sender.includes("Guest") ? "#8b949e" : "#0969da";
            html += `<div class="chat-msg">
                <span class="chat-time">${item.time}</span>
                <span class="chat-sender" style="color:${senderColor}">[${item.sender}]</span>
                <div class="chat-content">${item.content}</div>
            </div>`;
        });
        chatDiv.innerHTML = html;
        chatDiv.scrollTop = chatDiv.scrollHeight;
    } catch(e) {}
    setTimeout(fetchChatHistory, 1500);
}
fetchChatHistory();
</script>
</body></html>"""


def is_server2_running():
    """探测 server2.py 的 Web 端口是否在线（1 秒超时）。"""
    try:
        urllib.request.urlopen(SERVER2_URL + "/api/chat_history", timeout=1)
        return True
    except Exception:
        return False


# UDP 监听线程：仅独立模式启动
def udp_listener():
    udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        udp_sock.bind(('0.0.0.0', UDP_PORT))
        print(f"🎧 UDP 监听已启动 (端口 {UDP_PORT})，等待对讲机消息...")
        while True:
            data, addr = udp_sock.recvfrom(1024)
            msg = data.decode('utf-8', errors='ignore')
            if msg.startswith("FIND_ME_MSG:"):
                parts = msg.split(":", 4)
                if len(parts) == 4 and parts[1] == RESCUE_SECRET:
                    sender = parts[2]
                    content = parts[3]
                    if not sender.startswith("Guest_") and sender != "Web_Console":
                        ts = datetime.datetime.now().strftime("%H:%M:%S")
                        with print_lock:
                            print(f"\n🗣️ [对讲机] {sender} 说: {content}")
                            CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                            if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
    except OSError as e:
        if e.errno == 98:
            print(f"❌ UDP {UDP_PORT} 被占用（可能 server2.py 已在运行）。")
            print(f"   本网页仍可打开，但无法接收手机消息。建议直接访问 {SERVER2_URL}/ 使用内置对讲机。")
        else:
            print(f"❌ UDP 端口 {UDP_PORT} 绑定失败: {e}")
    except Exception as e:
        print(f"❌ UDP 监听异常: {e}")


class RequestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/history':
            if PROXY_MODE:
                # 代理模式：从 server2.py 拉取
                try:
                    raw = urllib.request.urlopen(SERVER2_URL + "/api/chat_history", timeout=3).read()
                    payload = json.loads(raw)
                    out = json.dumps(payload.get("messages", []))
                except Exception:
                    out = json.dumps([])
            else:
                with print_lock:
                    out = json.dumps(CHAT_MESSAGES)
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(out.encode('utf-8'))
        else:
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(HTML_PAGE.encode('utf-8'))

    def do_POST(self):
        if self.path == '/send':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length).decode('utf-8')
            data = json.loads(body)
            sender = data.get("sender", "Web_Guest")
            content = data.get("content", "")

            if content:
                if PROXY_MODE:
                    # 代理模式：交给 server2.py 的 /api/control 统一广播 + 记录
                    try:
                        req = urllib.request.Request(
                            SERVER2_URL + "/api/control",
                            data=json.dumps({
                                "action": "chat_msg",
                                "payload": content,
                                "target_ip": "all"
                            }).encode('utf-8'),
                            headers={"Content-Type": "application/json"})
                        urllib.request.urlopen(req, timeout=3)
                    except Exception as e:
                        print(f"❌ 代理发送失败: {e}")
                else:
                    # 独立模式：本地记录 + UDP 广播
                    ts = datetime.datetime.now().strftime("%H:%M:%S")
                    with print_lock:
                        print(f"\n💻 [网页] {sender} 说: {content}")
                        CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                        if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)

                    payload = f"FIND_ME_MSG:{RESCUE_SECRET}:{sender}:{content}"
                    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    try:
                        sock.sendto(payload.encode('utf-8'), ('255.255.255.255', UDP_PORT))
                    except Exception:
                        pass
                    finally:
                        sock.close()

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')

    def log_message(self, format, *args):
        pass


if __name__ == '__main__':
    PROXY_MODE = is_server2_running()

    print("=" * 50)
    if PROXY_MODE:
        print("🔗 检测到 server2.py 在线 → chat.py 进入【代理模式】")
        print(f"   聊天记录从 {SERVER2_URL}/api/chat_history 拉取")
        print(f"   发送走 {SERVER2_URL}/api/control (action=chat_msg)")
        print("   不再 bind UDP 8888，与 server2.py 零冲突")
    else:
        print("🔌 未检测到 server2.py → chat.py 进入【独立模式】")
        print(f"   自行监听 UDP {UDP_PORT}，行为与原版一致")
        threading.Thread(target=udp_listener, daemon=True).start()
    print(f"👉 浏览器访问: http://127.0.0.1:{WEB_PORT}")
    print("=" * 50)

    server = HTTPServer(('0.0.0.0', WEB_PORT), RequestHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n👋 退出。")
