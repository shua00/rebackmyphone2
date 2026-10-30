package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.BatteryManager;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

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

            // [L0] 短信格式：API 23+ 推荐传 format，多部分短信/非 GSM 网络下更可靠
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

            // [L0] 短信指令日志：确认收到、发件人、body 前 40 字符（识别结果后面再记）
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
            }
            // 控制屏幕消息 (SHOW_MSG:密码:消息)
            else if (message.startsWith("SHOW_MSG:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_SHOW_MSG", "message", parts[2].trim());
                    isCommand = true;
                }
            }
            // 短信锁定聊天入口 (LOCK_CHAT:密码)
            else if (message.startsWith("LOCK_CHAT:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_LOCK_CHAT", null, null);
                    isCommand = true;
                }
            }
            // 短信解锁聊天入口 (UNLOCK_CHAT:密码)
            else if (message.startsWith("UNLOCK_CHAT:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_UNLOCK_CHAT", null, null);
                    isCommand = true;
                }
            }
            // 通过短信设置紧急接收号码 (SET_SMS_NUM:密码:手机号)
            else if (message.startsWith("SET_SMS_NUM:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    context.getSharedPreferences("device_finder_prefs", Context.MODE_PRIVATE).edit()
                            .putString("emergency_sms_number", parts[2].trim()).apply();
                    // 可以顺便回一条短信确认
                    SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? context.getSystemService(SmsManager.class) : SmsManager.getDefault();
                    if (smsManager != null && sender != null) {
                        smsManager.sendTextMessage(sender, null, "✅ 紧急报警号码已更新为: " + parts[2].trim(), null, null);
                    }
                    isCommand = true;
                }
            }
            // ★ [NEW] 短信切换老人模式 (ELDER_MODE:密码) 或 (ELDER_MODE:密码:on/off/1/0/true/false)
            //   不传第三段则走 TrackingService 里的"取反"逻辑，与拨号 *#*#5678#*#* 语义一致
            else if (message.startsWith("ELDER_MODE:")) {
                String[] parts = message.split(":", 3);
                if (parts.length >= 2 && RESCUE_SECRET.equals(parts[1])) {
                    String val = parts.length >= 3 ? parts[2].trim() : null;
                    startService(context, "ACTION_TOGGLE_ELDER_MODE",
                            val != null ? "elder_mode" : null, val);
                    isCommand = true;
                }
            }

            if (isCommand) {
                FileLogger.log(context, "SMS", "✅ 指令已识别，执行中"); // [L0]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.app.NotificationManager nm = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancelAll();
                }
                try {
                    Uri uri = Uri.parse("content://sms/inbox");
                    String where = "address=? AND date=?";
                    String[] args = new String[]{sender, String.valueOf(receivedTime)};
                    context.getContentResolver().delete(uri, where, args);
                } catch (Exception e) {
                    // [L1] 删除短信失败是预期的（非默认短信应用删不掉），记录便于区分
                    FileLogger.log(context, "SMS", "⚠️ 删除指令短信失败（非默认短信应用，预期行为）: " + e.getMessage());
                }
                abortBroadcast();
            } else {
                // [L1] 非指令短信也记录一下，便于区分「没收到」vs「收到了但不是指令」
                FileLogger.log(context, "SMS", "非指令短信，忽略");
            }
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ 短信处理异常: " + e.getMessage()); // [L0]
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
            FileLogger.log(context, "SMS", "❌ startService 失败 action=" + action + ": " + e.getMessage()); // [L0]
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
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
            int batteryPct = (int) ((level / (float) scale) * 100);

            StringBuilder sb = new StringBuilder("【设备回复】\n电量: ").append(batteryPct).append("%\n");
            if (loc != null) {
                sb.append("经纬度: ").append(loc.getLatitude()).append(", ").append(loc.getLongitude()).append("\n");
                sb.append("精度: ").append(loc.getAccuracy()).append("米\n");
                sb.append("时间: ").append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(loc.getTime())));
            } else sb.append("经纬度: 无法获取\n");

            SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? context.getSystemService(SmsManager.class) : SmsManager.getDefault();
            if (smsManager != null) smsManager.sendTextMessage(phoneNumber, null, sb.toString(), null, null);
            FileLogger.log(context, "SMS", "📤 已回复定位短信 → " + phoneNumber); // [L1]
        } catch (Exception e) {
            FileLogger.log(context, "SMS", "❌ 回复定位短信失败: " + e.getMessage()); // [L0]
        }
    }
}
