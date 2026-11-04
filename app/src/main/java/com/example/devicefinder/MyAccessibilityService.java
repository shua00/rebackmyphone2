package com.example.devicefinder;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍保活服务
 *
 * 设计原则：
 * 1. 纯"保活外壳"：不捕获、不解析、不处理任何屏幕事件内容；
 * 2. 依赖 Android 系统的 AccessibilityManagerService 生命周期托管，
 *    进程被杀后由系统主动重新拉起，比 Job/Alarm 更顽固；
 * 3. 每次连接成功即主动拉起 TrackingService，并注入专属唤醒源标签，
 *    便于在日志中区分"这一次是谁拉起来的"。
 *
 * 2026-11-05 新建
 */
public class MyAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // ★ 纯保活外壳，静默不处理任何屏幕事件，CPU 占用近 0
    }

    @Override
    public void onInterrupt() {
        // 无需处理
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // ★ 核心拉活：无障碍连接成功（开机、或被系统意外杀死复活）时，强行拉起核心追踪服务
        try {
            Intent intent = new Intent(this, TrackingService.class);
            intent.setAction("ACTION_WAKE_BY_ACCESSIBILITY");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            FileLogger.log(this, "ACCESSIBILITY", "♿ 无障碍连接成功，已拉起 TrackingService");
        } catch (Exception e) {
            FileLogger.log(this, "ACCESSIBILITY", "❌ 拉起 TrackingService 失败: " + e.getMessage());
        }
    }
}
