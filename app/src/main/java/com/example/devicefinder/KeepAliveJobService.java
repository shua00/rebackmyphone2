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
        // ★③-1 新增：存活日志。下次出问题，有这条就能判断 JobService 到底活没活。
        //        （如果日志里有这条而 TrackingService 没起，说明 startForegroundService 抛异常；
        //          如果连这条都没有，说明 App 被 force-stop，只能靠用户手动操作）
        FileLogger.log(this, "JOB", "⏰ JobService 收到唤醒（独立进程存活证明）");

        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_JOB_WAKE_UP");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            FileLogger.log(this, "JOB", "✅ 已请求拉起 TrackingService");
        } catch (Exception e) {
            FileLogger.log(this, "JOB", "❌ 拉起服务失败: " + e.getMessage());
        }

        // ★③-2 新增：显式告知系统本任务已同步完成（needReschedule=false）。
        //        规范做法，避免系统 hold wake lock 过久。
        jobFinished(params, false);

        // 同步完成，返回 false 让系统释放 wake lock
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // ★③-3 修改：原返回 true = 系统中断时立即重试（会加剧重入压力）；
        //        本任务无状态（拉起服务后就不管了），被中断不需要重试 → false。
        FileLogger.log(this, "JOB", "⏹ JobService 被系统中断，不重试");
        return false;
    }

    public static void scheduleJob(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) return;

        // 防重复注册（你现有逻辑，保留）
        for (JobInfo job : jobScheduler.getAllPendingJobs()) {
            if (job.getId() == JOB_ID) {
                FileLogger.log(context, "JOB", "ℹ️ JobScheduler 已存在，跳过重复注册");
                return;
            }
        }

        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, KeepAliveJobService.class));
        builder.setPeriodic(15 * 60 * 1000);
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        builder.setPersisted(true);

        int result = jobScheduler.schedule(builder.build());
        if (result == JobScheduler.RESULT_SUCCESS) {
            FileLogger.log(context, "JOB", "✅ JobScheduler 注册成功 (15分钟周期)");
        } else {
            FileLogger.log(context, "JOB", "❌ JobScheduler 注册失败");
        }
    }
}
