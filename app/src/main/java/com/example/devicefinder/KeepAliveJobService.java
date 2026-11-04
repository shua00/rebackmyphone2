package com.example.devicefinder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class KeepAliveJobService extends JobService {

    private static final int JOB_ID = 10086;

    @Override
    public boolean onStartJob(JobParameters params) {
        FileLogger.log(this, "JOB", "⏰ JobScheduler 唤醒，检查服务状态");
        
        // 尝试拉起 TrackingService (注意：不要用 ACTION_KEEP_ALIVE，避免和 Alarm 逻辑混淆)
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_JOB_WAKE_UP"); 
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            FileLogger.log(this, "JOB", "❌ 拉起服务失败: " + e.getMessage());
        }

        // ★ 修复1：删除了 scheduleJob(this) 重新注册的代码
        // ★ 修复2：不需要调用 jobFinished，直接返回 false 表示任务已完成，系统无需保持唤醒锁。
        return false; 
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 如果被系统强制中断，告诉系统稍后重试
        return true;
    }

    public static void scheduleJob(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) return;

        // ★ 修复3：先检查 Job 是否已经存在，避免重复注册/取消导致死循环
        for (JobInfo job : jobScheduler.getAllPendingJobs()) {
            if (job.getId() == JOB_ID) {
                FileLogger.log(context, "JOB", "ℹ️ JobScheduler 已存在，跳过重复注册");
                return;
            }
        }

        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, new ComponentName(context, KeepAliveJobService.class));
        
        // 核心：每 15 分钟唤醒一次（Android 最低限制）
        builder.setPeriodic(15 * 60 * 1000);
        
        // 必须满足有网络才执行（避免空转耗电）
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        
        // 设备重启后依然有效（如果 ROM 允许）
        builder.setPersisted(true);

        int result = jobScheduler.schedule(builder.build());
        if (result == JobScheduler.RESULT_SUCCESS) {
            FileLogger.log(context, "JOB", "✅ JobScheduler 注册成功 (15分钟周期)");
        } else {
            FileLogger.log(context, "JOB", "❌ JobScheduler 注册失败");
        }
    }
}
