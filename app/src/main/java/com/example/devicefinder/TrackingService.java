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
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

public class TrackingService extends Service implements LocationListener {

    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private PendingIntent alarmPendingIntent;

    private SensorManager sensorManager;
    private Sensor motionSensor;

    private long reportIntervalMs = 1800 * 1000L;
    private String accuracyMode = "coarse";
    private volatile Location lastLocation = null;
    private final AtomicInteger reportCount = new AtomicInteger(0);

    private boolean enableWifiLock = true;
    private boolean enableMotionTrigger = true;

    private static volatile long lastReportTime = 0;
    private final java.util.concurrent.atomic.AtomicBoolean reportInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final Object reportLock = new Object();
    private static volatile int consecutiveFailures = 0;
    private static volatile boolean isListeningForRescue = false;

    private static long lastNotificationClickTime = 0;
    private static int notificationClickCount = 0;
    private static long lastSensorTriggerTime = 0;

    private static String lastReceivedMsg = "";
    private static long lastReceivedMsgTime = 0;

    // ★ [修复] 2026-11-04 AI 将 URL 截断了，已恢复为你实际使用的后端接口
    private static String defaultServerUrl = "http://dingwei.bai001.de5.net/api/report";
    private static final String DEFAULT_REMOTE_CONFIG_URL = "https://your-config-url.com/config.txt";
    private static final String AUTH_TOKEN = "your_private_secret_key";
    private static final String RESCUE_SECRET = "your_private_secret_key";

    // ===== 协议前缀（P0-1 拆分）=====
    /** v2 悬浮文字：FIND_ME_FLOAT:secret:content（content 可含冒号） */
    private static final String FIND_ME_FLOAT_MSG = "FIND_ME_FLOAT";
    /** v2 聊天：FIND_ME_CHAT:secret:sender:content */
    private static final String FIND_ME_CHAT_MSG  = "FIND_ME_CHAT";
    // [COMPAT-20261105-v1] 旧前缀常量，v1 全部下线后删除本行
    private static final String FIND_ME_MSG_LEGACY = "FIND_ME_MSG";
    // [/COMPAT-20261105-v1]

    private static long lastConfigFetchTime = 0;
    private static final String PREF_BUBBLE = "bubble_pos_prefs";

    private static final String CHANNEL_ID_MESSAGE = "message_channel";
    private static final int NOTIF_ID_MESSAGE = 2001;
    private static final String PREF_ELDER_MODE = "elder_mode";
    private static final String CHANNEL_ID_SELF_CHECK = "self_check_channel";
    private static final int NOTIF_ID_SELF_CHECK = 3001;

    private BroadcastReceiver batteryMonitor;

    private volatile boolean serviceDestroyed = false;
    private volatile boolean rescueRetryScheduled = false;
    private volatile DatagramSocket rescueSocketRef = null;
    private final Object rescueLock = new Object();
    
    // 看门狗线程
    private Thread watchdogThread;

    // ★ [核心优化] 2026-11-04 全局同步锁与键值，用于持久化“上次成功上报时间”，解决冷启动看门狗失效问题
    private static final Object FILE_LOCK = new Object();
    private static final String PREF_LAST_SUCCESS_REPORT_TIME = "last_success_report_time";

    // ==================== [HTTP下行] 字段区 ====================
    private static final String PREF_EXECUTED_CMD_IDS = "executed_cmd_ids";
    private static final int EXECUTED_CMD_IDS_MAX = 100;
    private static final int FAST_POLL_BATTERY_THRESHOLD = 20;
    private static final int FAST_POLL_MAX_CONSECUTIVE = 5;
    private static final long CMD_TIME_WINDOW_MS = 60 * 1000L;

    private volatile boolean isFastPolling = false;
    private volatile int fastPollConsecutiveCount = 0;
    private final Object fastPollLock = new Object();
    private final java.util.Map<String, Long> recentExecutions =
            new java.util.concurrent.ConcurrentHashMap<>();
    // ==================== [HTTP下行] 字段区结束 ====================

    private final List<android.view.View> activeBubbles = new ArrayList<>();

    private long lastAlarmExpectedAt = 0;
    private long lastHeartbeatExpectedAt = 0;
    private long serviceCreateTimeMs = 0;
    private int udpBindFailCount = 0;
    private long lastLocationLogTime = 0;
    private static long lastOnTaskRemovedTime = 0;
    private static final long ON_TASK_REMOVED_THROTTLE_MS = 10 * 60 * 1000L;
    private static final String PENDING_REPORTS_FILE = "pending_reports.jsonl";
    private static final long PENDING_QUEUE_MAX_BYTES = 1024 * 1024;

    private final java.util.concurrent.ExecutorService reportExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(5);

    @Override
    public void onCreate() {
        super.onCreate();
        serviceCreateTimeMs = SystemClock.elapsedRealtime();

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        FileLogger.log(this, "SERVICE", "🚀 追踪服务已启动 (或复活)");

        logStartupSelfCheck();

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
            multicastLock = wifiManager.createMulticastLock("DeviceFinder::Multicast");
            multicastLock.setReferenceCounted(false);
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

        handler.postDelayed(this::flushPendingReports, 5000);
        
        KeepAliveJobService.scheduleJob(this);
        startWatchdog();
    }

    private void startWatchdog() {
        if (watchdogThread != null && watchdogThread.isAlive()) return;
        watchdogThread = new Thread(() -> {
            while (!serviceDestroyed) {
                try {
                    Thread.sleep(10 * 60 * 1000); // 每 10 分钟检查一次
                    if (serviceDestroyed) break;
                    
                    long now = System.currentTimeMillis();
                    SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                    long lastSuccess = sp.getLong(PREF_LAST_SUCCESS_REPORT_TIME, 0);

                    // ★ [核心优化] 2026-11-04 冷启动时lastSuccess从 SharedPreferences 读取，确保看门狗时刻在线
                    if (lastSuccess > 0 && (now - lastSuccess > 60 * 60 * 1000L)) {
                        FileLogger.log(this, "WATCHDOG", "⚠️ 发现超 1 小时未成功上报，强制触发自救");
                        // ★ [优化] 去掉 handler.post 冗余线程切换，因为 sendReport 内部自带线程池
                        sendReport(true, true); 
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    FileLogger.log(this, "WATCHDOG", "异常: " + e.getMessage());
                }
            }
        });
        watchdogThread.start();
    }

    private void logStartupSelfCheck() {
    try {
        FileLogger.log(this, "SERVICE", "===== 启动自检 =====");
        FileLogger.log(this, "SERVICE", "[TS] 版本: " + BuildInfo.BUILD_VERSION
                + " (协议 v" + BuildInfo.PROTOCOL_VERSION + ")");
        String rom = Build.DISPLAY != null ? Build.DISPLAY : "unknown";
            FileLogger.log(this, "SERVICE", "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + " | ROM: " + rom);
            FileLogger.log(this, "SERVICE", "device_id: " + getUniqueDeviceId());

            try { FileLogger.log(this, "SERVICE", "安全补丁: " + Build.VERSION.SECURITY_PATCH); } catch (Exception ignored) {}
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
                } catch (Exception ignored) { perm.append("ADMIN=❓"); }
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

            try { FileLogger.log(this, "SERVICE", "老人模式: " + (isElderMode() ? "👴 开启" : "🕶️ 无痕")); } catch (Exception ignored) {}

            try {
                java.io.File qf = new java.io.File(getExternalFilesDir(null), PENDING_REPORTS_FILE);
                if (qf.exists() && qf.length() > 0) {
                    FileLogger.log(this, "SERVICE", "离线队列: " + (qf.length() / 1024) + "KB");
                }
            } catch (Exception ignored) {}

            FileLogger.log(this, "SERVICE", "===================");
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "❌ 自检块异常: " + e.getMessage());
        }
    }

    private String permStr(String perm) {
        try { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ? "✅" : "❌"; }
        catch (Exception e) { return "❓"; }
    }

    private String canScheduleExactAlarmsStr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "N/A";
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            return (am != null && am.canScheduleExactAlarms()) ? "✅" : "❌";
        } catch (Exception e) { return "❓"; }
    }

    private void scheduleKeepAliveJob() {
        if (alarmManager == null) return;
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_KEEP_ALIVE");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, 1002, intent, flags);

        long triggerAt = System.currentTimeMillis() + 15 * 60 * 1000L;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            else alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (SecurityException e) {
            FileLogger.log(this, "ALARM", "⚠️ 精确闹钟权限缺失，降级为不精确闹钟: " + e.getMessage());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    private void toggleMotionSensor(boolean enable) {
        if (sensorManager == null || motionSensor == null) return;
        if (enable && enableMotionTrigger) sensorManager.requestTriggerSensor(motionListener, motionSensor);
        else sensorManager.cancelTriggerSensor(motionListener, motionSensor);
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
        // ★ [新增] 2026-11-05 第一时间归类唤醒源，便于排查是谁救了服务
        logWakeSource(intent);

        // ★ [核心优化] 2026-11-04 修复了无 Action 时（侧滑退出重启）不执行自愈逻辑的 Bug
        if (intent == null || intent.getAction() == null) {
            FileLogger.log(this, "SERVICE",
                    "🔄 STICKY/无Action重启（intent=" + (intent == null ? "null" : "no-action") + "），触发兜底自愈");
            try { startForegroundNotification(); } catch (Exception ignored) {}
            selfHeal(); // 关键：恢复被杀的监听和定位等
            return START_STICKY;
        }

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

                selfHeal();
                scheduleKeepAliveJob();
                
            // ★ [核心优化] 2026-11-04 新增独立分支处理 JobScheduler 唤醒，避免和 Alarm 冲突
            } else if ("ACTION_JOB_WAKE_UP".equals(action)) {
                long uptimeMin = (SystemClock.elapsedRealtime() - serviceCreateTimeMs) / 60000;
                FileLogger.log(this, "JOB", "💓 JobScheduler 保底唤醒 (已运行 " + uptimeMin + " 分钟)");
                // 仅触发自愈检查，绝不重新 schedule 闹钟
                selfHeal();
                
            // ★ [新增] 2026-11-05 无障碍服务唤醒入口
            } else if ("ACTION_WAKE_BY_ACCESSIBILITY".equals(action)) {
                long uptimeMin = (SystemClock.elapsedRealtime() - serviceCreateTimeMs) / 60000;
                FileLogger.log(this, "ACCESSIBILITY", "♿ 无障碍唤醒，执行自愈 (已运行 " + uptimeMin + " 分钟)");
                selfHeal();
                
            // ★ [新增] 2026-11-05 组件自救唤醒入口（预留，配套 AlarmManager 兜底使用）
            } else if ("ACTION_WAKE_BY_COMPONENT_SELF_HEAL".equals(action)) {
                FileLogger.log(this, "SERVICE", "🔄 组件自救唤醒，执行自愈");
                selfHeal();
                
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
                    } catch (Exception e) { FileLogger.log(this, "CONFIG", "⚠️ interval 参数非法: " + intervalStr); }
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
                if (changed.length() > 0) FileLogger.log(this, "CONFIG", "📝 运行时配置已更新: " + changed);
                else FileLogger.log(this, "CONFIG", "⚠️ UPDATE_RUNTIME_CONFIG 未提供任何参数");
            } else if ("ACTION_LOCATE_ONCE".equals(action)) {
                FileLogger.log(this, "LOCATION", "📍 收到手动定位指令");
                requestSingleLocationAndReport();
            } else if ("ACTION_SCAN_WIFI".equals(action)) {
                new Thread(() -> {
                    try {
                        // ★ [修复] 2026-11-04 补全 AI 丢失的泛型 List<JSONObject>
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
                if (rescueSocketRef != null) { try { rescueSocketRef.close(); } catch (Exception ignored) {} }
            } else if ("ACTION_TEST_LOW_BATTERY".equals(action)) {
                FileLogger.log(this, "BATTERY", "🧪 模拟低电量告警 (15%)");
                sendReport(true, true);
                sendLowBatterySms(15);
            } else if ("ACTION_TOGGLE_ELDER_MODE".equals(action)) {
                String val = intent.getStringExtra("elder_mode");
                boolean target;
                if (val != null) target = "1".equals(val) || "true".equalsIgnoreCase(val);
                else target = !isElderMode();
                setElderMode(target);
                handler.post(() -> android.widget.Toast.makeText(this,
                        target ? "👴 老人模式已开启（脱敏通知）" : "🕶️ 无痕模式已开启（仅悬浮窗）",
                        android.widget.Toast.LENGTH_LONG).show());
            } else if ("ACTION_SELF_CHECK".equals(action)) {
                FileLogger.log(this, "SELF_CHECK", "📋 收到自检指令");
                showSelfCheckNotification();
            }
        }

        try { startForegroundNotification(); } catch (Exception ignored) {}
        return START_STICKY;
    }

    // ==================== 唤醒源日志归类 (2026-11-05 新增) ====================
    private void logWakeSource(Intent intent) {
        String source = "未知原因唤醒/冷启动";
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case "ACTION_WAKE_BY_ACCESSIBILITY":
                        source = "♿ [无障碍服务] 强力注入拉活";
                        break;
                    case "ACTION_WAKE_BY_COMPONENT_SELF_HEAL":
                        source = "🔄 [组件自救] 侧滑销毁瞬间就地复活";
                        break;
                    case "ACTION_JOB_WAKE_UP":
                        source = "⏰ [JobScheduler] 定期保底拉活";
                        break;
                    case "ACTION_KEEP_ALIVE":
                        source = "💓 [Alarm保活闹钟] 15分钟心跳复活";
                        break;
                    case "ACTION_ALARM_TRIGGER":
                        source = "⏰ [上报闹钟] 定时任务主动唤醒";
                        break;
                    case "ACTION_HEARTBEAT":
                        source = "💓 [长心跳] 6小时保底业务唤醒";
                        break;
                    default:
                        source = "📥 业务动作 Action: " + action;
                        break;
                }
            }
        }
        FileLogger.log(this, "WAKE_SOURCE", "🚩 当前服务启动源: " + source);
    }

    private void selfHeal() {
        try {
            try { startForegroundNotification(); } catch (Exception ignored) {}

            boolean listening;
            synchronized (rescueLock) {
                listening = isListeningForRescue;
            }
            if (!listening && !serviceDestroyed) {
                FileLogger.log(this, "SERVICE", "⚠️ 自愈: UDP 监听已死，重新拉起");
                triggerRescueListener();
            }

            try {
                List<String> providers = locationManager.getProviders(true);
                if (providers == null || providers.isEmpty()) {
                    FileLogger.log(this, "SERVICE", "⚠️ 自愈: 无可用 provider，重新注册定位监听");
                    startLocationUpdates();
                }
            } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "⚠️ 自愈: 检查定位失败，尝试重注册: " + e.getMessage());
                try { startLocationUpdates(); } catch (Exception ignored) {}
            }

            if (networkCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileLogger.log(this, "SERVICE", "⚠️ 自愈: networkCallback 为空，重新注册");
                registerNetworkCallback();
            }

            // ★ [核心优化] 2026-11-04 从 SharedPreferences 读取持久化的成功上报时间
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            long lastSuccess = sp.getLong(PREF_LAST_SUCCESS_REPORT_TIME, 0);
            if (lastSuccess > 0 && System.currentTimeMillis() - lastSuccess > 30 * 60 * 1000L) {
                long gapMin = (System.currentTimeMillis() - lastSuccess) / 60000;
                FileLogger.log(this, "SERVICE", "⚠️ 自愈: 距上次成功上报 " + gapMin + " 分钟，强制触发一次");
                sendReport(true, false);
            }
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "❌ 自愈心跳异常: " + e.getMessage());
        }
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

    private boolean isElderMode() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getBoolean(PREF_ELDER_MODE, false);
    }

    private void setElderMode(boolean enabled) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putBoolean(PREF_ELDER_MODE, enabled).commit();
        FileLogger.log(this, "CONFIG", enabled ? "👴 老人模式已开启（脱敏通知）" : "🕶️ 无痕模式已开启（仅悬浮窗）");
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
                if (msgUrl == null) { Log.e("TrackingService", "syncMessageToServer: server_url 非法，跳过"); return; }
                URL url = new URL(msgUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Connection", "close");

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
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.CENTER;
                wm.addView(textView, params);
                handler.postDelayed(() -> { try { wm.removeView(textView); } catch (Exception ignored) {} }, 3000);
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
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                int bubbleW = (int) (screenW * 0.85f);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        bubbleW, WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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

                // ★ [修复] 2026-11-04 按照你的要求，恢复长按复制功能。
                // 注意：极少数老旧机型可能因焦点问题在特殊操作下闪退，若以后发现异常，请改回 false
                textView.setTextIsSelectable(true);
                textView.setFocusable(true);
                textView.setFocusableInTouchMode(true);

                final android.widget.LinearLayout layoutRef = layout;
                attachDragAndRemember(wm, layout, params, "bubble_pos",
                        () -> { try { wm.removeView(layoutRef); } catch (Exception ignored) {} }, null);
            } catch (Exception e) {
                FileLogger.log(this, "UI", "❌ showMessageHistory 失败: " + e.getMessage());
            }
        });
    }

    private void showFloatingMessage(String message) {
        saveMessageToHistory(message);
        syncMessageToServer(message);
        if (isElderMode()) showElderMessageNotification();

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
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                textView.setLayoutParams(tvParams);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    textView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                    textView.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
                }
                layout.addView(textView);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        msgMaxW, WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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

    private void ensureElderMessageChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID_MESSAGE) != null) return;

            NotificationChannel ch = new NotificationChannel(CHANNEL_ID_MESSAGE, "家庭留言（老人模式）", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("收到新留言时提醒（内容脱敏，点击查看）");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 400, 200, 400});

            android.net.Uri soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            if (soundUri != null) {
                ch.setSound(soundUri, new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            }
            nm.createNotificationChannel(ch);
        } catch (Exception e) {
            FileLogger.log(this, "NOTIFY", "⚠️ 创建通知渠道失败: " + e.getMessage());
        }
    }

    private void showElderMessageNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                FileLogger.log(this, "NOTIFY", "⚠️ 无 POST_NOTIFICATIONS 权限，跳过通知");
                return;
            }
            ensureElderMessageChannel();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent historyIntent = new Intent(this, TrackingService.class);
            historyIntent.setAction("ACTION_SHOW_HISTORY");
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getService(this, 3001, historyIntent, piFlags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder = new Notification.Builder(this, CHANNEL_ID_MESSAGE);
            else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_HIGH);
                builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
            }

            Notification n = builder.setContentTitle("📩 家庭留言").setContentText("有新留言，点击查看")
                    .setSmallIcon(android.R.drawable.ic_dialog_email).setContentIntent(pi)
                    .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
            nm.notify(NOTIF_ID_MESSAGE, n);
            FileLogger.log(this, "NOTIFY", "📩 已发送脱敏通知（老人模式）");
        } catch (Exception e) {
            FileLogger.log(this, "NOTIFY", "❌ 发送通知失败: " + e.getMessage());
        }
    }

    private void ensureSelfCheckChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID_SELF_CHECK) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID_SELF_CHECK, "设备自检", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("拨号 *#*#4321#*#* 触发的设备自检结果");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {}
    }

    private void showSelfCheckNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                FileLogger.log(this, "SELF_CHECK", "⚠️ 无 POST_NOTIFICATIONS 权限，无法发送自检通知");
                return;
            }
            ensureSelfCheckChannel();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            StringBuilder sb = new StringBuilder();
            sb.append("[TS] 版本：").append(BuildInfo.BUILD_VERSION)
              .append("（协议 v").append(BuildInfo.PROTOCOL_VERSION).append("）\n");
            sb.append("老人模式：").append(isElderMode() ? "👴 已开启" : "🕶️ 无痕").append("\n");
            try {
                boolean loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean bgLoc = Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
                sb.append("定位权限：").append(loc ? "✅ 前台" : "❌ 前台").append(" / ").append(bgLoc ? "✅ 后台" : "❌ 后台").append("\n");
            } catch (Exception ignored) {}
            try {
                boolean notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                sb.append("通知权限：").append(notif ? "✅ 已授予" : "❌ 未授予").append("\n");
            } catch (Exception ignored) {}
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID_MESSAGE);
                    sb.append("留言通知渠道：").append(ch != null ? "✅ 已创建" : "⚠️ 未创建（老人模式首条留言时自动创建）").append("\n");
                }
            } catch (Exception ignored) {}
            try {
                boolean overlay = Settings.canDrawOverlays(this);
                sb.append("悬浮窗权限：").append(overlay ? "✅" : "❌").append("\n");
            } catch (Exception ignored) {}
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                String typeName = (ni != null && ni.isConnected()) ? ni.getTypeName() : "离线";
                String[] wifi = DeviceInfoUtils.getWifiInfo(this);
                sb.append("网络：").append(typeName).append("（").append(wifi[0]).append("）\n");
            } catch (Exception ignored) {}
            try {
                int[] battery = DeviceInfoUtils.getBatteryInfo(this);
                sb.append("电量：").append(battery[0]).append("%（").append(battery[1] == 1 ? "充电中" : "未充电").append("）\n");
            } catch (Exception ignored) {}
            sb.append("上报间隔：").append(reportIntervalMs / 60000).append(" 分钟\n");
            
            // ★ [核心优化] 2026-11-04 从 SharedPreferences 读取持久化时间
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            long lastSuccess = sp.getLong(PREF_LAST_SUCCESS_REPORT_TIME, 0);
            if (lastSuccess > 0) {
                long gapMin = (System.currentTimeMillis() - lastSuccess) / 60000;
                sb.append("上次成功上报：").append(gapMin).append(" 分钟前");
            } else sb.append("上次成功上报：本次启动后暂无");

            Intent piIntent = new Intent(this, TrackingService.class);
            piIntent.setAction("ACTION_RESET_NOTIFICATION");
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getService(this, 3002, piIntent, piFlags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder = new Notification.Builder(this, CHANNEL_ID_SELF_CHECK);
            else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_DEFAULT);
            }

            Notification n = builder.setContentTitle("📋 设备自检")
                    .setContentText("老人模式：" + (isElderMode() ? "已开启" : "无痕"))
                    .setStyle(new Notification.BigTextStyle().bigText(sb.toString()))
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentIntent(pi).setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build();
            nm.notify(NOTIF_ID_SELF_CHECK, n);
            FileLogger.log(this, "SELF_CHECK", "📋 自检通知已发送");
        } catch (Exception e) {
            FileLogger.log(this, "SELF_CHECK", "❌ 自检通知发送失败: " + e.getMessage());
        }
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
                        if (pendingSingleTap[0] != null) gestureHandler.removeCallbacks(pendingSingleTap[0]);
                    }
                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        if (!dragging[0] && onSingleTap != null) {
                            if (pendingSingleTap[0] != null) gestureHandler.removeCallbacks(pendingSingleTap[0]);
                            pendingSingleTap[0] = () -> { if (onSingleTap != null) onSingleTap.run(); };
                            gestureHandler.postDelayed(pendingSingleTap[0], 300);
                        }
                        return true;
                    }
                    @Override
                    public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (pendingSingleTap[0] != null) { gestureHandler.removeCallbacks(pendingSingleTap[0]); pendingSingleTap[0] = null; }
                        if (onDoubleTap != null) onDoubleTap.run();
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
                    if (lastSmsTime != 0) sp.edit().putLong("last_low_battery_sms_time", 0).apply();
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
            android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;

            int originalMode = audioManager.getRingerMode();
            FileLogger.log(this, "ALARM", "🔊 开始播放警报，当前 RingerMode=" + originalMode);

            if (originalMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                try {
                    audioManager.setRingerMode(android.media.AudioManager.RINGER_MODE_NORMAL);
                    FileLogger.log(this, "ALARM", "⚠️ 已临时切到正常模式（原 mode=" + originalMode + "）");
                } catch (SecurityException e) {
                    FileLogger.log(this, "ALARM", "❌ 无法切 RingerMode（缺 MODIFY_AUDIO_SETTINGS）: " + e.getMessage());
                }
            }

            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVolume, 0);

            android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(
                    getApplicationContext(),
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM));

            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                }
                ringtone.play();

                try {
                    android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        long[] pattern = {0, 500, 200, 500, 200, 500, 200, 500, 200, 500};
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
                        } else {
                            vibrator.vibrate(pattern, -1);
                        }
                    }
                } catch (Exception ignored) {}

                FileLogger.log(this, "ALARM", "🔊 警报已播放 + 震动");

                final int finalOriginalMode = originalMode;
                handler.postDelayed(() -> {
                    try {
                        if (ringtone.isPlaying()) ringtone.stop();
                        if (finalOriginalMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                            audioManager.setRingerMode(finalOriginalMode);
                            FileLogger.log(this, "ALARM", "🔇 已恢复原 RingerMode=" + finalOriginalMode);
                        }
                    } catch (Exception ignored) {}
                }, 30000);
            } else FileLogger.log(this, "ALARM", "❌ 获取警报铃声失败");
        } catch (Exception e) {
            FileLogger.log(this, "ALARM", "❌ 播放警报异常: " + e.getMessage());
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

        // ★ 修改：startForeground 增加容错降级 2026-11-04 14:15
        // ★ [修复] 2026-11-04 Android 14+ 不带类型的 startForeground 会被系统直接禁止
        //   降级必须用 dataSync 类型，不能退回无类型调用。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 统一带 LOCATION 类型
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1001, notification);
            }
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "❌ 带 location type 的 startForeground 失败: " + e.getMessage());
            // ★ 降级尝试：使用 dataSync 类型（Manifest 已声明 location|dataSync 双类型）
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(1001, notification);
                }
                FileLogger.log(this, "SERVICE", "✅ 已降级为 dataSync 类型前台服务");
            } catch (Exception e2) {
                FileLogger.log(this, "SERVICE", "❌❌ 所有类型均失败，服务可能被系统清理: " + e2.getMessage());
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
        if (!isValidUrl(newUrl)) { FileLogger.log(this, "CONFIG", "⚠️ 拒绝保存非法 server_url: [" + newUrl + "]"); return; }
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("saved_server_url", newUrl).commit();
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
        if (!isValidUrl(url)) { FileLogger.log(this, "CONFIG", "⚠️ 拒绝保存非法 config_url: [" + url + "]"); return; }
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("saved_config_url", url).commit();
        FileLogger.log(this, "CONFIG", "📝 config_url 已更新: " + url);
    }

    private void handleUpdateConfigUrl(String newConfigUrl) {
        if (newConfigUrl != null && !newConfigUrl.isEmpty()) { saveRemoteConfigUrl(newConfigUrl); lastConfigFetchTime = 0; fetchRemoteConfig(); }
    }

    private void handleIconVisibility(boolean hideIcon) {
        boolean ok = LauncherIconUtils.setLauncherIconVisible(this, !hideIcon);
        if (ok) FileLogger.log(this, "SERVICE", "🔧 图标状态: " + (hideIcon ? "隐藏" : "显示"));
        else FileLogger.log(this, "SERVICE", "⚠️ 修改图标状态失败 (hideIcon=" + hideIcon + ")");
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
                        flushPendingReports();
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
                FileLogger.log(this, "LOCATION", "⚠️ getProviders 失败，fallback 到 isProviderEnabled: " + e.getMessage());
                try { gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                try { netOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                allProviders = "fallback(GPS=" + gpsOn + ",NET=" + netOn + ")";
            }
            FileLogger.log(this, "LOCATION", "provider 状态: GPS=" + (gpsOn ? "on" : "off")
                    + ", NETWORK=" + (netOn ? "on" : "off")
                    + " | 全部: " + allProviders + " | 模式=" + accuracyMode);

            long minTime = 60000L;
            if ("fine".equals(accuracyMode) && gpsOn) {
                try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, 0f, this); }
                catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 GPS 失败: " + e.getMessage()); }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, minTime, 0f, this);
                    FileLogger.log(this, "LOCATION", "📡 已请求 FUSED_PROVIDER");
                } catch (Exception e) {
                    FileLogger.log(this, "LOCATION", "⚠️ FUSED_PROVIDER 不可用（忽略）: " + e.getMessage());
                }
            }
                        boolean hasNetworkProvider = false;
            try {
                List<String> providerList = locationManager.getProviders(true);
                hasNetworkProvider = providerList != null && providerList.contains(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignored) {}
            if (hasNetworkProvider) {
                try { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTime, 0f, this); }
                catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 NETWORK 失败: " + e.getMessage()); }
            } else {
                FileLogger.log(this, "LOCATION", "ℹ️ NETWORK provider 不存在（Android 12+ 正常），使用 FUSED");
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
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
                FileLogger.log(this, "LOCATION", "📡 已请求 FUSED_PROVIDER（单次）");
            } catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 FUSED_PROVIDER 失败（忽略）: " + e.getMessage()); }
        }
        try {
            List<String> pl = locationManager.getProviders(true);
            if (pl != null && pl.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
                FileLogger.log(this, "LOCATION", "📡 已请求 NETWORK provider（单次）");
            }
        } catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 NETWORK 失败: " + e.getMessage()); }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
            FileLogger.log(this, "LOCATION", "📡 已请求 GPS provider（单次）");
        } catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 GPS 失败: " + e.getMessage()); }

        timeoutHandler.postDelayed(() -> {
            if (!reported[0]) {
                reported[0] = true;
                try { locationManager.removeUpdates(singleListener); } catch (Exception ignored) {}
                FileLogger.log(this, "LOCATION", "⚠️ 60 秒内未获取到新定位，lastLocation="
                        + (lastLocation != null ? ("旧值(lat=" + lastLocation.getLatitude() + ")") : "无"));
                sendReport(true, false);
            }
        }, 60000);
    }

    private Location acquireFreshLocation(long timeoutMs) {
        final Location[] result = new Location[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        android.location.LocationListener tmpListener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null && result[0] == null) { result[0] = location; latch.countDown(); }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 0, 0, tmpListener, Looper.getMainLooper()); }
                catch (Exception ignored) {}
            }
            try {
                List<String> pl = locationManager.getProviders(true);
                if (pl != null && pl.contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, tmpListener, Looper.getMainLooper());
                }
            } catch (Exception ignored) {}
            try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, tmpListener, Looper.getMainLooper()); }
            catch (Exception ignored) {}
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try { locationManager.removeUpdates(tmpListener); } catch (Exception ignored) {}
        }
        return result[0];
    }

    private void scheduleNextReport() {
        if (alarmManager == null || alarmPendingIntent == null) return;
        int rc = reportCount.get();
        long delay = (rc == 1 || rc == 2) ? 3 * 60 * 1000L : reportIntervalMs;
        long triggerAtMillis = System.currentTimeMillis() + delay;
        lastAlarmExpectedAt = triggerAtMillis;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            else alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
        } catch (SecurityException e) {
            FileLogger.log(this, "ALARM", "⚠️ 精确闹钟权限缺失，降级: " + e.getMessage());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
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

        reportExecutor.execute(() -> {
            if (!reportInFlight.compareAndSet(false, true)) {
                FileLogger.log(TrackingService.this, "REPORT", "⏭ 已有上报在执行，跳过本次");
                return;
            }
            boolean success = false;
            if (enableWifiLock && wifiLock != null && !wifiLock.isHeld()) {
                try { wifiLock.acquire(); } catch (Exception e) { FileLogger.log(TrackingService.this, "REPORT", "⚠️ WifiLock acquire 失败: " + e.getMessage()); }
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

                Location loc = lastLocation;
                long locAgeMs = loc != null ? (System.currentTimeMillis() - loc.getTime()) : Long.MAX_VALUE;
                final long FRESH_THRESHOLD_MS = 10 * 60 * 1000L;
                final long FRESH_TIMEOUT_MS = 10000L;

                if (loc == null || locAgeMs > FRESH_THRESHOLD_MS) {
                    if (loc != null && locAgeMs > 15 * 60 * 1000L && !force) {
                        FileLogger.log(TrackingService.this, "REPORT", "⏭ 跳过定位刷新（旧值 " + (locAgeMs / 60000) + " 分钟前），直接使用旧坐标");
                    } else {
                        long t0 = System.currentTimeMillis();
                        Location fresh = acquireFreshLocation(FRESH_TIMEOUT_MS);
                        long costMs = System.currentTimeMillis() - t0;
                        if (fresh != null) {
                            FileLogger.log(TrackingService.this, "REPORT", "🔎 主动刷新定位成功 (耗时 " + costMs + "ms, provider=" + fresh.getProvider() + ", acc=" + (int) fresh.getAccuracy() + "m)");
                            loc = fresh;
                            lastLocation = fresh;
                        } else {
                            FileLogger.log(TrackingService.this, "REPORT", "⚠️ 主动刷新定位超时 (耗时 " + costMs + "ms, 旧值 " + (locAgeMs == Long.MAX_VALUE ? "无" : (locAgeMs / 1000) + "s 前") + ")，将使用旧值");
                        }
                    }
                }

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
                json.put("protocol_version", BuildInfo.PROTOCOL_VERSION);
                json.put("build_version", BuildInfo.BUILD_VERSION);
                json.put("os_version", "Android " + Build.VERSION.RELEASE); json.put("chat_locked", isChatLocked());
                json.put("elder_mode", isElderMode());
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE); json.put("is_screen_on", pm != null && pm.isInteractive()); json.put("uptime_ms", SystemClock.elapsedRealtime());
                JSONObject battObj = new JSONObject(); battObj.put("level", battery[0]); battObj.put("is_charging", battery[1] == 1); json.put("battery", battObj);
                JSONObject netObj = new JSONObject(); netObj.put("wifi_ssid", wifi[0]); netObj.put("wifi_bssid", wifi[1]); netObj.put("wifi_rssi", Integer.parseInt(wifi[2])); netObj.put("local_ip", ip); json.put("network", netObj);

                JSONObject locObj = new JSONObject();
                if (loc != null) {
                    long ageS = Math.max(0, (System.currentTimeMillis() - loc.getTime()) / 1000);
                    locObj.put("latitude", loc.getLatitude());
                    locObj.put("longitude", loc.getLongitude());
                    locObj.put("accuracy", loc.getAccuracy());
                    locObj.put("provider", loc.getProvider());
                    locObj.put("time", loc.getTime());
                    locObj.put("age_s", ageS);
                } else {
                    locObj.put("latitude", JSONObject.NULL); locObj.put("longitude", JSONObject.NULL);
                    locObj.put("accuracy", -1.0); locObj.put("provider", "none");
                    locObj.put("time", 0L); locObj.put("age_s", -1);
                }
                json.put("location", locObj);

                if (isCloudLogEnabled()) {
                    String memoryLogs = FileLogger.getMemoryLogs();
                    if (!memoryLogs.isEmpty()) json.put("cloud_logs", memoryLogs);
                }

                try {
                    java.util.Set<String> executedIds = loadExecutedCmdIds();
                    if (!executedIds.isEmpty()) {
                        JSONArray execArr = new JSONArray();
                        for (String id : executedIds) execArr.put(id);
                        json.put("last_executed_cmd_ids", execArr);
                    }
                } catch (Exception e) {
                    FileLogger.log(TrackingService.this, "HTTP_CMD", "⚠️ 上报 executedIds 失败: " + e.getMessage());
                }

                if (!isOnline && overrideUrl == null) {
                    FileLogger.log(TrackingService.this, "REPORT", "⚠️ 网络离线，排队等待网络恢复后补发");
                    queuePendingReport(json);
                    return;
                }

                if (withNearbyWifi) {
                    try {
                        List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this);
                        if (nearby != null && !nearby.isEmpty()) {
                            JSONArray arr = new JSONArray();
                            for (JSONObject obj : nearby) arr.put(obj);
                            json.put("nearby_wifi", arr);
                        }
                    } catch (Exception ignored) {}
                }

                URL url = new URL(activeUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);   // [P0-3] 15000 → 8000，服务器 down 时快速失败
                conn.setReadTimeout(8000);      // [P0-3]
                conn.setDoOutput(true);
                conn.setRequestProperty("Connection", "close");

                long httpStart = System.currentTimeMillis();
                try {
                    OutputStream os = conn.getOutputStream();
                    os.write(json.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    int httpCode = conn.getResponseCode();
                    long httpCost = System.currentTimeMillis() - httpStart;

                    if (httpCode == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder(); String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        if (overrideUrl == null) parseResponse(new JSONObject(sb.toString()));
                        success = true;

                        String locDesc;
                        if (loc != null) {
                            long ageS = Math.max(0, (System.currentTimeMillis() - loc.getTime()) / 1000);
                            locDesc = String.format(java.util.Locale.US, "lat=%.5f lng=%.5f acc=%dm age=%ds src=%s",
                                    loc.getLatitude(), loc.getLongitude(), (int) loc.getAccuracy(), ageS,
                                    loc.getProvider() != null ? loc.getProvider() : "?");
                        } else locDesc = "无定位";
                        FileLogger.log(TrackingService.this, "REPORT", "✅ 上报成功 " + locDesc + " (耗时 " + httpCost + "ms)");
                    } else {
                        FileLogger.log(TrackingService.this, "REPORT", "⚠️ 上报返回非200: HTTP " + httpCode + " (耗时 " + httpCost + "ms) | " + activeUrl);
                        if (overrideUrl == null) queuePendingReport(json);
                    }
                } catch (Exception e) {
                    String errMsg = e.getMessage();
                    if (errMsg != null && errMsg.contains("Unable to resolve host"))
                        FileLogger.log(TrackingService.this, "REPORT", "❌ DNS 解析失败: " + errMsg);
                    else FileLogger.log(TrackingService.this, "REPORT", "❌ 上报异常: " + errMsg);
                    Log.e("TrackingService", "===== 上报失败啦 =====");
                    Log.e("TrackingService", "目标URL: " + activeUrl);
                    Log.e("TrackingService", "报错原因: " + e.toString());
                    if (overrideUrl == null) queuePendingReport(json);
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.contains("Unable to resolve host"))
                    FileLogger.log(TrackingService.this, "REPORT", "❌ DNS 解析失败: " + errMsg);
                else FileLogger.log(TrackingService.this, "REPORT", "❌ 准备上报异常: " + errMsg);
            } finally {
                try {
                    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                    if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
                } catch (Exception ignored) {}
                reportInFlight.set(false);
                if (overrideUrl == null) {
                    reportCount.incrementAndGet();
                    scheduleNextReport();
                }
            }

            if (success) {
                consecutiveFailures = 0;
                // ★ [核心优化] 2026-11-04 将成功时间写入 SharedPreferences，持久化状态，防进程被杀看门狗失效
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                        .edit().putLong(PREF_LAST_SUCCESS_REPORT_TIME, System.currentTimeMillis()).apply();
                fetchRemoteConfig();
            } else {
                if (overrideUrl == null) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 1) { triggerRescueListener(); fetchRemoteConfig(); }
                }
            }
        });
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
        } catch (Exception e) { return typeName; }
    }

    private String getWifiBroadcastAddress() {
        String ip = DeviceInfoUtils.getLocalIpAddress();
        if (ip == null || ip.startsWith("0.") || ip.startsWith("127.")) return "255.255.255.255";
        String[] parts = ip.split("\\.");
        if (parts.length == 4) return parts[0] + "." + parts[1] + "." + parts[2] + ".255";
        return "255.255.255.255";
    }

    private void bindSocketToWifi(java.net.DatagramSocket socket) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    n.bindSocket(socket);
                    return;
                }
            }
        } catch (Exception e) {
            FileLogger.log(this, "UDP", "⚠️ bindSocketToWifi 失败（将使用系统默认出口）: " + e.getMessage());
        }
    }

    private void queuePendingReport(JSONObject json) {
        // ★ [修复] 2026-11-04 并发保护：写入文件必须与 flushPendingReports 互斥
        synchronized (FILE_LOCK) {
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) return;
                java.io.File queueFile = new java.io.File(dir, PENDING_REPORTS_FILE);
                if (queueFile.exists() && queueFile.length() > PENDING_QUEUE_MAX_BYTES) {
                    FileLogger.log(this, "REPORT", "⚠️ 离线队列已满，裁剪保留最近 100 条");
                    truncateQueueFile(queueFile, 100);
                }
                java.io.FileWriter fw = new java.io.FileWriter(queueFile, true);
                fw.write(json.toString() + "\n");
                fw.close();
                FileLogger.log(this, "REPORT", "📥 离线排队一条上报（队列 " + (queueFile.length() / 1024) + "KB）");
            } catch (Exception e) {
                FileLogger.log(this, "REPORT", "❌ 离线排队失败: " + e.getMessage());
            }
        }
    }

    private void truncateQueueFile(java.io.File queueFile, int keepLines) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(queueFile));
            java.util.List<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();
            int start = Math.max(0, lines.size() - keepLines);
            java.io.FileWriter fw = new java.io.FileWriter(queueFile, false);
            for (int i = start; i < lines.size(); i++) fw.write(lines.get(i) + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    private void flushPendingReports() {
        new Thread(() -> {
            // ★ [修复] 2026-11-04 整个读取-发送-重写流程统一使用文件锁保护，
            //   避免 queuePendingReport 并发写入导致文件内容错乱或截断。
            //   锁粒度可以接受：文件通常 <1MB，网络请求在 reportExecutor 之外，
            //   即使持锁 15 秒（超时最长），也只是让零星的新追加写入稍等，不影响 UI。
            synchronized (FILE_LOCK) {
                java.io.BufferedReader br = null;
                try {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir == null) return;
                    java.io.File queueFile = new java.io.File(dir, PENDING_REPORTS_FILE);
                    if (!queueFile.exists() || queueFile.length() == 0) return;

                    java.util.List<String> lines = new java.util.ArrayList<>();
                    br = new java.io.BufferedReader(new java.io.FileReader(queueFile));
                    String line;
                    while ((line = br.readLine()) != null) lines.add(line);
                    br.close();
                    br = null;

                    if (lines.isEmpty()) {
                        queueFile.delete();
                        return;
                    }

                    String serverUrl = getCurrentServerUrl();
                    int sent = 0;
                    for (String l : lines) {
                        try {
                            JSONObject json = new JSONObject(l);
                            if (postReportDirectly(json, serverUrl)) {
                                sent++;
                            } else {
                                break;
                            }
                        } catch (Exception parseEx) {
                            sent++;
                        }
                    }

                    if (sent >= lines.size()) {
                        queueFile.delete();
                    } else if (sent > 0) {
                        java.io.FileWriter fw = new java.io.FileWriter(queueFile, false);
                        for (int i = sent; i < lines.size(); i++) {
                            fw.write(lines.get(i) + "\n");
                        }
                        fw.close();
                    }

                    int remain = lines.size() - sent;
                    if (sent > 0 || remain > 0) {
                        FileLogger.log(this, "REPORT", "📤 离线队列补发: 成功 " + sent + " 条，剩余 " + remain + " 条");
                    }
                } catch (Exception e) {
                    FileLogger.log(this, "REPORT", "⚠️ 离线队列补发异常: " + e.getMessage());
                } finally {
                    if (br != null) { try { br.close(); } catch (Exception ignored) {} }
                }
            }
        }).start();
    }

    private boolean postReportDirectly(JSONObject json, String serverUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(serverUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(8000);   // [P0-3] 15000 → 8000，离线队列补发不再一条卡 15 秒
            conn.setReadTimeout(8000);      // [P0-3] 51 条 × 8 秒 = 408 秒，比旧版 51 × 15 = 765 秒省一半
            conn.setDoOutput(true);
            conn.setRequestProperty("Connection", "close");
            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code == 200) { 
                // ★ [核心优化] 2026-11-04 补发成功也更新持久化时间
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                        .edit().putLong(PREF_LAST_SUCCESS_REPORT_TIME, System.currentTimeMillis()).apply();
                return true; 
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    private void uploadLog(final String tempUrl, final boolean clearAfter) {
        new Thread(() -> {
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) {
                    FileLogger.log(this, "UPLOAD_LOG", "❌ getExternalFilesDir 为 null");
                    return;
                }

                java.io.File[] logFiles = dir.listFiles((d, name) ->
                        name.equals("device_log.txt") || (name.startsWith("device_log_") && name.endsWith(".txt")));
                if (logFiles == null || logFiles.length == 0) {
                    FileLogger.log(this, "UPLOAD_LOG", "⚠️ 没有找到任何日志文件");
                    return;
                }
                java.util.Arrays.sort(logFiles, (f1, f2) -> {
                    String n1 = f1.getName(); String n2 = f2.getName();
                    if (n1.equals("device_log.txt")) return 1;
                    if (n2.equals("device_log.txt")) return -1;
                    return n1.compareTo(n2);
                });

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gzos = new GZIPOutputStream(baos);
                long totalOriginalSize = 0;
                for (java.io.File file : logFiles) {
                    if (file.length() == 0) continue;
                    totalOriginalSize += file.length();
                    String header = "\n\n========== File: " + file.getName() + " ==========\n";
                    gzos.write(header.getBytes("UTF-8"));
                    FileInputStream in = null;
                    try {
                        in = new FileInputStream(file);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) gzos.write(buf, 0, n);
                    } finally {
                        if (in != null) try { in.close(); } catch (Exception ignored) {}
                    }
                }
                gzos.close();
                byte[] gzipData = baos.toByteArray();

                String baseUrl = (tempUrl != null && !tempUrl.isEmpty()) ? tempUrl : getCurrentServerUrl();
                FileLogger.log(this, "UPLOAD_LOG", "📤 baseUrl=[" + baseUrl + "] (tempUrl=[" + tempUrl + "])");

                String uploadUrl = buildApiUrl(baseUrl, "upload_log");
                if (uploadUrl == null) {
                    FileLogger.log(this, "UPLOAD_LOG", "❌ baseUrl 非法，无法拼接上传 URL: [" + baseUrl + "]");
                    return;
                }

                String deviceId = getUniqueDeviceId();
                String fullUrl = uploadUrl + (uploadUrl.contains("?") ? "&" : "?")
                        + "token=" + java.net.URLEncoder.encode(AUTH_TOKEN, "UTF-8")
                        + "&device_id=" + java.net.URLEncoder.encode(deviceId, "UTF-8");

                FileLogger.log(this, "UPLOAD_LOG", "📤 开始上传 " + logFiles.length + " 个日志文件: "
                        + (totalOriginalSize / 1024) + "KB → 压缩后 " + (gzipData.length / 1024) + "KB → " + uploadUrl);

                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/gzip");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(gzipData.length);
                conn.setRequestProperty("Connection", "close");

                OutputStream os = conn.getOutputStream();
                os.write(gzipData);
                os.flush();
                os.close();

                long start = System.currentTimeMillis();
                int code = conn.getResponseCode();
                long cost = System.currentTimeMillis() - start;

                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    FileLogger.log(this, "UPLOAD_LOG", "✅ 上传成功 (耗时 " + cost + "ms): " + sb.toString());
                    if (clearAfter) {
                        int deleted = 0;
                        for (java.io.File file : logFiles) if (file.delete()) deleted++;
                        FileLogger.log(this, "UPLOAD_LOG", "🧹 本地日志已清空 (" + deleted + "/" + logFiles.length + ")");
                    }
                } else {
                    FileLogger.log(this, "UPLOAD_LOG", "⚠️ 上传失败: HTTP " + code + " (耗时 " + cost + "ms)");
                }
                conn.disconnect();
            } catch (Exception e) {
                FileLogger.log(this, "UPLOAD_LOG", "❌ 上传异常: " + e.getMessage());
            }
        }).start();
    }

    private void fetchRemoteConfig() {
        String configUrl = getRemoteConfigUrl();
        if (configUrl.contains("your-config-url.com")) return;
        long now = System.currentTimeMillis();
        if (now - lastConfigFetchTime < 10 * 60 * 1000L) return;
        lastConfigFetchTime = now;
        new Thread(() -> {
            try {
                URL url = new URL(configUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Connection", "close");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    String fetchedText = sb.toString().trim();
                    if (fetchedText.startsWith("MSG:")) showFloatingMessage(fetchedText.substring(4).trim());
                    else if (fetchedText.startsWith("http") && !fetchedText.equals(getCurrentServerUrl())) {
                        saveNewServerUrl(fetchedText); consecutiveFailures = 0; sendReport(true, true);
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private Location getBetterLocation(Location l1, Location l2) {
        if (l1 == null) return l2; if (l2 == null) return l1;
        long timeDelta = l2.getTime() - l1.getTime();
        boolean isSignificantlyNewer = timeDelta > 120000;
        boolean isSignificantlyOlder = timeDelta < -120000;
        boolean isNewer = timeDelta > 0;
        if (isSignificantlyNewer) return l2; else if (isSignificantlyOlder) return l1;
        int accuracyDelta = (int) (l2.getAccuracy() - l1.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;
        boolean isFromSameProvider = l2.getProvider() != null && l2.getProvider().equals(l1.getProvider());
        if (isMoreAccurate) return l2;
        else if (isNewer && !isLessAccurate) return l2;
        else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return l2;
        return l1;
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.contains("://")) return false;
        return url.length() >= 10;
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
        } catch (Exception e) { return null; }
    }

    private void triggerRescueListener() {
        if (serviceDestroyed) return;
        boolean alreadyListening;
        synchronized (rescueLock) {
            alreadyListening = isListeningForRescue;
        }
        if (alreadyListening) {
            if (!rescueRetryScheduled) {
                rescueRetryScheduled = true;
                handler.postDelayed(() -> {
                    rescueRetryScheduled = false;
                    boolean still;
                    synchronized (rescueLock) {
                        still = isListeningForRescue;
                    }
                    if (!still && !serviceDestroyed) startRescueListener();
                }, 8000);
            }
            return;
        }
        startRescueListener();
    }

    private void sendLocalUdpMessage(final String message) {
        new Thread(() -> {
            java.net.DatagramSocket socket = null;
            try {
                socket = new java.net.DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(0));    // 先 bind 到随机端口，所有接口
                bindSocketToWifi(socket);                  // 再尝试限定走 WiFi（失败无妨）

                String myId = getUniqueDeviceId();
                // v2 新前缀
                String chatPayload   = FIND_ME_CHAT_MSG + ":" + RESCUE_SECRET + ":" + myId + ":" + message;
                // [COMPAT-20261105-v1] 旧 4 段前缀双发，v1 全部下线后删除本行 + 下方 legacyBuf 发送
                String legacyPayload = FIND_ME_MSG_LEGACY + ":" + RESCUE_SECRET + ":" + myId + ":" + message;
                // [/COMPAT-20261105-v1]
                byte[] chatBuf   = chatPayload.getBytes("UTF-8");
                byte[] legacyBuf = legacyPayload.getBytes("UTF-8");

                String subnetBcast = getWifiBroadcastAddress();
                String[] targets = subnetBcast.equals("255.255.255.255")
                        ? new String[]{"255.255.255.255"}
                        : new String[]{subnetBcast, "255.255.255.255"};

                for (String target : targets) {
                    // 先发 v2 新前缀
                    try {
                        java.net.DatagramPacket p = new java.net.DatagramPacket(
                                chatBuf, chatBuf.length, java.net.InetAddress.getByName(target), 8888);
                        socket.send(p);
                        FileLogger.log(TrackingService.this, "UDP",
                                "📡 CHAT 已发至 " + target + " | len=" + chatBuf.length
                                        + " | ip=" + DeviceInfoUtils.getLocalIpAddress());
                    } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "UDP",
                                "⚠️ CHAT 发往 " + target + " 失败: " + e.getMessage());
                    }
                    // [COMPAT-20261105-v1] 旧前缀兜底（旧 APK 只认这个），v1 全部下线后删除本段
                    try {
                        java.net.DatagramPacket p = new java.net.DatagramPacket(
                                legacyBuf, legacyBuf.length, java.net.InetAddress.getByName(target), 8888);
                        socket.send(p);
                    } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "UDP",
                                "⚠️ LEGACY 发往 " + target + " 失败: " + e.getMessage());
                    }
                    // [/COMPAT-20261105-v1]
                }
            } catch (Exception e) {
                Log.e("TrackingService", "UDP 发送失败: " + e.toString());
                FileLogger.log(TrackingService.this, "UDP", "❌ 发送失败: " + e.getMessage());
            } finally {
                if (socket != null && !socket.isClosed()) try { socket.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    // ==================== P0-1/P0-2 辅助方法 ====================

    /** recentExecutions 惰性清理阈值（防止 P0-2 改 Key 后内存无界增长） */
    private static final int RECENT_EXECUTIONS_MAX = 200;

    /**
     * P0-2 统一去重判断：60 秒内同 key 视为重复。
     * - 命中且未过期：返回 true
     * - 命中且已过期：删除该 key，返回 false
     * - 未命中：若表超过阈值，清理全部过期项，返回 false
     */
    private boolean isRecentlyExecuted(String dedupKey) {
        long now = System.currentTimeMillis();
        Long last = recentExecutions.get(dedupKey);
        if (last != null) {
            if (now - last < CMD_TIME_WINDOW_MS) return true;
            recentExecutions.remove(dedupKey);
        }
        // 惰性清理：超过阈值时批量清掉所有过期的 key
        if (recentExecutions.size() > RECENT_EXECUTIONS_MAX) {
            recentExecutions.entrySet().removeIf(e -> now - e.getValue() > CMD_TIME_WINDOW_MS);
        }
        return false;
    }

    /**
     * P0-2 统一 dedupKey 生成：HTTP action 与 UDP 前缀在此汇合。
     * - custom_msg / FLOAT / MSG-3段 → "custom_msg|" + content
     * - chat_msg / CHAT / MSG-4段   → "chat_msg|" + content
     * - 其余指令 → cmdType 本身（无 payload 区分需求）
     */
    private String buildDedupKey(String cmdType, String payload) {
        if ("custom_msg".equals(cmdType)) {
            return "custom_msg|" + (payload == null ? "" : payload);
        }
        if ("chat_msg".equals(cmdType)) {
            return "chat_msg|" + (payload == null ? "" : payload);
        }
        return cmdType;
    }

    /**
     * 收聊天的公共逻辑（新 CHAT 前缀 / 旧 MSG-4段 兼容块共用）。
     * 原 UDP 监听里的聊天处理逻辑抽此方法，避免两处重复。
     */
    private void handleIncomingChat(String senderId, String msgContent) {
        // 自己发的忽略
        if (senderId.equals(getUniqueDeviceId())) return;

        // 3 秒内同内容去重（子网+全局双发会收到 2 个包）
        String dedupKey = senderId + ":" + msgContent;
        long now = System.currentTimeMillis();
        if (dedupKey.equals(lastReceivedMsg) && (now - lastReceivedMsgTime < 3000)) return;
        lastReceivedMsg = dedupKey;
        lastReceivedMsgTime = now;

        // 短名称：去掉 device_id 末尾的 _xxxx 后缀
        String shortName = senderId;
        if (shortName.contains("_")) shortName = shortName.substring(0, shortName.lastIndexOf("_"));

        saveChatToHistory("[" + shortName + "] " + msgContent);
        FileLogger.log(this, "CHAT", "收到来自 " + shortName + " 的消息: " + msgContent);
        if (ChatActivity.isForeground) {
            sendBroadcast(new Intent("UPDATE_CHAT_UI"));
        }
    }

    private void startRescueListener() {
        synchronized (rescueLock) {
            if (isListeningForRescue) {
                FileLogger.log(this, "UDP", "⚠️ 监听已在运行，跳过重复启动");
                return;
            }
            isListeningForRescue = true;
        }

        if (multicastLock != null && !multicastLock.isHeld()) {
            try {
                multicastLock.acquire();
                FileLogger.log(this, "UDP", "🔓 MulticastLock 已获取");
            } catch (Exception e) {
                FileLogger.log(this, "UDP", "⚠️ MulticastLock 获取失败: " + e.getMessage());
            }
        }

        new Thread(() -> {
            DatagramSocket rescueSocket = null;
            try {
                rescueSocket = new DatagramSocket(null);
                rescueSocket.setReuseAddress(true);
                rescueSocket.setBroadcast(true);
                // [3.5 修复] 接收 socket 不做 bindSocketToWifi。
                // ZTE 上 bindSocket 返回 EPERM 会把 socket 污染成"已尝试绑定但未成功"状态，
                // 后续 bind(8888) 返回成功但不监听 WiFi 接口，PC 单播包全被内核丢弃。
                rescueSocket.bind(new InetSocketAddress("0.0.0.0", 8888));
                rescueSocket.setSoTimeout(3000);
                FileLogger.log(this, "UDP", "📡 接收 socket 实际绑定: " + rescueSocket.getLocalSocketAddress());

                rescueSocketRef = rescueSocket;
                udpBindFailCount = 0;
                FileLogger.log(this, "UDP", "✅ 监听已绑定 8888");

                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                java.util.Map<String, Long> udpCmdDedup = new java.util.HashMap<>();

                while (!serviceDestroyed) {
                    try {
                        packet.setLength(buffer.length);
                        rescueSocket.receive(packet);
                    } catch (java.net.SocketTimeoutException te) {
                        continue;
                    }
                    String message = new String(packet.getData(), 0, packet.getLength());

                    if (message.startsWith("FIND_ME_ALARM:")
                            || message.startsWith("FIND_ME_TEST_LOW_BATTERY:")
                            || message.startsWith("FIND_ME_MSG:")) {
                        String dedupKey = message.length() > 64 ? message.substring(0, 64) : message;
                        long nowMs = System.currentTimeMillis();
                        Long last = udpCmdDedup.get(dedupKey);
                        if (last != null && nowMs - last < 3000) {
                            continue;
                        }
                        udpCmdDedup.put(dedupKey, nowMs);
                        if (udpCmdDedup.size() > 50) {
                            long cutoff = nowMs - 30000;
                            udpCmdDedup.entrySet().removeIf(e -> e.getValue() < cutoff);
                        }
                    }

                    if (message.startsWith("FIND_ME_SERVER:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            saveNewServerUrl(parts[2].trim()); consecutiveFailures = 0; sendReport(true, true);
                            recentExecutions.put("change_server", System.currentTimeMillis());
                            break;
                        }
                    } else if (message.startsWith("FIND_ME_CONFIG:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) handleUpdateConfigUrl(parts[2].trim());
                    } else if (message.startsWith("FIND_ME_REPORT:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            sendReport(true, true);
                            recentExecutions.put("force_report", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_REPORT_TEMP:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            String tempUrl = parts[2].trim();
                            if (isValidUrl(tempUrl)) {
                                sendReport(true, true, tempUrl);
                                recentExecutions.put("force_report", System.currentTimeMillis());
                            } else {
                                FileLogger.log(this, "REPORT", "⚠️ 忽略非法 FIND_ME_REPORT_TEMP URL: [" + tempUrl + "]");
                            }
                        }
                    } else if (message.startsWith("FIND_ME_ALARM:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            playAlarm();
                            recentExecutions.put("play_alarm", System.currentTimeMillis());
                        }
                    // ===== [P0-1] 悬浮文字：v2 新前缀 =====
                    // 格式：FIND_ME_FLOAT:secret:content（content 可含冒号，split limit=3 完整保留）
                    else if (message.startsWith(FIND_ME_FLOAT_MSG + ":")) {
                        String[] floatParts = message.split(":", 3);
                        if (floatParts.length == 3 && RESCUE_SECRET.equals(floatParts[1])) {
                            String content = floatParts[2].trim();
                            String dk = buildDedupKey("custom_msg", content);
                            if (!isRecentlyExecuted(dk)) {
                                showFloatingMessage(content);
                                recentExecutions.put(dk, System.currentTimeMillis());
                            }
                        }
                    }
                    // ===== [P0-1] 聊天：v2 新前缀 =====
                    // 格式：FIND_ME_CHAT:secret:sender:content
                    else if (message.startsWith(FIND_ME_CHAT_MSG + ":")) {
                        String[] chatParts = message.split(":", 4);
                        if (chatParts.length == 4 && RESCUE_SECRET.equals(chatParts[1])) {
                            String senderId = chatParts[2].trim();
                            String content = chatParts[3].trim();
                            String dk = buildDedupKey("chat_msg", content);
                            if (!isRecentlyExecuted(dk)) {
                                handleIncomingChat(senderId, content);
                                recentExecutions.put(dk, System.currentTimeMillis());
                            }
                        }
                    }
                    // [COMPAT-20261105-v1] 旧 FIND_ME_MSG 前缀兼容：3 段=悬浮，4 段=聊天
                    // 旧 APK 全部下线后整块删除，同时删除常量区 FIND_ME_MSG_LEGACY
                    else if (message.startsWith(FIND_ME_MSG_LEGACY + ":")) {
                        String[] legacyParts = message.split(":", 4);
                        if (legacyParts.length == 3 && RESCUE_SECRET.equals(legacyParts[1])) {
                            // 3 段=悬浮，映射到与 FLOAT 相同的 dedupKey（避免 PC 双发时执行两次）
                            String content = legacyParts[2].trim();
                            String dk = buildDedupKey("custom_msg", content);
                            if (!isRecentlyExecuted(dk)) {
                                showFloatingMessage(content);
                                recentExecutions.put(dk, System.currentTimeMillis());
                            }
                        } else if (legacyParts.length == 4 && RESCUE_SECRET.equals(legacyParts[1])) {
                            // 4 段=聊天，映射到与 CHAT 相同的 dedupKey
                            String senderId = legacyParts[2].trim();
                            String content = legacyParts[3].trim();
                            String dk = buildDedupKey("chat_msg", content);
                            if (!isRecentlyExecuted(dk)) {
                                handleIncomingChat(senderId, content);
                                recentExecutions.put(dk, System.currentTimeMillis());
                            }
                        }
                    }
                    // [/COMPAT-20261105-v1]
                    
                    } else if (message.startsWith("FIND_ME_CLEAR_HISTORY:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            clearMessageHistory();
                            recentExecutions.put("clear_history", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_CLEAR_CHAT:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putString("chat_history", "").apply();
                            if (ChatActivity.isForeground) sendBroadcast(new Intent("UPDATE_CHAT_UI"));
                            FileLogger.log(TrackingService.this, "CHAT", "🧹 收到云端指令，已清空本地聊天记录");
                            recentExecutions.put("clear_chat", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_SET_SMS_NUM:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            String newNum = parts[2].trim();
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putString("emergency_sms_number", newNum).apply();
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "紧急号码已更新: " + newNum, android.widget.Toast.LENGTH_LONG).show());
                            recentExecutions.put("set_sms_num", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_CLOUD_LOG:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            boolean enable = "1".equals(parts[2].trim());
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putBoolean("enable_cloud_log", enable).apply();
                            String stateMsg = enable ? "☁️ 云端日志已开启" : "☁️ 云端日志已关闭";
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, stateMsg, android.widget.Toast.LENGTH_SHORT).show());
                            recentExecutions.put("set_cloud_log", System.currentTimeMillis());
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
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            setChatLocked(false);
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "🔓 聊天功能已解锁", android.widget.Toast.LENGTH_SHORT).show());
                            recentExecutions.put("unlock_chat", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_CHAT_LOCK:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            setChatLocked(true);
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "🔒 聊天功能已锁定", android.widget.Toast.LENGTH_SHORT).show());
                            recentExecutions.put("lock_chat", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_ELDER_MODE:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length >= 2 && RESCUE_SECRET.equals(parts[1])) {
                            boolean target;
                            if (parts.length >= 3) {
                                String v = parts[2].trim();
                                target = "1".equals(v) || "true".equalsIgnoreCase(v);
                            } else target = !isElderMode();
                            setElderMode(target);
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this,
                                    target ? "👴 老人模式已开启" : "🕶️ 无痕模式已开启",
                                    android.widget.Toast.LENGTH_SHORT).show());
                        }
                    } else if (message.startsWith("FIND_ME_UPLOAD_LOG:")) {
                        String rest = message.substring("FIND_ME_UPLOAD_LOG:".length());
                        int secretEnd = rest.indexOf(":");
                        String secret = secretEnd < 0 ? rest : rest.substring(0, secretEnd);
                        String payload = secretEnd < 0 ? "" : rest.substring(secretEnd + 1).trim();
                        if (RESCUE_SECRET.equals(secret)) {
                            boolean clearAfter = payload.endsWith(":clear") || "clear".equals(payload);
                            String urlPart = payload;
                            if (urlPart.endsWith(":clear")) urlPart = urlPart.substring(0, urlPart.length() - 6);
                            if ("clear".equals(urlPart)) urlPart = "";
                            String tempUrl = null;
                            if (!urlPart.isEmpty()) {
                                if (isValidUrl(urlPart)) tempUrl = urlPart;
                                else FileLogger.log(this, "UPLOAD_LOG", "⚠️ 忽略非法 tempUrl: [" + urlPart + "]（不含 :// 或过短），使用主服务器");
                            }
                            uploadLog(tempUrl, clearAfter);
                            recentExecutions.put("upload_log", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_LOCATE_ONCE:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            requestSingleLocationAndReport();
                            recentExecutions.put("locate_once", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_SCAN_WIFI:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            new Thread(() -> {
                                List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(this);
                                FileLogger.log(this, "WIFI", "📡 UDP 触发扫描: " + nearby.size() + " 个热点");
                            }).start();
                            recentExecutions.put("scan_wifi", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_RESTART_UDP:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            recentExecutions.put("restart_udp", System.currentTimeMillis());
                            break;
                        }
                    } else if (message.startsWith("FIND_ME_TEST_LOW_BATTERY:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            FileLogger.log(this, "BATTERY", "🧪 UDP 触发模拟低电量告警");
                            sendReport(true, true);
                            sendLowBatterySms(15);
                            recentExecutions.put("test_low_battery", System.currentTimeMillis());
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
                synchronized (rescueLock) {
                    isListeningForRescue = false;
                }

                if (multicastLock != null && multicastLock.isHeld()) {
                    try { multicastLock.release(); } catch (Exception ignored) {}
                }

                // ★ 修改：指数退避无限重试 2026-11-04 14:20
                if (!serviceDestroyed) {
                    long retryDelayMs = (long) Math.min(300000, 5000 * Math.pow(3, Math.min(udpBindFailCount, 4)));
                    FileLogger.log(this, "UDP", "监听断开，将在 " + (retryDelayMs / 1000) + " 秒后尝试第 " + (udpBindFailCount + 1) + " 次重连...");
                    
                    handler.postDelayed(() -> {
                        boolean needRestart;
                        synchronized (rescueLock) {
                            needRestart = !isListeningForRescue && !serviceDestroyed;
                        }
                        if (needRestart) {
                            triggerRescueListener();
                        }
                    }, retryDelayMs);
                }
            }
        }).start();
    }

    private void parseResponse(JSONObject res) {
        JSONObject cfg = res.optJSONObject("config");
        if (cfg != null) {
            long newInterval = cfg.optLong("report_interval_seconds", 1800) * 1000L;
            String newAccuracy = cfg.optString("accuracy_mode", "coarse");
            boolean newEnableWifiLock = cfg.optBoolean("enable_wifi_lock", true);
            boolean newEnableMotionTrigger = cfg.optBoolean("enable_motion_trigger", true);
            if (newEnableWifiLock != enableWifiLock || newEnableMotionTrigger != enableMotionTrigger) {
                enableWifiLock = newEnableWifiLock;
                enableMotionTrigger = newEnableMotionTrigger;
                toggleMotionSensor(enableMotionTrigger);
            }
            if (newInterval != reportIntervalMs || !newAccuracy.equals(accuracyMode)) {
                reportIntervalMs = newInterval;
                accuracyMode = newAccuracy;
                handler.post(() -> { startLocationUpdates(); if (reportCount.get() >= 3) scheduleNextReport(); });
            }
            String nextServerUrl = cfg.optString("next_server_url", "");
            if (!nextServerUrl.isEmpty() && !nextServerUrl.equals(getCurrentServerUrl())) { saveNewServerUrl(nextServerUrl); sendReport(true, true); }
            String nextConfigUrl = cfg.optString("next_config_url", "");
            if (!nextConfigUrl.isEmpty() && !nextConfigUrl.equals(getRemoteConfigUrl())) handleUpdateConfigUrl(nextConfigUrl);
            handleIconVisibility(cfg.optBoolean("hide_icon", true));
        }

        try {
            JSONArray pending = res.optJSONArray("pending_commands");
            if (pending != null && pending.length() > 0) {
                java.util.Set<String> executedIds = loadExecutedCmdIds();
                long now = System.currentTimeMillis();
                int executedCount = 0;

                for (int i = 0; i < pending.length(); i++) {
                    try {
                        JSONObject cmd = pending.getJSONObject(i);
                        String cmdId = cmd.optString("id", "");
                        String cmdType = cmd.optString("type", "");
                        String cmdPayload = cmd.optString("payload", "");

                        if (cmdId.isEmpty() || cmdType.isEmpty()) continue;

                        if (executedIds.contains(cmdId)) continue;

                        // [P0-2] 用 buildDedupKey 生成 Key，同类型不同 payload 不再互相误杀
                        String dk = buildDedupKey(cmdType, cmdPayload);
                        if (isRecentlyExecuted(dk)) {
                            executedIds.add(cmdId);
                            FileLogger.log(TrackingService.this, "HTTP_CMD",
                                    "⏭ 跳过 " + cmdType + "(" + cmdId + ")（60秒内已通过UDP执行, key=" + dk + "）");
                            continue;
                        }

                        dispatchHttpCommand(cmdType, cmdPayload);
                        recentExecutions.put(dk, now);
                        executedIds.add(cmdId);
                        executedCount++;
                        FileLogger.log(TrackingService.this, "HTTP_CMD",
                                "✅ 执行 HTTP 指令: " + cmdType + "(" + cmdId + ")");
                    } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "HTTP_CMD", "⚠️ 解析指令失败: " + e.getMessage());
                    }
                }

                saveExecutedCmdIds(executedIds);

                if (executedCount > 0) {
                    FileLogger.log(TrackingService.this, "HTTP_CMD",
                            "📊 本轮处理 " + pending.length() + " 条指令，实际执行 " + executedCount + " 条");
                }
            }

            int nextPollSeconds = res.optInt("next_poll_seconds", 0);
            if (nextPollSeconds > 0) {
                scheduleFastPoll(nextPollSeconds);
            } else {
                cancelFastPoll();
            }
        } catch (Exception e) {
            FileLogger.log(TrackingService.this, "HTTP_CMD", "❌ 处理 pending_commands 异常: " + e.getMessage());
        }
    }

    private java.util.Set<String> loadExecutedCmdIds() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        try {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            String json = sp.getString(PREF_EXECUTED_CMD_IDS, "");
            if (json != null && !json.isEmpty()) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i, "");
                    if (!id.isEmpty()) result.add(id);
                }
            }
        } catch (Exception e) {
            FileLogger.log(this, "HTTP_CMD", "⚠️ 加载 executedCmdIds 失败: " + e.getMessage());
        }
        return result;
    }

    private void saveExecutedCmdIds(java.util.Set<String> ids) {
        try {
            java.util.LinkedHashSet<String> trimmed = new java.util.LinkedHashSet<>();
            int skip = Math.max(0, ids.size() - EXECUTED_CMD_IDS_MAX);
            int i = 0;
            for (String id : ids) {
                if (i++ >= skip) trimmed.add(id);
            }
            JSONArray arr = new JSONArray();
            for (String id : trimmed) arr.put(id);
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            sp.edit().putString(PREF_EXECUTED_CMD_IDS, arr.toString()).apply();
        } catch (Exception e) {
            FileLogger.log(this, "HTTP_CMD", "⚠️ 保存 executedCmdIds 失败: " + e.getMessage());
        }
    }

    private void dispatchHttpCommand(String type, String payload) {
        if (type == null || type.isEmpty()) return;
        try {
            switch (type) {
                case "force_report":
                    sendReport(true, true);
                    break;
                case "play_alarm":
                    playAlarm();
                    break;
                case "custom_msg":
                    if (payload != null && !payload.isEmpty()) showFloatingMessage(payload);
                    break;
                case "chat_msg":
                    if (payload != null && !payload.isEmpty()) {
                        String shortName = "PC";
                        saveChatToHistory("[" + shortName + "] " + payload);
                        if (ChatActivity.isForeground) {
                            sendBroadcast(new Intent("UPDATE_CHAT_UI"));
                        }
                        FileLogger.log(this, "CHAT", "收到 HTTP 聊天: " + payload);
                    }
                    break;
                case "change_server":
                    if (isValidUrl(payload)) {
                        saveNewServerUrl(payload);
                        consecutiveFailures = 0;
                        sendReport(true, true);
                    }
                    break;
                case "clear_history":
                    clearMessageHistory();
                    break;
                case "clear_chat":
                    getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                            .edit().putString("chat_history", "").apply();
                    if (ChatActivity.isForeground) sendBroadcast(new Intent("UPDATE_CHAT_UI"));
                    break;
                case "lock_chat":
                    setChatLocked(true);
                    break;
                case "unlock_chat":
                    setChatLocked(false);
                    break;
                case "set_sms_num":
                    if (payload != null && !payload.isEmpty()) {
                        getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                                .edit().putString("emergency_sms_number", payload).apply();
                    }
                    break;
                case "set_cloud_log":
                    getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                            .edit().putBoolean("enable_cloud_log", "1".equals(payload)).apply();
                    break;
                case "upload_log":
                    uploadLog(null, false);
                    break;
                case "locate_once":
                    requestSingleLocationAndReport();
                    break;
                case "scan_wifi":
                    new Thread(() -> {
                        try {
                            List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this);
                            FileLogger.log(this, "WIFI", "📡 HTTP 触发扫描: " + nearby.size() + " 个热点");
                        } catch (Exception e) {
                            FileLogger.log(this, "WIFI", "❌ HTTP 触发扫描失败: " + e.getMessage());
                        }
                    }).start();
                    break;
                case "restart_udp":
                    if (rescueSocketRef != null) {
                        try { rescueSocketRef.close(); } catch (Exception ignored) {}
                    }
                    break;
                case "test_low_battery":
                    sendReport(true, true);
                    sendLowBatterySms(15);
                    break;
                default:
                    FileLogger.log(this, "HTTP_CMD", "⚠️ 未知 HTTP 指令: " + type);
                    break;
            }
        } catch (Exception e) {
            FileLogger.log(this, "HTTP_CMD", "❌ 执行 " + type + " 异常: " + e.getMessage());
        }
    }

    private void scheduleFastPoll(int seconds) {
        if (seconds <= 0) return;
        if (seconds > 60) seconds = 60;
        synchronized (fastPollLock) {
            if (isFastPolling) return;
            isFastPolling = true;
            fastPollConsecutiveCount = 0;
        }
        FileLogger.log(this, "FAST_POLL", "▶ 启动快速轮询（间隔 " + seconds + " 秒）");
        handler.postDelayed(fastPollRunnable, seconds * 1000L);
    }

    private void cancelFastPoll() {
        boolean wasRunning;
        synchronized (fastPollLock) {
            wasRunning = isFastPolling;
            if (!isFastPolling) return;
            isFastPolling = false;
            fastPollConsecutiveCount = 0;
        }
        handler.removeCallbacks(fastPollRunnable);
        if (wasRunning) FileLogger.log(this, "FAST_POLL", "⏹ 队列已空，停止快速轮询");
    }

    private final Runnable fastPollRunnable = this::doFastPoll;

    private void doFastPoll() {
        int currentCount;
        long nextInterval;
        synchronized (fastPollLock) {
            if (!isFastPolling) return;
            fastPollConsecutiveCount++;
            currentCount = fastPollConsecutiveCount;

            if (currentCount > FAST_POLL_MAX_CONSECUTIVE) {
                isFastPolling = false;
                fastPollConsecutiveCount = 0;
                FileLogger.log(this, "FAST_POLL",
                        "⚠️ 连续快速轮询达上限 " + FAST_POLL_MAX_CONSECUTIVE + " 次，强制停止");
                return;
            }

            int[] battery = DeviceInfoUtils.getBatteryInfo(this);
            int level = battery[0];
            boolean lowBattery = (level >= 0 && level < FAST_POLL_BATTERY_THRESHOLD);
            nextInterval = lowBattery ? 5 * 60 * 1000L : 30 * 1000L;

            FileLogger.log(this, "FAST_POLL",
                    "⚡ 第 " + currentCount + "/" + FAST_POLL_MAX_CONSECUTIVE + " 次"
                            + (lowBattery ? "（🔋 低电量 " + level + "%，降级 5 分钟）" : ""));
        }

        sendReport(false, true);

        synchronized (fastPollLock) {
            if (isFastPolling && fastPollConsecutiveCount < FAST_POLL_MAX_CONSECUTIVE) {
                handler.postDelayed(fastPollRunnable, nextInterval);
            }
        }
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
                FileLogger.log(this, "SERVICE", "⚠️ onTrimMemory level=" + level + ", importance=" + importanceStr(info.importance));
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

        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }

        cancelFastPoll();

        if (reportExecutor != null) reportExecutor.shutdownNow();
        if (rescueSocketRef != null) {
            try { rescueSocketRef.close(); } catch (Exception e) { FileLogger.log(this, "SERVICE", "⚠️ close rescueSocket 失败: " + e.getMessage()); }
            rescueSocketRef = null;
        }
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception e) { FileLogger.log(this, "SERVICE", "⚠️ removeUpdates 失败: " + e.getMessage()); }
        }
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) for (android.view.View v : new ArrayList<>(activeBubbles)) {
                try { wm.removeView(v); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        activeBubbles.clear();

        if (batteryMonitor != null) { try { unregisterReceiver(batteryMonitor); } catch (Exception ignored) {} }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorManager != null && motionListener != null) sensorManager.cancelTriggerSensor(motionListener, motionSensor);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}

        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmService.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, restartServicePendingIntent);
                else alarmService.setExact(AlarmManager.RTC_WAKEUP, triggerAt, restartServicePendingIntent);
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
