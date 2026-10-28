package com.example.devicefinder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

public class DialerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SECRET_CODE".equals(intent.getAction())) return;

        Uri data = intent.getData();
        if (data == null) return;
        String host = data.getHost();
        if (host == null) return;

        switch (host) {
            case "8866": // 进主界面
                launchActivity(context, new Intent(context, MainActivity.class), "主界面");
                break;
            case "7788": // 进对讲机
                launchActivity(context, new Intent(context, ChatActivity.class), "对讲机");
                break;
            case "5566": // 弹留言板（悬浮窗，不切界面）
                showHistoryViaService(context);
                break;
            case "9900": // 强制上报（扩展示例）
                forceReportViaService(context);
                break;
            default:
                break;
        }
    }

    /**
     * 尝试直接 startActivity；Android 10+ 后台启动限制可能拦截，
     * 失败时降级为发一条通知，用户点通知进入。
     */
    private void launchActivity(Context context, Intent target, String label) {
        target.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(target);
        } catch (Exception e) {
            postFallbackNotification(context, label, target);
        }
    }

    /**
     * 留言板走 startService + WindowManager.addView，不触发后台启动限制，最稳。
     */
    private void showHistoryViaService(Context context) {
        Intent svc = new Intent(context, TrackingService.class);
        svc.setAction("ACTION_SHOW_HISTORY");
        startServiceCompat(context, svc);
    }

    private void forceReportViaService(Context context) {
        Intent svc = new Intent(context, TrackingService.class);
        svc.setAction("ACTION_FORCE_REPORT");
        startServiceCompat(context, svc);
    }

    private void startServiceCompat(Context context, Intent svc) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {}
    }

    /**
     * startActivity 被拦时的兜底：发一条通知，用户点通知进入。
     */
    private void postFallbackNotification(Context context, String label, Intent target) {
        try {
            String channelId = "dialer_fallback_channel";
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "暗号入口", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }

            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(context, label.hashCode(), target, piFlags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, channelId);
            } else {
                builder = new Notification.Builder(context);
            }
            Notification n = builder
                    .setContentTitle("暗号入口")
                    .setContentText("点击进入「" + label + "」")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();

            nm.notify(label.hashCode(), n);
        } catch (Exception ignored) {}
    }
}
