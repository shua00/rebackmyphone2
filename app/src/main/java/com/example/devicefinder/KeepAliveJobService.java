package com.example.devicefinder;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class KeepAliveJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d("KeepAlive", "JobScheduler 唤醒服务...");
        Intent svc = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        jobFinished(params, false);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; 
    }
}
