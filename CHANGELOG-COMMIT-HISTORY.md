# DeviceFinder 提交变更日志

> 本日志以提交差异、提交时间、关键源码注释和最终净效果为依据。提交标题只作为辅助线索，不作为事实依据。

## 完整明细

- [2026-10-21 至 2026-10-28](CHANGELOG-2026-10-21-TO-2026-10-28.md)
- [2026-10-29 至 2026-11-04](CHANGELOG-2026-10-29-TO-2026-11-04.md)
- [2026-11-05 至 2026-11-11](CHANGELOG-2026-11-05-TO-2026-11-11.md)

## 时间范围与说明

- 最早提交：`6b4c74fb8d648cb42611bc0e6f8a9cb478ebb847`，2026-10-21 16:21 UTC。
- 明细分页覆盖到仓库分析时可见的最新提交 `bf34392a2f210d6e71692eb24ebd6228bfce00ac`。
- 提交时间使用 Git 记录中的 UTC 作者时间。
- 连续修复同一问题的提交合并为一个变更节点，保留参与提交的 SHA。
- 纯打印文案、纯注释、格式化、临时回退和已被后续提交替代的过渡实现不计为最终功能变更。

## 核心模块

- Android：`MainActivity`、`TrackingService`、`SmsReceiver`、`DialerReceiver`、`BootReceiver`、`ChatActivity`、无障碍和保活服务。
- 数据与日志：设备上报、SQLite、离线队列、`FileLogger`、云端日志和 `analyze_reports.py`。
- 网络控制：UDP 8888、局域网救援、短信控制、`FIND_ME_*` 协议、HTTP 下行队列。
- 服务端：`server.py`、`server2.py`、聊天代理、健康检查、并发池和离线设备管理。
- 运维工具：`rescue.py`、`rescue2.py`、`chat.py`、ADB 调试和日志拉取。
