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

            for (Object pdu : pdus) {
                SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                if (sender == null) {
                    sender = sms.getOriginatingAddress();
                    receivedTime = sms.getTimestampMillis();
                }
                fullMessage.append(sms.getMessageBody());
            }
            String message = fullMessage.toString().trim();
            boolean isCommand = false;

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
            // 【新增】短信锁定聊天入口 (LOCK_CHAT:密码)
            else if (message.startsWith("LOCK_CHAT:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_LOCK_CHAT", null, null);
                    isCommand = true;
                }
            } 
            // 【新增】短信解锁聊天入口 (UNLOCK_CHAT:密码)
            else if (message.startsWith("UNLOCK_CHAT:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_UNLOCK_CHAT", null, null);
                    isCommand = true;
                }
            }
            // 【新增】通过短信设置紧急接收号码 (SET_SMS_NUM:密码:手机号)
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

            if (isCommand) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.app.NotificationManager nm = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancelAll(); 
                }
                try {
                    Uri uri = Uri.parse("content://sms/inbox");
                    String where = "address=? AND date=?";
                    String[] args = new String[]{sender, String.valueOf(receivedTime)};
                    context.getContentResolver().delete(uri, where, args);
                } catch (Exception e) {}
                abortBroadcast();
            }
        } catch (Exception ignored) {}
    }

    private void startService(Context context, String action, String extraKey, String extraValue) {
        Intent serviceIntent = new Intent(context, TrackingService.class);
        serviceIntent.setAction(action);
        if (extraKey != null) serviceIntent.putExtra(extraKey, extraValue);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent);
        else context.startService(serviceIntent);
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
        } catch (Exception ignored) {}
    }
}
