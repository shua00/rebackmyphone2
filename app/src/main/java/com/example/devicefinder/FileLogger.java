package com.example.devicefinder;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 最大 5MB
    private static final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    public static void log(Context context, String tag, String msg) {
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

                String time = sdf.format(new Date());
                String line = time + " | " + tag + " | " + msg + "\n";

                FileWriter writer = new FileWriter(logFile, true);
                writer.write(line);
                writer.flush();
                writer.close();
            } catch (Exception e) {
                Log.e("FileLogger", "写入日志失败", e);
            }
        }).start();
    }
}
