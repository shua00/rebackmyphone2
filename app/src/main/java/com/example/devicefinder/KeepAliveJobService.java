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
        
        // 尝试拉起 TrackingService
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_KEEP_ALIVE"); // 复用你的保活 Action
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            FileLogger.log(this, "JOB", "❌ 拉起服务失败: " + e.getMessage());
        }

        // 任务执行完毕，重新调度自己（防止被打断后不执行）
        scheduleJob(this);
        jobFinished(params, false); // false 表示不需要系统重试，我们自己接管调度
        return true;
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

        // 取消旧的同 ID 任务，防止重复
        jobScheduler.cancel(JOB_ID);

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
