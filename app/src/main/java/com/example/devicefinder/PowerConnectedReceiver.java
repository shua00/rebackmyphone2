package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 充电恢复兜底（有限生效）。
 *
 * ⚠️ 注意：ACTION_POWER_CONNECTED 是隐式广播，Android 8+ 上 App 处于后台/stopped 状态时
 * 会被系统拦截（日志可见 "Background execution not allowed"）。
 * 因此本 Receiver 仅在 App 进程活跃、未被 stopped 时才能收到广播，
 * 不能作为"进程被杀后插充电自动恢复"的可靠兜底。
 *
 * 真正的低电量被杀恢复依赖：
 * 1. START_STICKY 让系统在内存压力后尝试重启服务；
 * 2. 用户插充电后手动打开一次 App（解除 stopped 状态）。
 */
public class PowerConnectedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // [L1] 充电插拔日志：确认广播是否收到（Android 8+ 隐式广播可能被拦，日志能区分"没收到"vs"收到了但没执行"）
        FileLogger.log(context, "POWER", "🔌 " + action);

        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            // 仅在广播未被系统拦截时执行（App 活跃状态）。
            // 进程被 force-stop / 长时间后台时此分支不会触发，属预期行为。
            Intent svc = new Intent(context, TrackingService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                FileLogger.log(context, "POWER", "✅ 已拉起 TrackingService"); // [L1]
            } catch (Exception e) {
                FileLogger.log(context, "POWER", "❌ 拉起服务失败: " + e.getMessage()); // [L0]
            }
        }
        // ACTION_POWER_DISCONNECTED 不需要动作，仅接收用于日志/未来扩展
    }
}
