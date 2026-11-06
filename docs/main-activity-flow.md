# MainActivity 引导流程与状态机说明

> 本文档对应 `com.example.devicefinder.MainActivity.java`
> 目的：帮助开发者快速理解首装引导的流转过程、状态字段的作用，以及各类防死循环机制。

---

## 1. 流程概览图

```mermaid
graph TD
    Start((Launcher 点击)) --> A[onCreate: 恢复状态]
    A --> B[onResume: 按 waitingFor 标志分发]
    
    B -->|首次进入| C[1. 精确闹钟]
    C -->|去设置| WaitAlarm((等待返回))
    C -->|暂不开启| D[2. 运行时权限]
    
    D -->|全给/有拒| E[3. 后台定位]
    E -->|API30+ 弹框 / API29 系统框| F[4. 通知权限]
    
    F --> G[5. 电池优化]
    G -->|去设置| WaitBatt((等待返回))
    G -->|暂时跳过| H[6. 悬浮窗]
    
    H -->|去设置| WaitOverlay((等待返回))
    H -->|暂不开启| I[7. 设备管理器]
    
    I -->|去激活| WaitAdmin((等待返回))
    I -->|暂不开启| J((拉起 TrackingService))
    
    J -->|失败| Fail[Toast 报错 + finish 不隐藏图标]
    J -->|成功| K[8. 无障碍引导]
    
    K -->|已开启| End[finishWithToast 收尾]
    K -->|去设置| WaitAcc((等待返回))
    K -->|暂不开启/返回键| End
    
    WaitAlarm -.-> B
    WaitBatt -.-> B
    WaitOverlay -.-> B
    WaitAdmin -.-> B
    WaitAcc -.-> B
