package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        FileLogger.log(context, "BOOT", "📱 开机广播触发: " + action);

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        // 双保险：即便未来有人误把 directBootAware 改回来，也拒绝在解锁前启动。
        // 原因：TrackingService 会读写 credential-encrypted 的 SharedPreferences，
        // direct boot 阶段访问会崩溃或生成临时 device_id（导致服务端出现双 ID）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null && !um.isUserUnlocked()) {
                FileLogger.log(context, "BOOT", "⏸ 用户未解锁，跳过本次启动，等解锁后系统再派发 BOOT_COMPLETED");
                return;
            }
        }

        Intent serviceIntent = new Intent(context, TrackingService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            FileLogger.log(context, "BOOT", "✅ 已拉起 TrackingService");
        } catch (Exception e) {
            FileLogger.log(context, "BOOT", "❌ 拉起失败: " + e.getMessage());
        }
    }
}
