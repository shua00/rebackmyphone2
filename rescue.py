import socket
import time

RESCUE_SECRET = "your_private_secret_key"
NEW_SERVER_URL = "http://192.168.1.100:8000/api/report"
NEW_CONFIG_URL = "https://gitee.com/xxx/raw/master/config.txt"

def send_udp(command_str):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    print(f"\n📡 广播指令: {command_str}")
    for i in range(5):
        sock.sendto(command_str.encode('utf-8'), ('255.255.255.255', 8888))
        print(f"   [{i+1}/5] 发送中...")
        time.sleep(1)
    sock.close()
    print("✅ 发送完毕。请查看服务器日志。\n")

if __name__ == "__main__":
    while True:
        print("====================================")
        print("🛠️  局域网调试/救援控制台")
        print("1. 强制向【主服务器】上报一次 (调试)")
        print("2. 强制向【临时服务器】上报一次 (无痕调试)")
        print("3. 更换手机的主服务器地址")
        print("4. 更换手机的配置中心地址")
        print("0. 退出")
        print("====================================")
        
        choice = input("请输入选项 (0-4): ").strip()
        
        if choice == '1':
            send_udp(f"FIND_ME_REPORT:{RESCUE_SECRET}")
        elif choice == '2':
            default_temp = "http://192.168.1.50:8000/api/report"
            temp_url = input(f"请输入临时 URL (回车默认 {default_temp}): ").strip()
            if not temp_url: temp_url = default_temp
            send_udp(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}")
        elif choice == '3':
            send_udp(f"FIND_ME_SERVER:{RESCUE_SECRET}:{NEW_SERVER_URL}")
        elif choice == '4':
            send_udp(f"FIND_ME_CONFIG:{RESCUE_SECRET}:{NEW_CONFIG_URL}")
        elif choice == '0':
            break
        else:
            print("无效选项！")
