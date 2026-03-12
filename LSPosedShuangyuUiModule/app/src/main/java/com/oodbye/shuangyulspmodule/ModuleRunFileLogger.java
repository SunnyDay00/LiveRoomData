package com.oodbye.shuangyulspmodule;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块运行日志文件记录器。
 */
final class ModuleRunFileLogger {
    private static final String TAG = "SYLspModule";
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final int MAX_SINGLE_FILE_BYTES = 512 * 1024;

    private static volatile File sCurrentFile;

    private ModuleRunFileLogger() {
    }

    static void init() {
        synchronized (LOCK) {
            File dir = resolveDir();
            if (dir == null) {
                sCurrentFile = null;
                return;
            }
            File current = new File(dir, UiComponentConfig.RUN_LOG_CURRENT_FILE_NAME);
            if (current.exists() && current.length() > MAX_SINGLE_FILE_BYTES) {
                File prev = new File(dir, UiComponentConfig.RUN_LOG_PREVIOUS_FILE_NAME);
                if (prev.exists()) prev.delete();
                current.renameTo(prev);
            }
            sCurrentFile = current;
        }
    }

    static void log(String level, String tag, String message) {
        File f = sCurrentFile;
        if (f == null) return;
        synchronized (LOCK) {
            String ts;
            synchronized (TS_FORMAT) {
                ts = TS_FORMAT.format(new Date());
            }
            String line = ts + " [" + level + "] " + tag + ": " + message + "\n";
            appendQuietly(f, line);
        }
    }

    static void i(String tag, String message) {
        log("I", tag, message);
        Log.i(tag, message);
    }

    static void w(String tag, String message) {
        log("W", tag, message);
        Log.w(tag, message);
    }

    static void e(String tag, String message) {
        log("E", tag, message);
        Log.e(tag, message);
    }

    static void e(String tag, String message, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        log("E", tag, message + "\n" + sw.toString());
        Log.e(tag, message, t);
    }

    private static void appendQuietly(File file, String content) {
        FileOutputStream fos = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            fos = new FileOutputStream(file, true);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignore) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignore) { }
            }
        }
    }

    private static File resolveDir() {
        try {
            File dir = new File(UiComponentConfig.RUN_LOG_EXTERNAL_DIR);
            if (!dir.exists()) dir.mkdirs();
            if (dir.isDirectory() && dir.canWrite()) return dir;
        } catch (Throwable ignore) {
        }
        return null;
    }
}
