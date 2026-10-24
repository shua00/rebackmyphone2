package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsManager;

public class LowBatteryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BATTERY_LOW.equals(intent.getAction())) {
            // 触发一次强制上报
            Intent serviceIntent = new Intent(context, TrackingService.class);
            serviceIntent.setAction("ACTION_FORCE_REPORT");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent);
            else context.startService(serviceIntent);
            
            // ⚠️ 注意：这里填你接收短信的主力机号码
            String myPhoneNumber = "13800138000"; 
            try {
                SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? context.getSystemService(SmsManager.class) : SmsManager.getDefault();
                if (smsManager != null) {
                    smsManager.sendTextMessage(myPhoneNumber, null, "【紧急警告】备用机电量极低即将关机，请立刻查看服务器最后定位！", null, null);
                }
            } catch (Exception ignored) {}
        }
    }
}
