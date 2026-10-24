package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
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
            for (Object pdu : pdus) {
                SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                if (sender == null) sender = sms.getOriginatingAddress();
                fullMessage.append(sms.getMessageBody());
            }
            String message = fullMessage.toString().trim();

            if (message.startsWith("FIND_ME:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_UPDATE_SERVER", "server_url", parts[2].trim());
                }
            } else if (message.startsWith("GET_LOC:")) {
                String[] parts = message.split(":");
                if (parts.length >= 2 && RESCUE_SECRET.equals(parts[1]) && sender != null) {
                    startService(context, "ACTION_FORCE_REPORT", null, null);
                    if (parts.length >= 3 && "reply".equalsIgnoreCase(parts[2])) {
                        replyLocationViaSms(context, sender);
                    }
                }
            } else if (message.startsWith("SET_CONFIG:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_UPDATE_CONFIG_URL", "config_url", parts[2].trim());
                }
            } else if (message.startsWith("GET_ALARM:")) {
                String[] parts = message.split(":", 2);
                if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                    startService(context, "ACTION_PLAY_ALARM", null, null);
                }
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
