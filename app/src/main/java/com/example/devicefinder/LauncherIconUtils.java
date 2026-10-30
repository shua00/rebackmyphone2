package com.example.devicefinder;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * 桌面图标显示/隐藏统一入口。
 *
 * 两处调用方：
 *   - MainActivity.hideLauncherIcon()  ：首次引导完成后隐藏
 *   - TrackingService.handleIconVisibility() ：随云端配置 hide_icon 变化
 *
 * 使用 COMPONENT_ENABLED_STATE_DEFAULT 时按 manifest 声明的 android:enabled 判定。
 */
public final class LauncherIconUtils {

    private static final String TAG = "LauncherIcon";
    private static final String ALIAS_CLASS = "com.example.devicefinder.VisibleAliasActivity";

    private LauncherIconUtils() {}

    /**
     * 设置桌面图标可见性。
     *
     * @param context 任意 Context（内部使用 applicationContext）
     * @param visible true=显示，false=隐藏
     * @return true 表示状态已就位（含"本来就是对的"）；false 表示修改失败
     */
    public static boolean setLauncherIconVisible(Context context, boolean visible) {
        try {
            Context appCtx = context.getApplicationContext();
            PackageManager pm = appCtx.getPackageManager();
            ComponentName alias = new ComponentName(appCtx, ALIAS_CLASS);

            int currentState = pm.getComponentEnabledSetting(alias);

            // DEFAULT 表示"跟随 manifest"；当前 manifest 中 android:enabled="true"，故视为可见
            boolean currentlyVisible;
            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                currentlyVisible = true;
            } else {
                currentlyVisible = (currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            }

            if (currentlyVisible == visible) {
                Log.d(TAG, "alias 已是 " + (visible ? "可见" : "隐藏") + " 状态，跳过");
                return true;
            }

            int targetState = visible
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

            pm.setComponentEnabledSetting(alias, targetState, PackageManager.DONT_KILL_APP);

            Log.i(TAG, "alias 状态变更: " + (currentlyVisible ? "可见" : "隐藏")
                    + " → " + (visible ? "可见" : "隐藏"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "修改 alias 状态失败: " + e.getMessage());
            return false;
        }
    }

    /** 当前图标是否可见（供自检日志使用） */
    public static boolean isLauncherIconVisible(Context context) {
        try {
            Context appCtx = context.getApplicationContext();
            PackageManager pm = appCtx.getPackageManager();
            ComponentName alias = new ComponentName(appCtx, ALIAS_CLASS);
            int state = pm.getComponentEnabledSetting(alias);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return true;
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception e) {
            return true;
        }
    }
}
