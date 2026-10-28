package com.example.devicefinder;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileLogger {
    private static final String TAG = "FileLogger";
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_MEMORY_LOGS = 50;   // [L1] 原 20→50，加日志后需更多快照
    private static final int KEEP_DAYS = 7;          // [L1] 历史日志保留 7 天
    private static final String LOG_FILE_NAME = "device_log.txt";
    private static final String LOG_FILE_PREFIX = "device_log_";

    private static final SimpleDateFormat sdfTime = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private static final LinkedList<String> memoryLogs = new LinkedList<>();

    // [L1] 单线程写文件，避免每次 new Thread（原实现线程爆炸）
    private static final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();

    // [L1] 记录当前文件日期，跨天时轮转
    private static String currentFileDate = null;

    /**
     * INFO/WARN/ERROR：写文件 + 内存 + logcat
     */
    public static void log(Context context, String tag, String msg) {
        String time = sdfTime.format(new Date());
        String line = time + " | " + tag + " | " + msg;

        synchronized (memoryLogs) {
            memoryLogs.add(line);
            if (memoryLogs.size() > MAX_MEMORY_LOGS) {
                memoryLogs.removeFirst();
            }
        }

        Log.i("DF_" + tag, msg);

        final Context appCtx = context.getApplicationContext();
        fileExecutor.execute(() -> writeToFile(appCtx, line));
    }

    /**
     * DEBUG：只进 logcat，不进文件、不上报云端
     * 用于高频日志（定位每次变化、UDP 每个包等）
     */
    public static void logD(String tag, String msg) {
        Log.d("DF_" + tag, msg);
    }

    private static void writeToFile(Context context, String line) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;

            String today = sdfDate.format(new Date());

            // 跨天轮转：device_log.txt → device_log_yyyy-MM-dd.txt
            if (currentFileDate != null && !currentFileDate.equals(today)) {
                File current = new File(dir, LOG_FILE_NAME);
                if (current.exists()) {
                    current.renameTo(new File(dir, LOG_FILE_PREFIX + currentFileDate + ".txt"));
                }
                cleanOldLogs(dir);
            }
            currentFileDate = today;

            File logFile = new File(dir, LOG_FILE_NAME);

            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                logFile.delete();
                FileWriter newWriter = new FileWriter(logFile, true);
                newWriter.write(sdfTime.format(new Date()) + " | SYSTEM | 🧹 单日日志超过5MB，已自动清空重写\n");
                newWriter.close();
            }

            FileWriter writer = new FileWriter(logFile, true);
            writer.write(line + "\n");
            writer.flush();
            writer.close();
        } catch (Exception e) {
            Log.e(TAG, "写入日志失败", e);
        }
    }

    private static void cleanOldLogs(File dir) {
        try {
            long now = System.currentTimeMillis();
            long maxAge = KEEP_DAYS * 24L * 60 * 60 * 1000;
            File[] files = dir.listFiles((d, name) -> name.startsWith(LOG_FILE_PREFIX) && name.endsWith(".txt"));
            if (files == null) return;
            for (File f : files) {
                if (now - f.lastModified() > maxAge) f.delete();
            }
        } catch (Exception ignored) {}
    }

    public static String getMemoryLogs() {
        synchronized (memoryLogs) {
            if (memoryLogs.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String log : memoryLogs) sb.append(log).append("\n");
            return sb.toString();
        }
    }
}
