package com.example.devicefinder;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class TrackingService extends Service implements LocationListener {

    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private PendingIntent alarmPendingIntent;

    private SensorManager sensorManager;
    private Sensor motionSensor;

    private long reportIntervalMs = 1800 * 1000L;
    private String accuracyMode = "coarse";
    private Location lastLocation = null;
    private int reportCount = 0;

    private boolean enableWifiLock = true;
    private boolean enableMotionTrigger = true;

    private static long lastReportTime = 0;
    private static final Object reportLock = new Object();
    private static int consecutiveFailures = 0;
    private static boolean isListeningForRescue = false;

    private static long lastNotificationClickTime = 0;
    private static int notificationClickCount = 0;
    private static long lastSensorTriggerTime = 0;

    private static String lastReceivedMsg = "";
    private static long lastReceivedMsgTime = 0;

    private static String defaultServerUrl = "http://dingwei.bai001.de5.net/api/report";
    private static final String DEFAULT_REMOTE_CONFIG_URL = "https://your-config-url.com/config.txt";
    private static final String AUTH_TOKEN = "your_private_secret_key";
    private static final String RESCUE_SECRET = "your_private_secret_key";

    private static long lastConfigFetchTime = 0;
    private static final String PREF_BUBBLE = "bubble_pos_prefs";

    private BroadcastReceiver batteryMonitor;

    private volatile boolean serviceDestroyed = false;
    private volatile boolean rescueRetryScheduled = false;
    private DatagramSocket rescueSocketRef = null;

    private final List<android.view.View> activeBubbles = new ArrayList<>();

    // [L1] P3 闹钟偏差跟踪
    private long lastAlarmExpectedAt = 0;
    private long lastHeartbeatExpectedAt = 0;
    // [L1] P3 服务运行时长起点
    private long serviceCreateTimeMs = 0;
    // [L1] UDP 连续 bind 失败计数，防止无限重试
    private int udpBindFailCount = 0;
    // [L2] 定位节流日志上次记录时间
    private long lastLocationLogTime = 0;
    // [改进] onTaskRemoved 节流：10 分钟内只真正排一次自救闹钟
    private static long lastOnTaskRemovedTime = 0;
    private static final long ON_TASK_REMOVED_THROTTLE_MS = 10 * 60 * 1000L;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceCreateTimeMs = SystemClock.elapsedRealtime();

        // 【修复】先赋值 locationManager，再跑自检，否则自检块里 provider 状态误报 off
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        FileLogger.log(this, "SERVICE", "🚀 追踪服务已启动 (或复活)");

        // [L0] ===== 启动自检块 =====
        logStartupSelfCheck();
        // [L0] ===== 自检块结束 =====

        startForegroundNotification();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceFinder::WakeLock");
            wakeLock.setReferenceCounted(false);
        }

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeviceFinder::WifiLock");
            wifiLock.setReferenceCounted(false);
        }

        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        Intent reportIntent = new Intent(this, TrackingService.class);
        reportIntent.setAction("ACTION_ALARM_TRIGGER");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        alarmPendingIntent = PendingIntent.getService(this, 1001, reportIntent, flags);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        }

        handleIconVisibility(true);
        triggerRescueListener();
        toggleMotionSensor(true);

        requestSingleLocationAndReport();
        registerNetworkCallback();
        startLocationUpdates();
        scheduleHeartbeatAlarm();
        registerBatteryMonitor();
        scheduleKeepAliveJob();
    }

    private void logStartupSelfCheck() {
        try {
            FileLogger.log(this, "SERVICE", "===== 启动自检 =====");

            String rom = Build.DISPLAY != null ? Build.DISPLAY : "unknown";
            FileLogger.log(this, "SERVICE", "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + " | ROM: " + rom);
            FileLogger.log(this, "SERVICE", "device_id: " + getUniqueDeviceId());

            try {
                FileLogger.log(this, "SERVICE", "安全补丁: " + Build.VERSION.SECURITY_PATCH);
            } catch (Exception ignored) {}

            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                boolean lowRam = am != null && am.isLowRamDevice();
                FileLogger.log(this, "SERVICE", "低内存设备: " + (lowRam ? "是" : "否"));
            } catch (Exception ignored) {}

            if (Build.VERSION.SDK_INT >= 23) {
                StringBuilder perm = new StringBuilder("权限: ");
                perm.append("LOC=").append(permStr(Manifest.permission.ACCESS_FINE_LOCATION)).append(", ");
                if (Build.VERSION.SDK_INT >= 29)
                    perm.append("BG_LOC=").append(permStr(Manifest.permission.ACCESS_BACKGROUND_LOCATION)).append(", ");
                if (Build.VERSION.SDK_INT >= 33)
                    perm.append("NOTIF=").append(permStr(Manifest.permission.POST_NOTIFICATIONS)).append(", ");
                perm.append("SMS=").append(permStr(Manifest.permission.RECEIVE_SMS)).append(", ");
                if (Build.VERSION.SDK_INT >= 31)
                    perm.append("ALARM=").append(canScheduleExactAlarmsStr()).append(", ");
                perm.append("OVERLAY=").append(Settings.canDrawOverlays(this) ? "✅" : "❌").append(", ");
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                    ComponentName admin = new ComponentName(this, MyDeviceAdminReceiver.class);
                    perm.append("ADMIN=").append(dpm != null && dpm.isAdminActive(admin) ? "✅" : "❌");
                } catch (Exception ignored) {
                    perm.append("ADMIN=❓");
                }
                FileLogger.log(this, "SERVICE", perm.toString());
            }

            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = registerReceiver(null, ifilter);
                int level = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
                int scale = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) : 100;
                int plugged = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) : 0;
                int pct = scale > 0 ? level * 100 / scale : -1;
                int tempRaw = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
                String tempStr = tempRaw >= 0 ? (tempRaw / 10.0f) + "°C" : "未知";
                FileLogger.log(this, "SERVICE", "电池: 白名单=" + (ignored ? "✅" : "❌")
                        + " | 充电中=" + (plugged != 0 ? "✅" : "❌")
                        + " | 电量=" + pct + "% | 温度=" + tempStr);
            } catch (Exception ignored) {}

            try {
                boolean gps = locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                boolean net = locationManager != null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                FileLogger.log(this, "SERVICE", "定位 provider: GPS=" + (gps ? "on" : "off")
                        + ", NETWORK=" + (net ? "on" : "设置未启用(可能仍可用)"));
            } catch (Exception ignored) {}

            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                String typeName = (ni != null && ni.isConnected()) ? ni.getTypeName() : "离线";
                String[] wifi = DeviceInfoUtils.getWifiInfo(this);
                String ip = DeviceInfoUtils.getLocalIpAddress();
                FileLogger.log(this, "SERVICE", "网络: " + typeName + " | SSID=" + wifi[0] + " | IP=" + ip);
            } catch (Exception ignored) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    boolean canExact = am != null && am.canScheduleExactAlarms();
                    FileLogger.log(this, "SERVICE", "闹钟权限: 精确闹钟=" + (canExact ? "✅" : "❌"));
                } catch (Exception ignored) {}
            }

            FileLogger.log(this, "SERVICE", "===================");
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "❌ 自检块异常: " + e.getMessage());
        }
    }

    private String permStr(String perm) {
        try {
            return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ? "✅" : "❌";
        } catch (Exception e) {
            return "❓";
        }
    }

    private String canScheduleExactAlarmsStr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "N/A";
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            return (am != null && am.canScheduleExactAlarms()) ? "✅" : "❌";
        } catch (Exception e) {
            return "❓";
        }
    }

    private void scheduleKeepAliveJob() {
        if (alarmManager == null) return;
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_KEEP_ALIVE");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, 1002, intent, flags);

        long triggerAt = System.currentTimeMillis() + 15 * 60 * 1000L;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (SecurityException e) {
            FileLogger.log(this, "ALARM", "⚠️ 精确闹钟权限缺失，降级为不精确闹钟: " + e.getMessage());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }
    }

    private void toggleMotionSensor(boolean enable) {
        if (sensorManager == null || motionSensor == null) return;
        if (enable && enableMotionTrigger) {
            sensorManager.requestTriggerSensor(motionListener, motionSensor);
        } else {
            sensorManager.cancelTriggerSensor(motionListener, motionSensor);
        }
    }

    private final TriggerEventListener motionListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            long now = System.currentTimeMillis();
            if (now - lastSensorTriggerTime < 15 * 60 * 1000L) {
                toggleMotionSensor(true);
                return;
            }
            lastSensorTriggerTime = now;
            FileLogger.log(TrackingService.this, "SENSOR", "检测到运动，触发强制上报");
            sendReport(true, true);
            toggleMotionSensor(true);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) FileLogger.log(this, "SERVICE", "📥 onStartCommand action=" + action);

            if ("ACTION_RESET_NOTIFICATION".equals(action)) {
                notificationClickCount = 0;
                startForegroundNotification();
            } else if ("ACTION_MANUAL_REPORT".equals(action) || "ACTION_FORCE_REPORT".equals(action)) {
                sendReport(true, true);
            } else if ("ACTION_UPDATE_SERVER".equals(action) || "ACTION_ADB_UPDATE".equals(action)) {
                String newUrl = intent.getStringExtra("server_url");
                if (newUrl != null && !newUrl.isEmpty()) { saveNewServerUrl(newUrl); sendReport(false, true); }
            } else if ("ACTION_UPDATE_CONFIG_URL".equals(action)) {
                String newConfigUrl = intent.getStringExtra("config_url");
                handleUpdateConfigUrl(newConfigUrl);
            } else if ("ACTION_HEARTBEAT".equals(action)) {
                long actual = System.currentTimeMillis();
                long drift = lastHeartbeatExpectedAt > 0 ? actual - lastHeartbeatExpectedAt : -1;
                FileLogger.log(this, "ALARM", "💓 6小时心跳被唤醒" + (drift >= 0 ? " (偏差 " + drift + "ms)" : ""));
                triggerRescueListener();
                fetchRemoteConfig();
                scheduleHeartbeatAlarm();
            } else if ("ACTION_PLAY_ALARM".equals(action)) {
                playAlarm();
            } else if ("ACTION_SHOW_MSG".equals(action)) {
                String msg = intent.getStringExtra("message");
                if (msg != null) showFloatingMessage(msg);
            } else if ("ACTION_SHOW_HISTORY".equals(action)) {
                showMessageHistory();
            } else if ("ACTION_CLEAR_HISTORY".equals(action)) {
                clearMessageHistory();
            } else if ("ACTION_ALARM_TRIGGER".equals(action)) {
                long actual = System.currentTimeMillis();
                long drift = lastAlarmExpectedAt > 0 ? actual - lastAlarmExpectedAt : -1;
                FileLogger.log(this, "ALARM", "⏰ 定时上报闹钟被唤醒" + (drift >= 0 ? " (偏差 " + drift + "ms)" : ""));
                if (enableWifiLock && wifiLock != null && !wifiLock.isHeld()) {
                    try { wifiLock.acquire(); } catch (Exception e) {
                        FileLogger.log(this, "REPORT", "⚠️ WifiLock acquire 失败: " + e.getMessage());
                    }
                }
                sendReport(true, true);
                handler.postDelayed(() -> { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }, 15000);
            } else if ("ACTION_SECRET_UNLOCK".equals(action)) {
                long now = System.currentTimeMillis();
                if (now - lastNotificationClickTime > 3000) { notificationClickCount = 1; } else { notificationClickCount++; }
                lastNotificationClickTime = now;

                if (notificationClickCount == 2) { startForegroundNotification(); }
                else if (notificationClickCount >= 3) {
                    notificationClickCount = 0;
                    startForegroundNotification();
                    if (!isChatLocked()) {
                        Intent chatIntent = new Intent(this, ChatActivity.class);
                        chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        try { startActivity(chatIntent); } catch (Exception ignored) {}
                    } else {
                        android.widget.Toast.makeText(this, "服务已锁定，无法进入", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            } else if ("ACTION_SEND_UDP_CHAT".equals(action)) {
                String msg = intent.getStringExtra("message");
                if (msg != null) sendLocalUdpMessage(msg);
            } else if ("ACTION_KEEP_ALIVE".equals(action)) {
                long uptimeMin = (SystemClock.elapsedRealtime() - serviceCreateTimeMs) / 60000;
                FileLogger.log(this, "ALARM", "💓 15分钟保活心跳被唤醒 (已运行 " + uptimeMin + " 分钟)");
                try {
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    long maxMb = rt.maxMemory() / (1024 * 1024);
                    FileLogger.log(this, "MEMORY", "💾 内存占用: " + usedMb + "MB / " + maxMb + "MB");
                } catch (Exception ignored) {}

                try {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        StatFs stat = new StatFs(dir.getAbsolutePath());
                        long availMb = stat.getAvailableBytes() / (1024 * 1024);
                        long totalMb = stat.getTotalBytes() / (1024 * 1024);
                        FileLogger.log(this, "STORAGE", "💾 可用空间: " + availMb + "MB / " + totalMb + "MB");
                    }
                } catch (Exception ignored) {}

                try {
                    long rx = TrafficStats.getUidRxBytes(Process.myUid());
                    long tx = TrafficStats.getUidTxBytes(Process.myUid());
                    if (rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED) {
                        FileLogger.log(this, "TRAFFIC", "📶 累计流量: RX=" + (rx / 1024) + "KB, TX=" + (tx / 1024) + "KB");
                    }
                } catch (Exception ignored) {}

                scheduleKeepAliveJob();
            } else if ("ACTION_LOCK_CHAT".equals(action)) {
                setChatLocked(true);
            } else if ("ACTION_UNLOCK_CHAT".equals(action)) {
                setChatLocked(false);
            } else if ("ACTION_UPLOAD_LOG".equals(action)) {
                String tempUrl = intent.getStringExtra("temp_url");
                boolean clearAfter = intent.getBooleanExtra("clear_after", false);
                uploadLog(tempUrl, clearAfter);
            } else if ("ACTION_UPDATE_RUNTIME_CONFIG".equals(action)) {
                StringBuilder changed = new StringBuilder();
                String intervalStr = intent.getStringExtra("report_interval_seconds");
                if (intervalStr != null) {
                    try {
                        long newInterval = Long.parseLong(intervalStr) * 1000L;
                        reportIntervalMs = newInterval;
                        changed.append("interval=").append(intervalStr).append("s, ");
                    } catch (Exception e) {
                        FileLogger.log(this, "CONFIG", "⚠️ interval 参数非法: " + intervalStr);
                    }
                }
                String accuracy = intent.getStringExtra("accuracy_mode");
                if (accuracy != null && (accuracy.equals("coarse") || accuracy.equals("fine"))) {
                    accuracyMode = accuracy;
                    changed.append("accuracy=").append(accuracy).append(", ");
                    handler.post(() -> startLocationUpdates());
                }
                String motionStr = intent.getStringExtra("enable_motion_trigger");
                if (motionStr != null) {
                    enableMotionTrigger = Boolean.parseBoolean(motionStr);
                    toggleMotionSensor(enableMotionTrigger);
                    changed.append("motion=").append(enableMotionTrigger).append(", ");
                }
                String wifiLockStr = intent.getStringExtra("enable_wifi_lock");
                if (wifiLockStr != null) {
                    enableWifiLock = Boolean.parseBoolean(wifiLockStr);
                    changed.append("wifiLock=").append(enableWifiLock);
                }
                if (changed.length() > 0) {
                    FileLogger.log(this, "CONFIG", "📝 运行时配置已更新: " + changed);
                } else {
                    FileLogger.log(this, "CONFIG", "⚠️ UPDATE_RUNTIME_CONFIG 未提供任何参数");
                }
            } else if ("ACTION_LOCATE_ONCE".equals(action)) {
                FileLogger.log(this, "LOCATION", "📍 收到手动定位指令");
                requestSingleLocationAndReport();
            } else if ("ACTION_SCAN_WIFI".equals(action)) {
                new Thread(() -> {
                    try {
                        List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this);
                        FileLogger.log(TrackingService.this, "WIFI", "📡 手动扫描完成: " + nearby.size() + " 个热点");
                        for (JSONObject obj : nearby) {
                            FileLogger.log(TrackingService.this, "WIFI",
                                    "  - " + obj.optString("ssid") + " (" + obj.optInt("rssi") + " dBm)");
                        }
                    } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "WIFI", "❌ 手动扫描失败: " + e.getMessage());
                    }
                }).start();
            } else if ("ACTION_RESTART_UDP".equals(action)) {
                FileLogger.log(this, "UDP", "🔄 收到手动重启监听指令");
                if (rescueSocketRef != null) {
                    try { rescueSocketRef.close(); } catch (Exception ignored) {}
                }
            } else if ("ACTION_TEST_LOW_BATTERY".equals(action)) {
                FileLogger.log(this, "BATTERY", "🧪 模拟低电量告警 (15%)");
                sendReport(true, true);
                sendLowBatterySms(15);
            }
        }
        return START_STICKY;
    }

    private boolean isChatLocked() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getBoolean("chat_locked", false);
    }

    private void setChatLocked(boolean locked) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putBoolean("chat_locked", locked).apply();
    }

    private boolean isCloudLogEnabled() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getBoolean("enable_cloud_log", false);
    }

    private void saveMessageToHistory(String message) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("msg_history", "");
        String timeStr = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
        String newRecord = timeStr + " | " + message;

        String updatedHistory = newRecord + "\n" + history;
        String[] lines = updatedHistory.split("\n");
        if (lines.length > 100) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) sb.append(lines[i]).append("\n");
            updatedHistory = sb.toString();
        }
        sp.edit().putString("msg_history", updatedHistory).apply();
    }

    private void saveChatToHistory(String message) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("chat_history", "");
        String timeStr = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
        String newRecord = timeStr + " | " + message;

        String updatedHistory = history.isEmpty() ? newRecord : history + "\n" + newRecord;

        String[] lines = updatedHistory.split("\n");
        if (lines.length > 100) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 100; i < lines.length; i++) sb.append(lines[i]).append("\n");
            updatedHistory = sb.toString().trim();
        }
        sp.edit().putString("chat_history", updatedHistory).apply();
    }

    private void syncMessageToServer(String message) {
        new Thread(() -> {
            try {
                String timeStr = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
                JSONObject json = new JSONObject();
                json.put("token", AUTH_TOKEN);
                json.put("device_id", getUniqueDeviceId());
                json.put("message", message);
                json.put("time_str", timeStr);
                json.put("timestamp", System.currentTimeMillis());

                String msgUrl = buildApiUrl(getCurrentServerUrl(), "message");
                if (msgUrl == null) {
                    Log.e("TrackingService", "syncMessageToServer: server_url 非法，跳过");
                    return;
                }
                URL url = new URL(msgUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void clearMessageHistory() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("msg_history", "").apply();

        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                TextView textView = new TextView(this);
                textView.setText("🧹 历史留言已清空");
                textView.setTextSize(20f);
                textView.setTextColor(Color.WHITE);
                textView.setBackgroundColor(Color.parseColor("#CC000000"));
                textView.setPadding(40, 40, 40, 40);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.CENTER;
                wm.addView(textView, params);

                handler.postDelayed(() -> {
                    try { wm.removeView(textView); } catch (Exception ignored) {}
                }, 3000);
            } catch (Exception ignored) {}
        });
    }

    private void showMessageHistory() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("msg_history", "还没有留言哦~");

        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenW = dm.widthPixels, screenH = dm.heightPixels;

                android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                layout.setOrientation(android.widget.LinearLayout.VERTICAL);

                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(Color.parseColor("#F2FFFFFF"));
                shape.setCornerRadius(50);
                layout.setBackground(shape);
                layout.setPadding(60, 60, 60, 60);

                TextView titleView = new TextView(this);
                titleView.setText("📜 家庭留言板");
                titleView.setTextSize(22f);
                titleView.setTextColor(Color.parseColor("#333333"));
                titleView.setTypeface(null, android.graphics.Typeface.BOLD);
                titleView.setGravity(Gravity.START);

                android.widget.LinearLayout.LayoutParams titleParams = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                titleParams.bottomMargin = 30;
                titleView.setLayoutParams(titleParams);
                layout.addView(titleView);

                ScrollView scrollView = new ScrollView(this);
                TextView textView = new TextView(this);
                textView.setText(history);
                textView.setTextSize(18f);
                textView.setTextColor(Color.parseColor("#555555"));
                textView.setLineSpacing(20f, 1f);
                textView.setGravity(Gravity.START);
                scrollView.addView(textView);
                layout.addView(scrollView);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE;

                int bubbleW = (int) (screenW * 0.85f);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        bubbleW,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);

                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 80;
                params.y = 450;

                wm.addView(layout, params);

                scrollView.post(() -> {
                    int contentH = textView.getMeasuredHeight();
                    int maxH = (int) (screenH * 0.55f);
                    int finalH = Math.min(contentH + 20, maxH);
                    android.view.ViewGroup.LayoutParams lp2 = scrollView.getLayoutParams();
                    lp2.height = finalH;
                    scrollView.setLayoutParams(lp2);
                });

                textView.setTextIsSelectable(true);
                textView.setFocusable(true);
                textView.setFocusableInTouchMode(true);

                final android.widget.LinearLayout layoutRef = layout;
                attachDragAndRemember(wm, layout, params, "bubble_pos",
                        () -> { try { wm.removeView(layoutRef); } catch (Exception ignored) {} },
                        null);

            } catch (Exception e) {
                FileLogger.log(this, "UI", "❌ showMessageHistory 失败: " + e.getMessage());
            }
        });
    }

    private void showFloatingMessage(String message) {
        saveMessageToHistory(message);
        syncMessageToServer(message);

        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                for (android.view.View oldBubble : activeBubbles) {
                    try { wm.removeView(oldBubble); } catch (Exception ignored) {}
                }
                activeBubbles.clear();

                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenW = dm.widthPixels;
                final int msgMaxW = (int) (screenW * 0.85f);

                android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                layout.setGravity(Gravity.START);

                TextView textView = new TextView(this);
                textView.setText(message);
                textView.setTextSize(20f);
                textView.setTypeface(null, android.graphics.Typeface.NORMAL);
                textView.setTextColor(Color.WHITE);
                textView.setShadowLayer(8f, 3f, 3f, Color.parseColor("#99000000"));
                textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

                textView.setPadding(dpToPx(10), dpToPx(15), dpToPx(20), dpToPx(15));
                textView.setMaxWidth(msgMaxW);
                textView.setLineSpacing(15f, 1f);
                textView.setHorizontallyScrolling(false);
                textView.setSingleLine(false);

                android.widget.LinearLayout.LayoutParams tvParams = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                );
                textView.setLayoutParams(tvParams);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    textView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                    textView.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
                }
                layout.addView(textView);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        msgMaxW,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);

                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 20;
                params.y = 450;

                wm.addView(layout, params);
                activeBubbles.add(layout);

                layout.post(() -> {
                    int textNaturalW = measureTextNaturalWidth(textView, message);
                    int finalW = Math.min(textNaturalW, msgMaxW);
                    finalW = Math.max(finalW, dpToPx(60));
                    if (finalW != params.width) {
                        params.width = finalW;
                        try { wm.updateViewLayout(layout, params); } catch (Exception ignored) {}
                    }
                });

                final android.widget.LinearLayout layoutRef = layout;
                attachDragAndRemember(wm, layout, params, "bubble_pos",
                        () -> { try { wm.removeView(layoutRef); } catch (Exception ignored) {} activeBubbles.remove(layoutRef); },
                        () -> { try { wm.removeView(layoutRef); } catch (Exception ignored) {} activeBubbles.remove(layoutRef); showMessageHistory(); });

                handler.postDelayed(() -> {
                    try { wm.removeView(layoutRef); } catch (Exception ignored) {}
                    activeBubbles.remove(layoutRef);
                }, 24 * 60 * 60 * 1000L);

            } catch (Exception e) {
                FileLogger.log(this, "UI", "❌ showFloatingMessage 失败: " + e.getMessage());
            }
        });
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int getNavBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int clampX(int x, int viewW, int screenW) { return Math.max(0, Math.min(x, screenW - viewW)); }
    private int clampY(int y, int viewH, int screenH, int statusH, int navH) {
        int minY = statusH; int maxY = screenH - navH - viewH;
        if (maxY < minY) maxY = minY;
        return Math.max(minY, Math.min(y, maxY));
    }
    private int dpToPx(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }

    private int measureTextNaturalWidth(TextView tv, String text) {
        android.text.TextPaint paint = tv.getPaint();
        if (text == null || text.isEmpty()) return 0;
        int maxW = 0;
        for (String line : text.split("\n")) { maxW = Math.max(maxW, (int) Math.ceil(paint.measureText(line))); }
        return maxW + tv.getPaddingLeft() + tv.getPaddingRight();
    }

    private void attachDragAndRemember(WindowManager wm, android.view.View view,
                                       WindowManager.LayoutParams lp, String prefKey,
                                       Runnable onSingleTap, Runnable onDoubleTap) {
        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int screenW = dm.widthPixels, screenH = dm.heightPixels;
        final int statusH = getStatusBarHeight(), navH = getNavBarHeight();
        final String posKey = prefKey + "_" + screenW + "x" + screenH;

        final float[] downRaw = new float[2];
        final int[] startPos = new int[2];
        final boolean[] dragging = {false};

        final Runnable[] pendingSingleTap = new Runnable[1];
        final Handler gestureHandler = new Handler(Looper.getMainLooper());

        android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(android.view.MotionEvent e) {
                        dragging[0] = true;
                        if (pendingSingleTap[0] != null) { gestureHandler.removeCallbacks(pendingSingleTap[0]); }
                    }

                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        if (!dragging[0] && onSingleTap != null) {
                            if (pendingSingleTap[0] != null) { gestureHandler.removeCallbacks(pendingSingleTap[0]); }
                            pendingSingleTap[0] = () -> { if (onSingleTap != null) onSingleTap.run(); };
                            gestureHandler.postDelayed(pendingSingleTap[0], 300);
                        }
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (pendingSingleTap[0] != null) { gestureHandler.removeCallbacks(pendingSingleTap[0]); pendingSingleTap[0] = null; }
                        if (onDoubleTap != null) { onDoubleTap.run(); }
                        return true;
                    }
                });

        view.setOnTouchListener((v, event) -> {
            gd.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX(); downRaw[1] = event.getRawY();
                    if (lp.gravity != (Gravity.TOP | Gravity.START)) {
                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.x = loc[0]; lp.y = loc[1];
                        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
                    }
                    startPos[0] = lp.x; startPos[1] = lp.y;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (dragging[0]) {
                        int nx = startPos[0] + (int) (event.getRawX() - downRaw[0]);
                        int ny = startPos[1] + (int) (event.getRawY() - downRaw[1]);
                        lp.x = clampX(nx, v.getWidth(), screenW);
                        lp.y = clampY(ny, v.getHeight(), screenH, statusH, navH);
                        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) {
                        dragging[0] = false;
                        getSharedPreferences(PREF_BUBBLE, MODE_PRIVATE).edit()
                                .putInt(posKey + "_x", lp.x).putInt(posKey + "_y", lp.y).apply();
                    }
                    return true;
            }
            return false;
        });

        view.post(() -> {
            SharedPreferences sp = getSharedPreferences(PREF_BUBBLE, MODE_PRIVATE);
            int sx = sp.getInt(posKey + "_x", Integer.MIN_VALUE);
            int sy = sp.getInt(posKey + "_y", Integer.MIN_VALUE);
            if (sx != Integer.MIN_VALUE && sy != Integer.MIN_VALUE) {
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = clampX(sx, view.getWidth(), screenW);
                lp.y = clampY(sy, view.getHeight(), screenH, statusH, navH);
                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
            }
        });
    }

    private void registerBatteryMonitor() {
        batteryMonitor = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
                int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                int plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                int percent = (scale > 0) ? (level * 100 / scale) : -1;
                boolean charging = plugged != 0;

                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                long lastSmsTime = sp.getLong("last_low_battery_sms_time", 0);
                long now = System.currentTimeMillis();

                if (percent >= 0 && percent <= 15 && !charging) {
                    if (now - lastSmsTime > 6 * 60 * 60 * 1000L) {
                        FileLogger.log(TrackingService.this, "BATTERY", "🪫 触发低电量告警 (" + percent + "%)");
                        sp.edit().putLong("last_low_battery_sms_time", now).apply();
                        sendReport(true, true);
                        sendLowBatterySms(percent);
                    }
                } else if (charging || percent > 20) {
                    if (lastSmsTime != 0) { sp.edit().putLong("last_low_battery_sms_time", 0).apply(); }
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(batteryMonitor, filter);
        } catch (Exception e) {
            FileLogger.log(this, "BATTERY", "❌ 注册电池监听失败: " + e.getMessage());
        }
    }

    private void sendLowBatterySms(int percent) {
        try {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            String myPhoneNumber = sp.getString("emergency_sms_number", "");
            if (myPhoneNumber == null || myPhoneNumber.trim().isEmpty()) {
                FileLogger.log(this, "BATTERY", "⚠️ 低电量告警但未设置紧急号码");
                return;
            }

            android.telephony.SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getSystemService(android.telephony.SmsManager.class) : android.telephony.SmsManager.getDefault();
            if (smsManager != null) {
                smsManager.sendTextMessage(myPhoneNumber, null, "【紧急警告】备用机电量 " + percent + "% 即将关机，请立刻查看服务器最后定位！", null, null);
                FileLogger.log(this, "BATTERY", "📩 低电量短信已发送 → " + myPhoneNumber);
            }
        } catch (Exception e) {
            FileLogger.log(this, "BATTERY", "❌ 低电量短信发送失败: " + e.getMessage());
        }
    }

    private void playAlarm() {
        try {
            android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(getApplicationContext(), android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM));
            if (ringtone != null) {
                android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) { audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0); }
                ringtone.play();
                FileLogger.log(this, "ALARM", "🔊 警报已播放");
                handler.postDelayed(() -> { if (ringtone.isPlaying()) ringtone.stop(); }, 30000);
            }
        } catch (Exception e) {
            FileLogger.log(this, "ALARM", "❌ 播放警报失败: " + e.getMessage());
        }
    }

    private void startForegroundNotification() {
        String channelId = "device_guardian_channel";
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(channelId, "系统服务", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false); channel.setSound(null, null); channel.enableVibration(false); channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            if (manager != null) manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this); builder.setPriority(Notification.PRIORITY_MIN);
        }

        int iconRes = android.R.drawable.stat_notify_sync;
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent chatPi;
        if (notificationClickCount >= 2 && !isChatLocked()) {
            Intent chatIntent = new Intent(this, ChatActivity.class);
            chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            chatPi = PendingIntent.getActivity(this, 888, chatIntent, piFlags);
        } else {
            Intent unlockIntent = new Intent(this, TrackingService.class);
            unlockIntent.setAction("ACTION_SECRET_UNLOCK");
            chatPi = PendingIntent.getService(this, 888, unlockIntent, piFlags);
        }

        Notification notification = builder.setContentTitle("系统同步").setContentText("正在运行...").setSmallIcon(iconRes).setContentIntent(chatPi).setOngoing(true).build();
        notification.priority = Notification.PRIORITY_MIN;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1001, notification);
            }
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "❌ 带 location type 的 startForeground 失败，降级为无 type: " + e.getMessage());
            try {
                startForeground(1001, notification);
            } catch (Exception e2) {
                FileLogger.log(this, "SERVICE", "❌❌ startForeground 彻底失败: " + e2.getMessage());
            }
        }
    }

    private void scheduleHeartbeatAlarm() {
        if (alarmManager == null) return;
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_HEARTBEAT");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, 9999, intent, flags);
        long triggerAt = System.currentTimeMillis() + 6 * 60 * 60 * 1000L;
        lastHeartbeatExpectedAt = triggerAt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }

    private String getUniqueDeviceId() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String deviceId = sp.getString("saved_device_id", null);
        if (deviceId == null) {
            String model = Build.MODEL != null ? Build.MODEL.replaceAll("\\s+", "_") : "UnknownPhone";
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String suffix = (androidId != null && androidId.length() >= 4) ? androidId.substring(androidId.length() - 4) : "0000";
            deviceId = model + "_" + suffix;
            sp.edit().putString("saved_device_id", deviceId).apply();
        }
        return deviceId;
    }

    private String getCurrentServerUrl() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String saved = sp.getString("saved_server_url", defaultServerUrl);
        if (!isValidUrl(saved)) {
            FileLogger.log(this, "CONFIG", "⚠️ saved_server_url 非法: [" + saved + "]，回退默认值");
            sp.edit().putString("saved_server_url", defaultServerUrl).apply();
            return defaultServerUrl;
        }
        return saved;
    }

    private void saveNewServerUrl(String newUrl) {
        if (!isValidUrl(newUrl)) {
            FileLogger.log(this, "CONFIG", "⚠️ 拒绝保存非法 server_url: [" + newUrl + "]");
            return;
        }
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("saved_server_url", newUrl).apply();
        FileLogger.log(this, "CONFIG", "📝 server_url 已更新: " + newUrl);
    }

    private String getRemoteConfigUrl() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String saved = sp.getString("saved_config_url", DEFAULT_REMOTE_CONFIG_URL);
        if (!isValidUrl(saved)) {
            FileLogger.log(this, "CONFIG", "⚠️ saved_config_url 非法: [" + saved + "]，回退默认值");
            return DEFAULT_REMOTE_CONFIG_URL;
        }
        return saved;
    }

    private void saveRemoteConfigUrl(String url) {
        if (!isValidUrl(url)) {
            FileLogger.log(this, "CONFIG", "⚠️ 拒绝保存非法 config_url: [" + url + "]");
            return;
        }
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("saved_config_url", url).apply();
        FileLogger.log(this, "CONFIG", "📝 config_url 已更新: " + url);
    }

    private void handleUpdateConfigUrl(String newConfigUrl) {
        if (newConfigUrl != null && !newConfigUrl.isEmpty()) { saveRemoteConfigUrl(newConfigUrl); lastConfigFetchTime = 0; fetchRemoteConfig(); }
    }

    private void handleIconVisibility(boolean hideIcon) {
        try {
            PackageManager p = getPackageManager();
            ComponentName componentName = new ComponentName(this, "com.example.devicefinder.VisibleAliasActivity");
            if (p.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "⚠️ 修改 alias 状态失败: " + e.getMessage());
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        String oldSsid = DeviceInfoUtils.cachedSsid;
                        String[] wifiInfo = DeviceInfoUtils.getWifiInfo(TrackingService.this);
                        String newSsid = wifiInfo[0];
                        if (newSsid.equals("UNKNOWN") || newSsid.equals("<unknown ssid>")) return;
                        if (!oldSsid.equals(newSsid) && !oldSsid.equals("UNKNOWN")) {
                            FileLogger.log(TrackingService.this, "NETWORK", "✅ WiFi 连接: SSID=" + newSsid + ", IP=" + DeviceInfoUtils.getLocalIpAddress());
                            sendReport(true, false);
                            triggerRescueListener();
                        }
                    }
                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        FileLogger.log(TrackingService.this, "NETWORK", "❌ WiFi 断开");
                        sendReport(true, false);
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            FileLogger.log(this, "NETWORK", "❌ 注册网络回调失败: " + e.getMessage());
        }
    }

    private void startLocationUpdates() {
        try {
            locationManager.removeUpdates(this);

            // [改进] 用 getProviders(true) 一次拿到所有已启用的 provider
            // 比两次 isProviderEnabled 更可靠：一次调用代替两次，且能看到 fused/passive 等额外 provider
            // 关键：getProviders 失败时 fallback 到 isProviderEnabled，保证行为不退化
            boolean gpsOn = false;
            boolean netOn = false;
            String allProviders = "getProviders 失败";

            try {
                List<String> providers = locationManager.getProviders(true);
                if (providers != null) {
                    allProviders = providers.toString();
                    gpsOn = providers.contains(LocationManager.GPS_PROVIDER);
                    netOn = providers.contains(LocationManager.NETWORK_PROVIDER);
                }
            } catch (Exception e) {
                // [保护] getProviders 抛异常时，fallback 到 isProviderEnabled，行为跟旧代码一致
                FileLogger.log(this, "LOCATION", "⚠️ getProviders 失败，fallback 到 isProviderEnabled: " + e.getMessage());
                try { gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                try { netOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                allProviders = "fallback(GPS=" + gpsOn + ",NET=" + netOn + ")";
            }

            FileLogger.log(this, "LOCATION", "provider 状态: GPS=" + (gpsOn ? "on" : "off")
                    + ", NETWORK=" + (netOn ? "on" : "off")
                    + " | 全部: " + allProviders
                    + " | 模式=" + accuracyMode);

            long minTime = 60000L;
            // GPS：只在 fine 模式且开启时请求
            if ("fine".equals(accuracyMode) && gpsOn) {
                try {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, 0f, this);
                } catch (Exception e) {
                    FileLogger.log(this, "LOCATION", "⚠️ 请求 GPS 失败: " + e.getMessage());
                }
            }
            // NETWORK：不管 gpsOn/netOn 返回什么，都尝试请求
            try {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTime, 0f, this);
            } catch (Exception e) {
                FileLogger.log(this, "LOCATION", "⚠️ 请求 NETWORK 失败: " + e.getMessage());
            }
        } catch (SecurityException e) {
            FileLogger.log(this, "LOCATION", "❌ 定位权限缺失，无法注册 provider: " + e.getMessage());
        } catch (Exception e) {
            FileLogger.log(this, "LOCATION", "❌ 注册定位监听失败: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            lastLocation = location;
            long now = System.currentTimeMillis();
            if (now - lastLocationLogTime > 5 * 60 * 1000L) {
                lastLocationLogTime = now;
                FileLogger.log(this, "LOCATION", "📍 lat=" + location.getLatitude()
                        + ", lng=" + location.getLongitude()
                        + ", acc=" + location.getAccuracy() + "m"
                        + ", provider=" + location.getProvider());
            }
        }
    }

    private void requestSingleLocationAndReport() {
        final boolean[] reported = new boolean[]{false};
        final Handler timeoutHandler = new Handler(Looper.getMainLooper());
        final LocationListener singleListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) {
                    lastLocation = location;
                    FileLogger.log(TrackingService.this, "LOCATION", "📍 单次定位成功: provider="
                            + location.getProvider() + ", acc=" + location.getAccuracy() + "m");
                }
                if (!reported[0]) {
                    reported[0] = true;
                    timeoutHandler.removeCallbacksAndMessages(null);
                    try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
                    sendReport(true, false);
                    reportCount = 1;
                    scheduleNextReport();
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
            FileLogger.log(this, "LOCATION", "📡 已请求 NETWORK provider（单次）");
        } catch (Exception e) {
            FileLogger.log(this, "LOCATION", "⚠️ 请求 NETWORK 失败: " + e.getMessage());
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
            FileLogger.log(this, "LOCATION", "📡 已请求 GPS provider（单次）");
        } catch (Exception e) {
            FileLogger.log(this, "LOCATION", "⚠️ 请求 GPS 失败: " + e.getMessage());
        }

        timeoutHandler.postDelayed(() -> {
            if (!reported[0]) {
                reported[0] = true;
                try { locationManager.removeUpdates(singleListener); } catch (Exception ignored) {}
                FileLogger.log(this, "LOCATION", "⚠️ 60 秒内未获取到新定位，lastLocation="
                        + (lastLocation != null ? ("旧值(lat=" + lastLocation.getLatitude() + ")") : "无"));
                sendReport(true, false);
                reportCount = 1;
                scheduleNextReport();
            }
        }, 60000);
    }

    private void scheduleNextReport() {
        if (alarmManager == null || alarmPendingIntent == null) return;
        long delay = (reportCount == 1 || reportCount == 2) ? 3 * 60 * 1000L : reportIntervalMs;
        long triggerAtMillis = System.currentTimeMillis() + delay;
        lastAlarmExpectedAt = triggerAtMillis;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent); }
            else { alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent); }
        } catch (SecurityException e) {
            FileLogger.log(this, "ALARM", "⚠️ 精确闹钟权限缺失，降级: " + e.getMessage());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent); }
            else { alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent); }
        }
    }

    private void sendReport(final boolean withNearbyWifi, boolean force) { sendReport(withNearbyWifi, force, null); }

    private void sendReport(final boolean withNearbyWifi, boolean force, final String overrideUrl) {
        long currentTime = System.currentTimeMillis();

        synchronized (reportLock) {
            if (!force && currentTime - lastReportTime < 5000) return;
            if (force && currentTime - lastReportTime < 2000) return;
            lastReportTime = currentTime;
        }

        final String activeUrl = (overrideUrl != null && !overrideUrl.isEmpty()) ? overrideUrl : getCurrentServerUrl();

        new Thread(() -> {
            boolean success = false;
            if (enableWifiLock && wifiLock != null && !wifiLock.isHeld()) {
                try { wifiLock.acquire(); } catch (Exception e) {
                    FileLogger.log(TrackingService.this, "REPORT", "⚠️ WifiLock acquire 失败: " + e.getMessage());
                }
            }
            try {
                if (wakeLock != null) {
                    try { wakeLock.acquire(10000); } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "REPORT", "⚠️ WakeLock acquire 失败: " + e.getMessage());
                    }
                }
                int[] battery = DeviceInfoUtils.getBatteryInfo(TrackingService.this);
                String[] wifi = DeviceInfoUtils.getWifiInfo(TrackingService.this);
                String wifiSsid = wifi[0];
                String ip = DeviceInfoUtils.getLocalIpAddress();

                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNet = cm.getActiveNetworkInfo();
                boolean isOnline = (activeNet != null && activeNet.isConnected());
                String netDesc = isOnline ? getNetworkTypeDesc(activeNet) : "离线";
                FileLogger.log(TrackingService.this, "REPORT", "准备上报. 网络:" + netDesc + " | SSID:" + wifiSsid + " | IP:" + ip + " | 电量:" + battery[0] + "%");

                if (!isOnline && overrideUrl == null) {
                    FileLogger.log(TrackingService.this, "REPORT", "⚠️ 网络离线，跳过本次上报");
                    return;
                }

                Location loc = lastLocation;
                if (loc == null) {
                    Location netLoc = null, gpsLoc = null, passiveLoc = null;
                    try { netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                    try { gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                    try { passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER); } catch (Exception ignored) {}
                    loc = getBetterLocation(netLoc, gpsLoc);
                    loc = getBetterLocation(loc, passiveLoc);
                }
                JSONObject json = new JSONObject();
                json.put("device_id", getUniqueDeviceId()); json.put("token", AUTH_TOKEN); json.put("timestamp", System.currentTimeMillis());
                json.put("os_version", "Android " + Build.VERSION.RELEASE); json.put("chat_locked", isChatLocked());
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE); json.put("is_screen_on", pm != null && pm.isInteractive()); json.put("uptime_ms", SystemClock.elapsedRealtime());
                JSONObject battObj = new JSONObject(); battObj.put("level", battery[0]); battObj.put("is_charging", battery[1] == 1); json.put("battery", battObj);
                JSONObject netObj = new JSONObject(); netObj.put("wifi_ssid", wifi[0]); netObj.put("wifi_bssid", wifi[1]); netObj.put("wifi_rssi", Integer.parseInt(wifi[2])); netObj.put("local_ip", ip); json.put("network", netObj);
                JSONObject locObj = new JSONObject();
                if (loc != null) { locObj.put("latitude", loc.getLatitude()); locObj.put("longitude", loc.getLongitude()); locObj.put("accuracy", loc.getAccuracy()); locObj.put("provider", loc.getProvider()); locObj.put("time", loc.getTime()); }
                else { locObj.put("latitude", JSONObject.NULL); locObj.put("longitude", JSONObject.NULL); locObj.put("accuracy", -1.0); locObj.put("provider", "none"); locObj.put("time", 0L); }
                json.put("location", locObj);

                if (isCloudLogEnabled()) {
                    String memoryLogs = FileLogger.getMemoryLogs();
                    if (!memoryLogs.isEmpty()) json.put("cloud_logs", memoryLogs);
                }

                if (withNearbyWifi) {
                    try { List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this); if (nearby != null && !nearby.isEmpty()) { JSONArray arr = new JSONArray(); for (JSONObject obj : nearby) arr.put(obj); json.put("nearby_wifi", arr); } } catch (Exception ignored) {}
                }
                URL url = new URL(activeUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json"); conn.setConnectTimeout(15000); conn.setReadTimeout(15000); conn.setDoOutput(true);

                long httpStart = System.currentTimeMillis();
                OutputStream os = conn.getOutputStream(); os.write(json.toString().getBytes("UTF-8")); os.flush(); os.close();
                int httpCode = conn.getResponseCode();
                long httpCost = System.currentTimeMillis() - httpStart;

                if (httpCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream())); StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close(); if (overrideUrl == null) parseResponse(new JSONObject(sb.toString())); success = true;
                    FileLogger.log(TrackingService.this, "REPORT", "✅ 上报成功 (耗时 " + httpCost + "ms)");
                } else {
                    FileLogger.log(TrackingService.this, "REPORT", "⚠️ 上报返回非200: HTTP " + httpCode + " (耗时 " + httpCost + "ms) | " + activeUrl);
                }
                conn.disconnect();
            } catch (Exception e) {
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.contains("Unable to resolve host")) {
                    FileLogger.log(TrackingService.this, "REPORT", "❌ DNS 解析失败: " + errMsg);
                } else {
                    FileLogger.log(TrackingService.this, "REPORT", "❌ 上报异常: " + errMsg);
                }
                Log.e("TrackingService", "===== 上报失败啦 =====");
                Log.e("TrackingService", "目标URL: " + activeUrl);
                Log.e("TrackingService", "报错原因: " + e.toString());
            } finally {
                try {
                    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                    if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
                } catch (Exception ignored) {}

                if (overrideUrl == null) {
                    reportCount++;
                    scheduleNextReport();
                }
            }

            if (success) {
                consecutiveFailures = 0;
                fetchRemoteConfig();
            } else {
                if (overrideUrl == null) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 1) {
                        triggerRescueListener();
                        fetchRemoteConfig();
                    }
                }
            }
        }).start();
    }

    private void uploadLog(final String tempUrl, final boolean clearAfter) {
        new Thread(() -> {
            FileInputStream fis = null;
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) {
                    FileLogger.log(this, "UPLOAD_LOG", "❌ getExternalFilesDir 为 null");
                    return;
                }
                java.io.File logFile = new java.io.File(dir, "device_log.txt");
                if (!logFile.exists() || logFile.length() == 0) {
                    FileLogger.log(this, "UPLOAD_LOG", "⚠️ 日志文件不存在或为空");
                    return;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gzos = new GZIPOutputStream(baos);
                fis = new FileInputStream(logFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) gzos.write(buf, 0, n);
                gzos.close();
                byte[] gzipData = baos.toByteArray();
                long originalSize = logFile.length();

                String baseUrl = (tempUrl != null && !tempUrl.isEmpty()) ? tempUrl : getCurrentServerUrl();
                FileLogger.log(this, "UPLOAD_LOG", "📤 baseUrl=[" + baseUrl + "] (tempUrl=[" + tempUrl + "])");

                String uploadUrl = buildApiUrl(baseUrl, "upload_log");
                if (uploadUrl == null) {
                    FileLogger.log(this, "UPLOAD_LOG", "❌ baseUrl 非法，无法拼接上传 URL: [" + baseUrl + "]");
                    return;
                }

                String deviceId = getUniqueDeviceId();
                String fullUrl = uploadUrl
                        + (uploadUrl.contains("?") ? "&" : "?")
                        + "token=" + java.net.URLEncoder.encode(AUTH_TOKEN, "UTF-8")
                        + "&device_id=" + java.net.URLEncoder.encode(deviceId, "UTF-8");

                FileLogger.log(this, "UPLOAD_LOG", "📤 开始上传日志: " + (originalSize / 1024) + "KB → 压缩后 "
                        + (gzipData.length / 1024) + "KB → " + uploadUrl);

                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/gzip");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(gzipData.length);

                OutputStream os = conn.getOutputStream();
                os.write(gzipData);
                os.flush();
                os.close();

                long start = System.currentTimeMillis();
                int code = conn.getResponseCode();
                long cost = System.currentTimeMillis() - start;

                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    String resp = sb.toString();
                    FileLogger.log(this, "UPLOAD_LOG", "✅ 上传成功 (耗时 " + cost + "ms): " + resp);

                    if (clearAfter) {
                        boolean deleted = logFile.delete();
                        FileLogger.log(this, "UPLOAD_LOG", deleted ? "🧹 本地日志已清空" : "⚠️ 本地日志删除失败");
                    }
                } else {
                    FileLogger.log(this, "UPLOAD_LOG", "⚠️ 上传失败: HTTP " + code + " (耗时 " + cost + "ms)");
                }
                conn.disconnect();
            } catch (Exception e) {
                FileLogger.log(this, "UPLOAD_LOG", "❌ 上传异常: " + e.getMessage());
            } finally {
                if (fis != null) { try { fis.close(); } catch (Exception ignored) {} }
            }
        }).start();
    }

    private String getNetworkTypeDesc(NetworkInfo activeNet) {
        String typeName = activeNet.getTypeName();
        if (ConnectivityManager.TYPE_MOBILE != activeNet.getType()) return typeName;
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return typeName;
            int subType = tm.getNetworkType();
            String sub;
            switch (subType) {
                case TelephonyManager.NETWORK_TYPE_NR: sub = "5G"; break;
                case TelephonyManager.NETWORK_TYPE_LTE: sub = "4G"; break;
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                    sub = "3G"; break;
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    sub = "2G"; break;
                default: sub = String.valueOf(subType); break;
            }
            return "MOBILE(" + sub + ")";
        } catch (Exception e) {
            return typeName;
        }
    }

    private void fetchRemoteConfig() {
        String configUrl = getRemoteConfigUrl();
        if (configUrl.contains("your-config-url.com")) return;
        long now = System.currentTimeMillis(); if (now - lastConfigFetchTime < 10 * 60 * 1000L) return;
        lastConfigFetchTime = now;
        new Thread(() -> {
            try {
                URL url = new URL(configUrl); HttpURLConnection conn = (HttpURLConnection) url.openConnection(); conn.setRequestMethod("GET"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream())); StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line); br.close();
                    String fetchedText = sb.toString().trim();
                    if (fetchedText.startsWith("MSG:")) { showFloatingMessage(fetchedText.substring(4).trim()); }
                    else if (fetchedText.startsWith("http") && !fetchedText.equals(getCurrentServerUrl())) { saveNewServerUrl(fetchedText); consecutiveFailures = 0; sendReport(true, true); }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private Location getBetterLocation(Location l1, Location l2) {
        if (l1 == null) return l2; if (l2 == null) return l1;
        long timeDelta = l2.getTime() - l1.getTime(); boolean isSignificantlyNewer = timeDelta > 120000; boolean isSignificantlyOlder = timeDelta < -120000; boolean isNewer = timeDelta > 0;
        if (isSignificantlyNewer) return l2; else if (isSignificantlyOlder) return l1;
        int accuracyDelta = (int) (l2.getAccuracy() - l1.getAccuracy()); boolean isLessAccurate = accuracyDelta > 0; boolean isMoreAccurate = accuracyDelta < 0; boolean isSignificantlyLessAccurate = accuracyDelta > 200; boolean isFromSameProvider = l2.getProvider() != null && l2.getProvider().equals(l1.getProvider());
        if (isMoreAccurate) return l2; else if (isNewer && !isLessAccurate) return l2; else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return l2;
        return l1;
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.contains("://")) return false;
        if (url.length() < 10) return false;
        return true;
    }

    private String buildApiUrl(String baseUrl, String apiName) {
        if (!isValidUrl(baseUrl)) return null;
        try {
            android.net.Uri uri = android.net.Uri.parse(baseUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) return null;
            int port = uri.getPort();
            String authority = port > 0 ? (host + ":" + port) : host;
            return scheme + "://" + authority + "/api/" + apiName;
        } catch (Exception e) {
            return null;
        }
    }

    private void triggerRescueListener() {
        if (serviceDestroyed) return;
        if (isListeningForRescue) {
            if (!rescueRetryScheduled) {
                rescueRetryScheduled = true;
                handler.postDelayed(() -> {
                    rescueRetryScheduled = false;
                    if (!isListeningForRescue && !serviceDestroyed) {
                        startRescueListener();
                    }
                }, 8000);
            }
            return;
        }
        startRescueListener();
    }

    private void sendLocalUdpMessage(final String message) {
        new Thread(() -> {
            try {
                java.net.DatagramSocket socket = new java.net.DatagramSocket();
                socket.setBroadcast(true);
                String myId = getUniqueDeviceId();
                String payload = "FIND_ME_MSG:" + RESCUE_SECRET + ":" + myId + ":" + message;
                byte[] buf = payload.getBytes("UTF-8");
                java.net.DatagramPacket packet = new java.net.DatagramPacket(buf, buf.length, java.net.InetAddress.getByName("255.255.255.255"), 8888);

                for (int i = 0; i < 3; i++) {
                    socket.send(packet);
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
                socket.close();
            } catch (Exception e) {
                Log.e("TrackingService", "UDP 发送失败: " + e.toString());
                FileLogger.log(TrackingService.this, "UDP", "❌ 发送失败: " + e.getMessage());
            }
        }).start();
    }

    private void startRescueListener() {
        isListeningForRescue = true;
        new Thread(() -> {
            DatagramSocket rescueSocket = null;
            try {
                rescueSocket = new DatagramSocket(8888); rescueSocket.setBroadcast(true);
                rescueSocket.setSoTimeout(3000);
                rescueSocketRef = rescueSocket;
                udpBindFailCount = 0;
                FileLogger.log(this, "UDP", "✅ 监听已绑定 8888");

                byte[] buffer = new byte[512]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (!serviceDestroyed) {
                    try {
                        rescueSocket.receive(packet);
                    } catch (java.net.SocketTimeoutException te) {
                        continue;
                    }
                    String message = new String(packet.getData(), 0, packet.getLength());
                    if (message.startsWith("FIND_ME_SERVER:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) { saveNewServerUrl(parts[2].trim()); consecutiveFailures = 0; sendReport(true, true); break; }
                    } else if (message.startsWith("FIND_ME_CONFIG:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) { handleUpdateConfigUrl(parts[2].trim()); }
                    } else if (message.startsWith("FIND_ME_REPORT:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) { sendReport(true, true); }
                    } else if (message.startsWith("FIND_ME_REPORT_TEMP:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            String tempUrl = parts[2].trim();
                            if (isValidUrl(tempUrl)) {
                                sendReport(true, true, tempUrl);
                            } else {
                                FileLogger.log(this, "REPORT", "⚠️ 忽略非法 FIND_ME_REPORT_TEMP URL: [" + tempUrl + "]");
                            }
                        }
                    } else if (message.startsWith("FIND_ME_ALARM:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) { playAlarm(); }
                    } else if (message.startsWith("FIND_ME_MSG:")) {
                        String[] parts = message.split(":", 4);

                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            String msgContent = parts[2].trim();
                            showFloatingMessage(msgContent);
                        }
                        else if (parts.length == 4 && RESCUE_SECRET.equals(parts[1])) {
                            String senderId = parts[2].trim();
                            String msgContent = parts[3].trim();

                            if (senderId.equals(getUniqueDeviceId())) { continue; }

                            String dedupKey = senderId + ":" + msgContent;
                            long now = System.currentTimeMillis();
                            if (dedupKey.equals(lastReceivedMsg) && (now - lastReceivedMsgTime < 3000)) {
                                continue;
                            }
                            lastReceivedMsg = dedupKey;
                            lastReceivedMsgTime = now;

                            String shortName = senderId;
                            if (shortName.contains("_")) { shortName = shortName.substring(0, shortName.lastIndexOf("_")); }

                            saveChatToHistory("[" + shortName + "] " + msgContent);
                            FileLogger.log(TrackingService.this, "CHAT", "收到来自 " + shortName + " 的消息: " + msgContent);

                            if (ChatActivity.isForeground) {
                                Intent updateIntent = new Intent("UPDATE_CHAT_UI"); sendBroadcast(updateIntent);
                            }
                        }
                    } else if (message.startsWith("FIND_ME_CLEAR_HISTORY:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) { clearMessageHistory(); }
                    } else if (message.startsWith("FIND_ME_CLEAR_CHAT:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putString("chat_history", "").apply();
                            if (ChatActivity.isForeground) {
                                Intent updateIntent = new Intent("UPDATE_CHAT_UI");
                                sendBroadcast(updateIntent);
                            }
                            FileLogger.log(TrackingService.this, "CHAT", "🧹 收到云端指令，已清空本地聊天记录");
                        }
                    } else if (message.startsWith("FIND_ME_SET_SMS_NUM:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            String newNum = parts[2].trim();
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putString("emergency_sms_number", newNum).apply();
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "紧急号码已更新: " + newNum, android.widget.Toast.LENGTH_LONG).show());
                        }
                    } else if (message.startsWith("FIND_ME_CLOUD_LOG:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            boolean enable = "1".equals(parts[2].trim());
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putBoolean("enable_cloud_log", enable).apply();
                            String stateMsg = enable ? "☁️ 云端日志已开启" : "☁️ 云端日志已关闭";
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, stateMsg, android.widget.Toast.LENGTH_SHORT).show());
                        }
                    } else if (message.startsWith("FIND_ME_CHAT_TOGGLE:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            boolean currentLocked = isChatLocked(); setChatLocked(!currentLocked);
                            String stateMsg = !currentLocked ? "🔒 聊天功能已锁定" : "🔓 聊天功能已解锁";
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, stateMsg, android.widget.Toast.LENGTH_SHORT).show());
                        }
                    } else if (message.startsWith("FIND_ME_CHAT_UNLOCK:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) { setChatLocked(false); handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "🔓 聊天功能已解锁", android.widget.Toast.LENGTH_SHORT).show()); }
                    } else if (message.startsWith("FIND_ME_CHAT_LOCK:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) { setChatLocked(true); handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "🔒 聊天功能已锁定", android.widget.Toast.LENGTH_SHORT).show()); }
                    } else if (message.startsWith("FIND_ME_UPLOAD_LOG:")) {
                        String rest = message.substring("FIND_ME_UPLOAD_LOG:".length());
                        int secretEnd = rest.indexOf(":");
                        String secret = secretEnd < 0 ? rest : rest.substring(0, secretEnd);
                        String payload = secretEnd < 0 ? "" : rest.substring(secretEnd + 1).trim();

                        if (RESCUE_SECRET.equals(secret)) {
                            boolean clearAfter = payload.endsWith(":clear") || "clear".equals(payload);
                            String urlPart = payload;
                            if (urlPart.endsWith(":clear")) {
                                urlPart = urlPart.substring(0, urlPart.length() - 6);
                            }
                            if ("clear".equals(urlPart)) {
                                urlPart = "";
                            }
                            String tempUrl = null;
                            if (!urlPart.isEmpty()) {
                                if (isValidUrl(urlPart)) {
                                    tempUrl = urlPart;
                                } else {
                                    FileLogger.log(this, "UPLOAD_LOG", "⚠️ 忽略非法 tempUrl: [" + urlPart + "]（不含 :// 或过短），使用主服务器");
                                }
                            }
                            uploadLog(tempUrl, clearAfter);
                        }
                    } else if (message.startsWith("FIND_ME_LOCATE_ONCE:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            requestSingleLocationAndReport();
                        }
                    } else if (message.startsWith("FIND_ME_SCAN_WIFI:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            new Thread(() -> {
                                List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(this);
                                FileLogger.log(this, "WIFI", "📡 UDP 触发扫描: " + nearby.size() + " 个热点");
                            }).start();
                        }
                    } else if (message.startsWith("FIND_ME_RESTART_UDP:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            break;
                        }
                    } else if (message.startsWith("FIND_ME_TEST_LOW_BATTERY:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            FileLogger.log(this, "BATTERY", "🧪 UDP 触发模拟低电量告警");
                            sendReport(true, true);
                            sendLowBatterySms(15);
                        }
                    }
                }
            } catch (java.net.BindException be) {
                udpBindFailCount++;
                FileLogger.log(this, "UDP", "❌ bind 失败 (第 " + udpBindFailCount + " 次): " + be.getMessage());
            } catch (Exception e) {
                Log.e("TrackingService", "UDP 监听异常: " + e.toString());
                FileLogger.log(this, "UDP", "❌ 监听异常: " + e.getMessage());
            } finally {
                if (rescueSocket != null && !rescueSocket.isClosed()) rescueSocket.close();
                rescueSocketRef = null;
                isListeningForRescue = false;

                if (!serviceDestroyed) {
                    if (udpBindFailCount >= 5) {
                        FileLogger.log(this, "UDP", "⚠️ 连续 " + udpBindFailCount + " 次 bind 失败，停止重试直到下次手动触发");
                    } else {
                        handler.postDelayed(() -> {
                            if (!isListeningForRescue && !serviceDestroyed) {
                                FileLogger.log(TrackingService.this, "UDP", "监听断开，5秒后尝试重连...");
                                triggerRescueListener();
                            }
                        }, 5000);
                    }
                }
            }
        }).start();
    }

    private void parseResponse(JSONObject res) {
        if (!res.has("config")) return; JSONObject cfg = res.optJSONObject("config"); if (cfg == null) return;
        long newInterval = cfg.optLong("report_interval_seconds", 1800) * 1000L; String newAccuracy = cfg.optString("accuracy_mode", "coarse");
        boolean newEnableWifiLock = cfg.optBoolean("enable_wifi_lock", true); boolean newEnableMotionTrigger = cfg.optBoolean("enable_motion_trigger", true);
        if (newEnableWifiLock != enableWifiLock || newEnableMotionTrigger != enableMotionTrigger) { enableWifiLock = newEnableWifiLock; enableMotionTrigger = newEnableMotionTrigger; toggleMotionSensor(enableMotionTrigger); }
        if (newInterval != reportIntervalMs || !newAccuracy.equals(accuracyMode)) { reportIntervalMs = newInterval; accuracyMode = newAccuracy; handler.post(() -> { startLocationUpdates(); if (reportCount >= 3) scheduleNextReport(); }); }
        String nextServerUrl = cfg.optString("next_server_url", ""); if (!nextServerUrl.isEmpty() && !nextServerUrl.equals(getCurrentServerUrl())) { saveNewServerUrl(nextServerUrl); sendReport(true, true); }
        String nextConfigUrl = cfg.optString("next_config_url", ""); if (!nextConfigUrl.isEmpty() && !nextConfigUrl.equals(getRemoteConfigUrl())) { handleUpdateConfigUrl(nextConfigUrl); }
        handleIconVisibility(cfg.optBoolean("hide_icon", true));
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        FileLogger.log(this, "SERVICE", "⚠️ onLowMemory: 系统内存压力");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            try {
                ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
                ActivityManager.getMyMemoryState(info);
                FileLogger.log(this, "SERVICE", "⚠️ onTrimMemory level=" + level
                        + ", importance=" + importanceStr(info.importance));
            } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "⚠️ onTrimMemory level=" + level);
            }
        }
    }

    private String importanceStr(int imp) {
        switch (imp) {
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND: return "FOREGROUND";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE: return "FOREGROUND_SERVICE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE: return "VISIBLE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE: return "PERCEPTIBLE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE: return "SERVICE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED: return "CACHED";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY: return "EMPTY";
            default: return String.valueOf(imp);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        FileLogger.log(this, "SERVICE", "🛑 服务销毁");

        serviceDestroyed = true;

        if (rescueSocketRef != null) {
            try { rescueSocketRef.close(); } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "⚠️ close rescueSocket 失败: " + e.getMessage());
            }
            rescueSocketRef = null;
        }

        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "⚠️ removeUpdates 失败: " + e.getMessage());
            }
        }

        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                for (android.view.View v : new ArrayList<>(activeBubbles)) {
                    try { wm.removeView(v); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        activeBubbles.clear();

        if (batteryMonitor != null) { try { unregisterReceiver(batteryMonitor); } catch (Exception ignored) {} }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorManager != null && motionListener != null) sensorManager.cancelTriggerSensor(motionListener, motionSensor);
        try { ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE); if (cm != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}

        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        // [改进] 10 分钟节流：用户反复划掉 App 时，每次 onTaskRemoved 都排一次闹钟是浪费
        long now = System.currentTimeMillis();
        if (now - lastOnTaskRemovedTime < ON_TASK_REMOVED_THROTTLE_MS) {
            FileLogger.log(this, "SERVICE", "⚠️ 任务栈清空(10分钟内已排过自救，跳过)");
            return;
        }
        lastOnTaskRemovedTime = now;

        FileLogger.log(this, "SERVICE", "⚠️ 任务栈已清空(如退出聊天/侧滑)，触发兜底自救...");
        Intent restartServiceIntent = new Intent(getApplicationContext(), TrackingService.class);
        restartServiceIntent.setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_ONE_SHOT;
        PendingIntent restartServicePendingIntent = PendingIntent.getService(getApplicationContext(), 1, restartServiceIntent, flags);
        AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            long triggerAt = System.currentTimeMillis() + 1000;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmService.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, restartServicePendingIntent);
                } else {
                    alarmService.setExact(AlarmManager.RTC_WAKEUP, triggerAt, restartServicePendingIntent);
                }
            } catch (SecurityException e) {
                FileLogger.log(this, "SERVICE", "⚠️ onTaskRemoved 精确闹钟权限缺失，降级为不精确: " + e.getMessage());
                alarmService.set(AlarmManager.RTC_WAKEUP, triggerAt, restartServicePendingIntent);
            }
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
