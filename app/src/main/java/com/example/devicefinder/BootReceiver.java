package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

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
                FileLogger.log(context, "BOOT", "✅ 已拉起 TrackingService");
            } catch (Exception e) {
                FileLogger.log(context, "BOOT", "❌ 拉起失败: " + e.getMessage());
            }
        }
    }
}
