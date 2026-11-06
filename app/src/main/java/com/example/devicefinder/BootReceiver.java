package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * BootReceiver — 开机自启入口
 * 构建版本 : 20261107-1200
 *
 * 2026-11-07 加固：
 * 1. 保留 startForegroundService 主通道（BOOT_COMPLETED 属 Android 12+ FGS 启动豁免白名单）。
 * 2. 增加 catch 兜底通道：个别 ROM 在开机瞬间因 location 类型 FGS 限制而拦截时，
 *    立即调度 KeepAliveJobService（JobScheduler 不受 FGS 启动限制），15 分钟内由系统拉活。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        FileLogger.log(context, "BOOT", "📱 开机广播触发: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Intent serviceIntent = new Intent(context, TrackingService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                FileLogger.log(context, "BOOT", "✅ [通道1] 已拉起 TrackingService");
            } catch (Exception e) {
                // ★ 2026-11-07 加固：Android 14+ 对 BOOT_COMPLETED 启动 location 型 FGS 有限制，
                //   部分 ROM 会直接抛 ForegroundServiceStartNotAllowedException。
                //   降级通道：调度 JobScheduler，系统会在 15 分钟内拉活。
                FileLogger.log(context, "BOOT", "⚠️ [通道1] 前台服务被拦截: " + e.getMessage() + "，启动通道2 JobScheduler");
                try {
                    KeepAliveJobService.scheduleJob(context);
                    FileLogger.log(context, "BOOT", "✅ [通道2] 已注入 JobScheduler 保活心跳");
                } catch (Exception jobEx) {
                    FileLogger.log(context, "BOOT", "❌ [通道2] 调度失败: " + jobEx.getMessage());
                }
            }
        }
    }
}
