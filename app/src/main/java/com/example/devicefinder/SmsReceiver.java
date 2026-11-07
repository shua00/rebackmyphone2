package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;

/**
 * SmsReceiver — 短信指令接收器
 * 构建版本 : 20261107-2000 (彻底封板完全体)
 */
public class SmsReceiver extends BroadcastReceiver {
    private static final String RESCUE_SECRET = "your_private_secret_key";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        // ★ 核心加固 1：必须在主线程第 0 毫秒强行斩断广播！
        // 确保系统 AMS 优先识别到拦截标记，把明文密码彻底隔绝在系统收件箱之外
        try {
            abortBroadcast();
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "⚠️ 主线程前置拦截被拒（非默认短信App，预期行为）: " + e.getMessage());
        }

        // ★ 核心加固 2：抢 WakeLock，防止 CPU 在子线程处理时打盹
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceFinder::SmsReceiver");
            wakeLock.acquire(30000); 
        }

        // ★ 核心加固 3：使用 goAsync() 挂起锁，将耗时操作移入子线程，防 10 秒 ANR 强杀
        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                processSms(context, intent);
            } catch (Exception e) {
                FileLogger.log(context, "SMS", "❌ 短信处理异常: " + e.getMessage());
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                if (pendingResult != null) {
                    pendingResult.finish();
                }
            }
        }).start();
    }

    private void processSms(Context context, Intent intent) {
        try {
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            if (pdus == null) return;
            StringBuilder fullMessage = new StringBuilder();
            String sender = null;
            long receivedTime = 0;

            String format = intent.getStringExtra("format");
            for (Object pdu : pdus) {
                SmsMessage sms;
                if (Build.VERSION.SDK_INT >= 23) {
                    sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    sms = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (sender == null) {
                    sender = sms.getOriginatingAddress();
                    receivedTime = sms.getTimestampMillis();
                }
                fullMessage.append(sms.getMessageBody());
            }
            String message = fullMessage.toString().trim();
            boolean isCommand = false;

            FileLogger.log(context, "SMS", "📩 收到短信 from=" + sender
                    + ", body=" + (message.length() > 40 ? message.substring(0, 40) + "..." : message));

            if (message.startsWith("FIND_ME:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_UPDATE_SERVER", "server_url", parts[2].trim());
                    isCommand = true;
                }
            } else if (message.startsWith("GET_LOC:")) {
                String[] parts = message.split(":");
                if (parts.length >= 2 && RESCUE_SECRET.equals(parts[1]) && sender != null) {
                    startService(context, "ACTION_FORCE_REPORT", null, null);
                    if (parts.length >= 3 && "reply".equalsIgnoreCase(parts[2])) {
                        replyLocationViaSms(context, sender);
                    }
                    isCommand = true;
                }
            } else if (message.startsWith("SET_CONFIG:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_UPDATE_CONFIG_URL", "config_url", parts[2].trim());
                    isCommand = true;
                }
            } else if (message.startsWith("GET_ALARM:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_PLAY_ALARM", null, null);
                    isCommand = true;
                }
            } else if (message.startsWith("SHOW_MSG:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_SHOW_MSG", "message", parts[2].trim());
                    isCommand = true;
                }
            } else if (message.startsWith("LOCK_CHAT:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_LOCK_CHAT", null, null);
                    isCommand = true;
                }
            } else if (message.startsWith("UNLOCK_CHAT:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_UNLOCK_CHAT", null, null);
                    isCommand = true;
                }
            } else if (message.startsWith("SET_SMS_NUM:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    context.getSharedPreferences("device_finder_prefs", Context.MODE_PRIVATE).edit()
                            .putString("emergency_sms_number", parts[2].trim()).apply();
                    sendSmsCompat(context, sender, "✅ 紧急报警号码已更新为: " + parts[2].trim());
                    isCommand = true;
                }
            } else if (message.startsWith("ELDER_MODE:")) {
                String[] parts = message.split(":", 3);
                if (parts.length >= 2 && RESCUE_SECRET.equals(parts[1])) {
                    String val = parts.length >= 3 ? parts[2].trim() : null;
                    startService(context, "ACTION_TOGGLE_ELDER_MODE",
                            val != null ? "elder_mode" : null, val);
                    isCommand = true;
                }
            }

            if (isCommand) {
                FileLogger.log(context, "SMS", "✅ 指令已识别，执行中");
                // 尝试从收件箱删除短信
                try {
                    Uri uri = Uri.parse("content://sms/inbox");
                    String where = "address=? AND date=?";
                    String[] args = new String[]{sender, String.valueOf(receivedTime)};
                    context.getContentResolver().delete(uri, where, args);
                } catch (Exception e) {
                    FileLogger.log(context, "SMS", "⚠️ 删除指令短信失败（非默认短信应用，预期行为）: " + e.getMessage());
                }
                // 注意：这里不再调用 abortBroadcast()，因为已经在 onReceive 主线程第一现场干掉了！
            } else {
                FileLogger.log(context, "SMS", "非指令短信，忽略");
            }
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ 短信解析异常: " + e.getMessage());
        }
    }

    private void startService(Context context, String action, String extraKey, String extraValue) {
        Intent serviceIntent = new Intent(context, TrackingService.class);
        serviceIntent.setAction(action);
        if (extraKey != null) serviceIntent.putExtra(extraKey, extraValue);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent);
            else context.startService(serviceIntent);
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ startService 失败 action=" + action + ": " + e.getMessage());
        }
    }

    private void replyLocationViaSms(Context context, String phoneNumber) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Location loc = null;
            if (lm != null) {
                Location netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                Location gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (netLoc != null && gpsLoc != null) loc = netLoc.getTime() > gpsLoc.getTime() ? netLoc : gpsLoc;
                else if (netLoc != null) loc = netLoc;
                else loc = gpsLoc;
            }

            int batteryPct = TrackingService.GLOBAL_BATTERY_LEVEL;

            StringBuilder sb = new StringBuilder("【设备回复】\n电量: ")
                    .append(batteryPct >= 0 ? batteryPct + "%" : "未知").append("\n");
            if (loc != null) {
                sb.append("经纬度: ").append(loc.getLatitude()).append(", ").append(loc.getLongitude()).append("\n");
                sb.append("精度: ").append(loc.getAccuracy()).append("米\n");
                sb.append("时间: ").append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(loc.getTime())));
            } else sb.append("经纬度: 无法获取\n");

            sendSmsCompat(context, phoneNumber, sb.toString());
            FileLogger.log(context, "SMS", "📤 已回复定位短信 → " + phoneNumber);
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ 回复定位短信失败: " + e.getMessage());
        }
    }

    /**
     * ★ 核心加固 4：双卡发送自适应 + 阉割版 ROM 防摔沙箱
     */
    private void sendSmsCompat(Context context, String phoneNumber, String content) {
        if (phoneNumber == null || content == null) return;
        try {
            SmsManager smsManager = null;
            
            // 严格捕获 Throwable，防止低端定制 ROM 缺少多卡模块引发 NoClassDefFoundError 闪退
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    smsManager = context.getSystemService(SmsManager.class);
                    SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    if (subManager != null) {
                        int subId = subManager.getDefaultSmsSubscriptionId();
                        if (subId != -1) { // INVALID_SUBSCRIPTION_ID 原生值为 -1
                            smsManager = context.getSystemService(SmsManager.class).createForSubscriptionId(subId);
                        }
                    }
                } catch (Throwable t) {
                    FileLogger.log(context, "SMS", "⚠️ 多卡槽底层组件缺失或被阉割，启动原生保底通道");
                    smsManager = null;
                }
            }

            // 终极自愈：如果上面高版本或多卡槽创建失败，无痕回退到兼容性最高的原生默认通道
            if (smsManager == null) {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                smsManager.sendTextMessage(phoneNumber, null, content, null, null);
            }
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ sendSmsCompat 硬件分发彻底失败: " + e.getMessage());
        }
    }
}
