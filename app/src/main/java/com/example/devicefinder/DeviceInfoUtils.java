package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

public class DeviceInfoUtils {

    // 全局内存缓存：用来对抗 Android 10+ 后台无法直接读取 Wi-Fi SSID 的限制
    public static String cachedSsid = "UNKNOWN";
    public static String cachedBssid = "UNKNOWN";
    // 缓存最后一次有效 RSSI（dBm），后台拿不到时回退使用
    public static int cachedRssi = 0;

    // 周围 Wi-Fi 扫描最多等待时长
    private static final long SCAN_TIMEOUT_MS = 3000L;
    // 最多上报多少个热点（按信号强度排序后取前 N 个）
    private static final int MAX_NEARBY_WIFI = 15;

    // 获取电量信息 [电量百分比, 是否在充电(1/0)]
    public static int[] getBatteryInfo(Context context) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        // [L1] scale 异常保护，防止除零/负数百分比
        if (scale <= 0) scale = 100;
        int batteryPct = (int) ((level / (float) scale) * 100);

        int status = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
        int isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                          status == BatteryManager.BATTERY_STATUS_FULL) ? 1 : 0;

        return new int[]{batteryPct, isCharging};
    }

    // 获取 Wi-Fi 信息 [SSID, BSSID, RSSI]（带内存缓存降级）
    public static String[] getWifiInfo(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return new String[]{cachedSsid, cachedBssid, String.valueOf(cachedRssi)};

        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info != null ? info.getSSID() : "UNKNOWN";

            if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.equals("UNKNOWN")) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                cachedSsid = ssid;
                cachedBssid = info.getBSSID() != null ? info.getBSSID() : "UNKNOWN";
                cachedRssi = info.getRssi();
            }
        } catch (Exception ignored) {}

        return new String[]{cachedSsid, cachedBssid, String.valueOf(cachedRssi)};
    }

    // 获取内网 IPv4 地址
    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    /**
     * 异步扫描周围 Wi-Fi 热点，最多等待 3 秒。
     * 返回按信号强度降序排列的前 15 个热点，每项为 {"ssid","bssid","rssi"}。
     *
     * Android 13+ 需要 NEARBY_WIFI_DEVICES 权限，否则 startScan 永远返回 false；
     * startScan 失败时降级读 getScanResults() 缓存，尽量拿点数据。
     * 不使用 isScanThrottled()（部分 SDK 中为 @hide，普通 App 编译报错）。
     */
    public static List<JSONObject> scanNearbyWifi(Context context) {
        final List<JSONObject> result = new ArrayList<>();
        final WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return result;

        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};
        final Handler handler = new Handler(Looper.getMainLooper());

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                try {
                    List<ScanResult> scanResults = wifiManager.getScanResults();
                    result.addAll(buildResultFromScanResults(ctx, scanResults, "扫描"));
                } catch (Exception ignored) {
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        };

        try {
            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            // [L0] API 33+ 动态注册必须显式指定 exported 标志
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Exception e) {
            FileLogger.log(context, "WIFI", "❌ 注册扫描广播失败: " + e.getMessage());
            return result;
        }

        boolean started;
        try {
            started = wifiManager.startScan();
        } catch (Exception e) {
            FileLogger.log(context, "WIFI", "❌ startScan 抛异常: " + e.getMessage());
            started = false;
        }

        if (!started) {
            // [L1] startScan 失败，降级读缓存（Android 13+ 缺权限 / 后台节流 / 频率限制都会返回 false）
            FileLogger.log(context, "WIFI", "⚠️ startScan 返回 false，尝试读缓存");
            try {
                List<ScanResult> cached = wifiManager.getScanResults();
                result.addAll(buildResultFromScanResults(context, cached, "缓存降级"));
            } catch (Exception ignored) {}
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
            return result;
        }

        // 等待广播或超时
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Exception ignored) {}
            }
        });

        // [L1] 记录扫描结果数，便于判断「无结果」vs「扫描失败」
        if (result.isEmpty()) {
            FileLogger.log(context, "WIFI", "📡 扫描完成: 0 个热点（可能后台限制或附近无热点）");
        } else {
            FileLogger.log(context, "WIFI", "📡 扫描完成: " + result.size() + " 个热点");
        }
        return result;
    }

    /**
     * 把 List<ScanResult> 转成按信号强度降序的 JSONObject 列表，最多 MAX_NEARBY_WIFI 个。
     * 抽成辅助方法，供「正常扫描」和「缓存降级」复用。
     */
    private static List<JSONObject> buildResultFromScanResults(Context context, List<ScanResult> scanResults, String source) {
        List<JSONObject> out = new ArrayList<>();
        if (scanResults == null || scanResults.isEmpty()) {
            FileLogger.log(context, "WIFI", "📡 " + source + ": 0 个热点");
            return out;
        }
        List<ScanResult> filtered = new ArrayList<>();
        for (ScanResult r : scanResults) {
            if (r.SSID != null && !r.SSID.isEmpty()) filtered.add(r);
        }
        Collections.sort(filtered, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult a, ScanResult b) {
                return b.level - a.level;
            }
        });
        int count = Math.min(filtered.size(), MAX_NEARBY_WIFI);
        for (int i = 0; i < count; i++) {
            ScanResult r = filtered.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("ssid", r.SSID);
                obj.put("bssid", r.BSSID != null ? r.BSSID : "");
                obj.put("rssi", r.level);
                out.add(obj);
            } catch (Exception ignored) {}
        }
        FileLogger.log(context, "WIFI", "📡 " + source + ": " + out.size() + " 个热点");
        return out;
    }
}
