package com.example.devicefinder;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

public class FileLogger {
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 最大 5MB
    private static final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
    
    // 【新增】内存缓存，最多保留最近 20 条日志，专供云端上报使用
    private static final LinkedList<String> memoryLogs = new LinkedList<>();
    private static final int MAX_MEMORY_LOGS = 20;

    public static void log(Context context, String tag, String msg) {
        String time = sdf.format(new Date());
        String line = time + " | " + tag + " | " + msg;

        // 1. 写入内存缓存
        synchronized (memoryLogs) {
            memoryLogs.add(line);
            if (memoryLogs.size() > MAX_MEMORY_LOGS) {
                memoryLogs.removeFirst();
            }
        }

        // 2. 写入本地文件（异步）
        new Thread(() -> {
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir == null) return;
                File logFile = new File(dir, "device_log.txt");

                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                    logFile.delete();
                    FileWriter newWriter = new FileWriter(logFile, true);
                    newWriter.write(sdf.format(new Date()) + " | SYSTEM | 🧹 历史日志超过5MB，已自动清空重写\n");
                    newWriter.close();
                }

                FileWriter writer = new FileWriter(logFile, true);
                writer.write(line + "\n");
                writer.flush();
                writer.close();
            } catch (Exception e) {
                Log.e("FileLogger", "写入日志失败", e);
            }
        }).start();
    }

    // 【新增】获取内存中的最近日志快照，用于云端上报
    public static String getMemoryLogs() {
        synchronized (memoryLogs) {
            if (memoryLogs.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String log : memoryLogs) {
                sb.append(log).append("\n");
            }
            return sb.toString();
        }
    }
}
