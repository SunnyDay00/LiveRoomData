package com.oodbye.looklspmodule;

import android.content.Context;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ModuleRunFileLogger {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static long sPreparedCommandSeq = Long.MIN_VALUE;
    private static File sCurrentLogFile;

    private ModuleRunFileLogger() {
    }

    static void prepareForNewRun(Context context, long commandSeq) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            if (sCurrentLogFile != null
                    && commandSeq == sPreparedCommandSeq
                    && sCurrentLogFile.exists()) {
                return;
            }
            File dir = resolveWritableDir(context);
            if (dir == null) {
                return;
            }
            File current = new File(dir, UiComponentConfig.RUN_LOG_CURRENT_FILE_NAME);
            File backup = new File(dir, UiComponentConfig.RUN_LOG_PREVIOUS_FILE_NAME);
            rotateFiles(current, backup);
            ensureFile(current);
            sCurrentLogFile = current;
            sPreparedCommandSeq = commandSeq;
            appendLocked("=== NEW RUN seq=" + commandSeq
                    + " process=" + safeProcessName(context)
                    + " ===");
        }
    }

    static void appendLine(Context context, String line) {
        if (TextUtils.isEmpty(line)) {
            return;
        }
        synchronized (LOCK) {
            if (!ensureCurrentFileReady(context)) {
                return;
            }
            appendLocked(line);
        }
    }

    static String getCurrentLogPath(Context context) {
        synchronized (LOCK) {
            if (!ensureCurrentFileReady(context)) {
                return "";
            }
            return sCurrentLogFile.getAbsolutePath();
        }
    }

    private static boolean ensureCurrentFileReady(Context context) {
        if (sCurrentLogFile != null && sCurrentLogFile.exists()) {
            return true;
        }
        if (context == null) {
            return false;
        }
        File dir = resolveWritableDir(context);
        if (dir == null) {
            return false;
        }
        File current = new File(dir, UiComponentConfig.RUN_LOG_CURRENT_FILE_NAME);
        ensureFile(current);
        sCurrentLogFile = current;
        return sCurrentLogFile.exists();
    }

    private static void rotateFiles(File current, File backup) {
        if (backup.exists() && !backup.delete()) {
            // 尝试覆盖旧备份，删除失败时继续，后续 rename 可能失败。
        }
        if (!current.exists()) {
            return;
        }
        if (current.renameTo(backup)) {
            return;
        }
        String copied = readFileText(current);
        if (!TextUtils.isEmpty(copied)) {
            writeText(backup, copied, false);
        }
        if (current.exists()) {
            current.delete();
        }
    }

    private static String readFileText(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return "";
        }
        FileInputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            in = new FileInputStream(file);
            out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable ignore) {
            return "";
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void writeText(File file, String text, boolean append) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file, append);
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignore) {
        } finally {
            closeQuietly(fos);
        }
    }

    private static void ensureFile(File file) {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (Throwable ignore) {
        }
    }

    private static File resolveWritableDir(Context context) {
        File[] candidates = new File[] {
                new File(UiComponentConfig.RUN_LOG_EXTERNAL_DIR),
                buildExternalContextDir(context),
                buildInternalContextDir(context)
        };
        for (File candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (isWritableDir(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static File buildExternalContextDir(Context context) {
        if (context == null) {
            return null;
        }
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null) {
                return null;
            }
            return new File(base, UiComponentConfig.RUN_LOG_FALLBACK_DIR_NAME);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static File buildInternalContextDir(Context context) {
        if (context == null) {
            return null;
        }
        try {
            File base = context.getFilesDir();
            if (base == null) {
                return null;
            }
            return new File(base, UiComponentConfig.RUN_LOG_FALLBACK_DIR_NAME);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean isWritableDir(File dir) {
        try {
            if (dir.exists()) {
                if (!dir.isDirectory()) {
                    return false;
                }
            } else if (!dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".probe");
            FileOutputStream fos = new FileOutputStream(probe, false);
            fos.write('1');
            fos.flush();
            closeQuietly(fos);
            probe.delete();
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void appendLocked(String line) {
        if (sCurrentLogFile == null) {
            return;
        }
        String content = formatLine(line);
        writeText(sCurrentLogFile, content, true);
    }

    private static String formatLine(String line) {
        String ts;
        synchronized (TS_FORMAT) {
            ts = TS_FORMAT.format(new Date());
        }
        return ts + " " + safeTrim(line) + "\n";
    }

    private static String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static String safeProcessName(Context context) {
        if (context == null) {
            return "";
        }
        try {
            return safeTrim(context.getPackageName());
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static void closeQuietly(FileOutputStream fos) {
        if (fos == null) {
            return;
        }
        try {
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    private static void closeQuietly(FileInputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Throwable ignore) {
        }
    }

    private static void closeQuietly(ByteArrayOutputStream out) {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (Throwable ignore) {
        }
    }
}
