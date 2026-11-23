# 提交差异核对版变更记录

> 本记录以提交差异、关键文件注释、最终源码和提交间净变化为依据；提交标题和说明只作辅助线索。
> 范围：`6b4c74fb8d648cb42611bc0e6f8a9cb478ebb847` 至 `bf34392a2f210d6e71692eb24ebd6228bfce00ac`。
> 每一条都来自实际保留到当前代码中的实现。纯文本、注释格式、临时切换或已撤销实现不列为正式功能。

## 1. 初始架构与基础工程

提交锚点：`6b4c74fb8d648cb42611bc0e6f8a9cb478ebb847`。

实际新增了：

- Android Gradle 工程、`app` 模块、JDK 17 和 Android 34 编译配置。
- GitHub Actions APK 构建流程。
- `MainActivity`：定位权限申请、手动上报入口和基础状态界面。
- `TrackingService`：前台服务、定位监听、周期上报、设备状态采集。
- `DeviceInfoUtils`：电量、充电状态、Wi-Fi、BSSID、RSSI 和本机 IPv4。
- Android Manifest：网络、定位、前台服务、通知、短信等基础权限和组件声明。
- 局域网救援脚本、基础 Python Socket 服务端和初始 Web 状态页。

## 2. 定位、上报和网络信息

后续提交实际演进出：

- 定时上报闹钟、网络变化上报、手动上报和临时服务器上报。
- 多来源定位回退：GPS、NETWORK、PASSIVE、last known location。
- 单次定位并上报，并增加定位超时和定位年龄。
- 定位 provider 日志及 Android 15 下网络定位兼容处理。
- Android 13+ `NEARBY_WIFI_DEVICES` 权限。
- 周边 Wi-Fi 扫描、扫描缓存回退、按 RSSI 排序、最多保留 15 个热点。
- 移除部分 SDK 中不可用的隐藏 API 节流检查。
- 本地 IP 优先选择 Wi-Fi 网卡；移动网络和其他网卡作为回退。
- 网络类型、电池温度、电池电压、屏幕状态和流量快照。

## 3. 后台常驻、保活和自愈

实际保留的实现包括：

- 前台通知和前台服务。
- WakeLock、WifiLock 和组播锁。
- 保活闹钟、心跳闹钟和上报闹钟。
- `KeepAliveJobService` JobScheduler 保底唤醒。
- 无障碍保活入口。
- `onTaskRemoved()` 划掉任务后的 AlarmManager 和 JobScheduler 自救。
- 看门狗线程检查成功上报时间并触发自愈。
- 成功上报时间持久化到 SharedPreferences。
- 网络、定位和 UDP 启动失败后的重新注册与重试。
- Android 14+ 前台服务类型兼容：location 优先，失败回退 dataSync。
- 前台通知去重和强制刷新两种路径。

相关关键提交：

- 无障碍保活：`83e03dbda2d146e90ae7be1e195c3ba38399520f`
- 网络和保活修复：`a9e72123a39c14e7a239d07a8256413db7d77694`
- 前台通知修复：`50f1fb` 系列及 `bf34392` 前序提交

## 4. 短信、UDP 和远程指令

提交差异实际增加了：

- `SmsReceiver`：短信指令解析、goAsync、WakeLock 和双卡发送适配。
- 短信定位回复、报警短信、紧急号码、聊天锁定和老人模式。
- 8888 端口 UDP 监听及绑定失败指数重试。
- 子网广播和全局广播双发。
- 绑定随机端口后尝试限制到 Wi-Fi 网络出口。
- 悬浮文字、聊天和旧短信协议兼容。
- UDP 消息去重。
- 短信和 UDP 远程控制的异常保护。

协议演进：

- `FIND_ME_MSG`：旧版兼容协议。
- `FIND_ME_FLOAT`：v2 悬浮文字。
- `FIND_ME_CHAT`：v2 聊天。
- `BuildInfo.PROTOCOL_VERSION = 2`。

## 5. 聊天、悬浮留言和老人模式

实际保留的功能：

- `ChatActivity` 聊天界面和聊天历史。
- 聊天清空、锁定、解锁和新消息刷新。
- 聊天文字选择。
- 悬浮留言、多泡拖动和留言历史。
- 长按复制支持。
- 老人模式开关及脱敏通知。
- 云端日志开关和通知渠道。
- 拨号盘暗号入口：主界面、聊天、留言、强制上报、老人模式和自检。

## 6. 日志、云端日志和数据分析

实际新增：

- `FileLogger` 单线程写入、按日轮转和内存快照。
- 日志保留天数、最大文件大小和异常 I/O 保护。
- 云端日志开关、gzip 日志上传和日志下载。
- 上传日志和历史日志目录管理。
- `analyze_reports.py`：
  - 每日汇总；
  - 健康分析；
  - 上报间隔和失联事件；
  - 坐标跳变；
  - 24 小时分布；
  - 通勤推断；
  - CSV 导出。

## 7. 服务端和并发可靠性

提交差异实际保留了：

- 独立健康检查端口。
- ThreadPoolExecutor 和有界请求队列。
- SQLite 写锁、busy timeout 和 WAL 模式。
- `reports` 表持久化和索引。
- 离线设备检测及离线告警。
- HTTP 下行指令队列、指令 TTL、确认和去重。
- 聊天、留言、日志和设备状态接口。
- 单设备删除、离线设备清理和批量清理。
- POST 按 `Content-Length` 完整读取。
- 非法 JSON、段包、数据库异常和空响应处理。
- 前端输出转义和基础 XSS 防护。
- `faulthandler` 和服务假死诊断支持。
