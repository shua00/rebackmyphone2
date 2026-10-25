import socket
import time
import sys

RESCUE_SECRET = "your_private_secret_key"  # ⚠️ 必须与手机端一致
DEFAULT_SERVER_URL = "http://dingwei.bai001.de5.net/api/report"
DEFAULT_CONFIG_URL = "https://your-config-url.com/config.txt"

def send_udp(command_str, target_ip="255.255.255.255"):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    
    print(f"\n📡 发送指令至 {target_ip}: {command_str}")
    for i in range(3):
        sock.sendto(command_str.encode('utf-8'), (target_ip, 8888))
        print(f"   [{i+1}/3] 发送中...")
        time.sleep(1)
    sock.close()
    print("✅ 发送完毕。请查看手机响应。\n")

def main():
    while True:
        print("="*45)
        print("🛠️  局域网轻量级救援/调试终端")
        print("="*45)
        print("1. 强制向【当前主服务器】上报一次")
        print("2. 强制向【临时服务器】上报一次 (无痕调试)")
        print("3. 更换手机的主服务器地址")
        print("4. 更换手机的配置中心地址")
        print("5. 🔊 强制手机播放警报音 (响铃)")
        print("6. 💬 在手机屏幕显示悬浮文字")
        print("0. 退出")
        print("="*45)
        
        choice = input("请输入选项 (0-6): ").strip()
        
        # 只要不是退出，就询问目标 IP
        target_ip = "255.255.255.255"
        if choice in ['1', '2', '3', '4', '5', '6']:
            ip_input = input("🎯 请输入目标手机 IP (直接回车=全局广播): ").strip()
            if ip_input:
                target_ip = ip_input
        
        if choice == '1':
            send_udp(f"FIND_ME_REPORT:{RESCUE_SECRET}", target_ip)
            
        elif choice == '2':
            temp_url = input(f"请输入临时 URL (回车默认使用 {DEFAULT_SERVER_URL}): ").strip()
            if not temp_url: temp_url = DEFAULT_SERVER_URL
            send_udp(f"FIND_ME_REPORT_TEMP:{RESCUE_SECRET}:{temp_url}", target_ip)
            
        elif choice == '3':
            new_server = input(f"请输入新的主服务器 URL (回车默认 {DEFAULT_SERVER_URL}): ").strip()
            if not new_server: new_server = DEFAULT_SERVER_URL
            send_udp(f"FIND_ME_SERVER:{RESCUE_SECRET}:{new_server}", target_ip)
            
        elif choice == '4':
            new_config = input(f"请输入新的配置中心 URL (回车默认 {DEFAULT_CONFIG_URL}): ").strip()
            if not new_config: new_config = DEFAULT_CONFIG_URL
            send_udp(f"FIND_ME_CONFIG:{RESCUE_SECRET}:{new_config}", target_ip)
            
        elif choice == '5':
            print("\n⚠️ 警告: 手机将以最大音量播放 30 秒警报！")
            send_udp(f"FIND_ME_ALARM:{RESCUE_SECRET}", target_ip)
            
        elif choice == '6':
            msg_text = input("请输入要显示在手机屏幕上的文字: ").strip()
            if msg_text:
                send_udp(f"FIND_ME_MSG:{RESCUE_SECRET}:{msg_text}", target_ip)
            
        elif choice == '0':
            print("👋 退出。")
            break
        else:
            print("❌ 无效选项，请重新输入。\n")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n👋 已手动中断退出。")
        sys.exit(0)
