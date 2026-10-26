import socket
import threading
import datetime
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

# ================= 配置区 =================
WEB_PORT = 8081
UDP_PORT = 8888
RESCUE_SECRET = "your_private_secret_key"  # ⚠️ 必须与手机端一致
# ==========================================

# 用于保存在内存中的聊天记录
CHAT_MESSAGES = []
print_lock = threading.Lock()

# 极简 HTML 网页
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
// 每次打开网页，随机生成一个网名，例如 Guest_382
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

# UDP 监听线程：负责接收手机或其他电脑发来的消息
def udp_listener():
    udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        udp_sock.bind(('0.0.0.0', UDP_PORT))
        print(f"🎧 UDP 监听已启动 (端口 {UDP_PORT})，等待对讲机消息...")
        while True:
            data, addr = udp_sock.recvfrom(1024)
            msg = data.decode('utf-8', errors='ignore')
            # 解析 4 段式报文：FIND_ME_MSG:密钥:发送者:内容
            if msg.startswith("FIND_ME_MSG:"):
                parts = msg.split(":", 4)
                if len(parts) == 3 and parts[1] == RESCUE_SECRET:
                    # 3段式（来自网页/短信提示），不算入聊天室
                    pass
                elif len(parts) == 4 and parts[1] == RESCUE_SECRET:
                    sender = parts[2]
                    content = parts[3]
                    # 过滤掉自己发出去的广播，防止重复显示
                    if not sender.startswith("Guest_") and sender != "Web_Console":
                        ts = datetime.datetime.now().strftime("%H:%M:%S")
                        with print_lock:
                            print(f"\n🗣️ [对讲机] {sender} 说: {content}")
                            CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                            if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
    except Exception as e:
        print(f"❌ UDP 端口 {UDP_PORT} 绑定失败，可能被主控台 server2.py 占用！\n报错: {e}")

# HTTP 服务处理
class RequestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/history':
            with print_lock:
                resp = json.dumps(CHAT_MESSAGES)
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(resp.encode('utf-8'))
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
                # 1. 记录到本地内存
                ts = datetime.datetime.now().strftime("%H:%M:%S")
                with print_lock:
                    print(f"\n💻 [网页] {sender} 说: {content}")
                    CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                    if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
                
                # 2. 发送 UDP 广播到局域网（让手机收到）
                payload = f"FIND_ME_MSG:{RESCUE_SECRET}:{sender}:{content}"
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                try:
                    sock.sendto(payload.encode('utf-8'), ('255.255.255.255', UDP_PORT))
                except: pass
                finally: sock.close()
                
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            
    def log_message(self, format, *args):
        pass # 屏蔽原生 HTTP 日志，保持控制台干净

if __name__ == '__main__':
    # 启动 UDP 监听
    threading.Thread(target=udp_listener, daemon=True).start()
    
    print("="*50)
    print(f"🚀 局域网轻量对讲机已启动")
    print(f"👉 请在浏览器访问: http://127.0.0.1:{WEB_PORT}")
    print("="*50)
    
    # 启动网页服务
    server = HTTPServer(('0.0.0.0', WEB_PORT), RequestHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n👋 退出。")
