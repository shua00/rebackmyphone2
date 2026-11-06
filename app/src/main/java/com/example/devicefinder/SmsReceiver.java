package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

/**
 * SmsReceiver — 短信指令接收器
 * 构建版本 : 20261107-1200
 *
 * 2026-11-07 修改：
 * 1. abortBroadcast() 包进 try-catch（非默认短信 App 调用部分 ROM 抛 SecurityException）
 * 2. replyLocationViaSms 改读 TrackingService.GLOBAL_BATTERY_LEVEL，
 *    废弃同步 registerReceiver(null) 电池查询（降低 onReceive 主线程 Binder 往返）
 */
public class SmsReceiver extends BroadcastReceiver {
    private static final String RESCUE_SECRET = "your_private_secret_key";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
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
                    SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? context.getSystemService(SmsManager.class) : SmsManager.getDefault();
                    if (smsManager != null && sender != null) {
                        smsManager.sendTextMessage(sender, null, "✅ 紧急报警号码已更新为: " + parts[2].trim(), null, null);
                    }
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
                try {
                    Uri uri = Uri.parse("content://sms/inbox");
                    String where = "address=? AND date=?";
                    String[] args = new String[]{sender, String.valueOf(receivedTime)};
                    context.getContentResolver().delete(uri, where, args);
                } catch (Exception e) {
                    FileLogger.log(context, "SMS", "⚠️ 删除指令短信失败（非默认短信应用，预期行为）: " + e.getMessage());
                }
                // ★ 2026-11-07 加固：非默认短信 App 下 abortBroadcast() 会被系统忽略，
                //   部分定制 ROM 还可能抛 SecurityException，故必须包 try-catch
                try {
                    abortBroadcast();
                } catch (Exception e) {
                    FileLogger.log(context, "SMS", "⚠️ abortBroadcast 被系统拒绝（非默认短信 App，预期行为）: " + e.getMessage());
                }
            } else {
                FileLogger.log(context, "SMS", "非指令短信，忽略");
            }
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ 短信处理异常: " + e.getMessage());
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

            // ★ 2026-11-07 架构优化：废弃同步 context.registerReceiver(null) 电池查询，
            //   改读 TrackingService 的静态内存变量，消除 onReceive 主线程上的 Binder 往返。
            //   TrackingService 未启动时该值为 -1，下方会显示"未知"，属可接受降级。
            int batteryPct = TrackingService.GLOBAL_BATTERY_LEVEL;

            StringBuilder sb = new StringBuilder("【设备回复】\n电量: ")
                    .append(batteryPct >= 0 ? batteryPct + "%" : "未知").append("\n");
            if (loc != null) {
                sb.append("经纬度: ").append(loc.getLatitude()).append(", ").append(loc.getLongitude()).append("\n");
                sb.append("精度: ").append(loc.getAccuracy()).append("米\n");
                sb.append("时间: ").append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(loc.getTime())));
            } else sb.append("经纬度: 无法获取\n");

            SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? context.getSystemService(SmsManager.class) : SmsManager.getDefault();
            if (smsManager != null) smsManager.sendTextMessage(phoneNumber, null, sb.toString(), null, null);
            FileLogger.log(context, "SMS", "📤 已回复定位短信 → " + phoneNumber);
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ 回复定位短信失败: " + e.getMessage());
        }
    }
}
