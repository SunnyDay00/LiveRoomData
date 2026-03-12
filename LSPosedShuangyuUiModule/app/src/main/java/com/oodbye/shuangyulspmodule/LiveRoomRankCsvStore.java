package com.oodbye.shuangyulspmodule;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 用户数据 CSV 存储（单文件格式）。
 * 表头：时间,用户ID,用户昵称,性别,年龄,财富等级,魅力等级,粉丝数,地区,所在房间,所在房间ID
 */
final class LiveRoomRankCsvStore {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FILE_DAY_FORMAT =
            new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static String sPreparedDayToken = "";
    private static File sDataFile;

    private static final String CSV_HEADER =
            "时间,用户ID,用户昵称,性别,年龄,财富等级,魅力等级,粉丝数,地区,所在房间,所在房间ID";

    private static final String KEY_RUNTIME_CSV_BASE_DAY = "runtime_csv_base_day";
    private static final String KEY_RUNTIME_CSV_BASE_VERSION = "runtime_csv_base_version";

    private LiveRoomRankCsvStore() {
    }

    // ─────────────────────── 数据行 ───────────────────────
    static final class UserDataRow {
        final String userId;
        final String userName;
        final String gender;
        final String age;
        final String wealthLevel;
        final String charmLevel;
        final String followers;
        final String location;
        final String roomName;
        final String roomId;
        final long collectTimeMs;

        UserDataRow(
                String userId, String userName, String gender, String age,
                String wealthLevel, String charmLevel, String followers,
                String location, String roomName, String roomId,
                long collectTimeMs
        ) {
            this.userId = safeTrim(userId);
            this.userName = safeTrim(userName);
            this.gender = safeTrim(gender);
            this.age = safeTrim(age);
            this.wealthLevel = safeTrim(wealthLevel);
            this.charmLevel = safeTrim(charmLevel);
            this.followers = safeTrim(followers);
            this.location = safeTrim(location);
            this.roomName = safeTrim(roomName);
            this.roomId = safeTrim(roomId);
            this.collectTimeMs = Math.max(0L, collectTimeMs);
        }
    }

    // ─────────────────────── 追加结果 ───────────────────────
    static final class AppendResult {
        final int requestedCount;
        final int acceptedCount;
        final int writtenCount;
        final int duplicateSkippedCount;
        final boolean ioSuccess;

        AppendResult(int requestedCount, int acceptedCount, int writtenCount,
                     int duplicateSkippedCount, boolean ioSuccess) {
            this.requestedCount = Math.max(0, requestedCount);
            this.acceptedCount = Math.max(0, acceptedCount);
            this.writtenCount = Math.max(0, writtenCount);
            this.duplicateSkippedCount = Math.max(0, duplicateSkippedCount);
            this.ioSuccess = ioSuccess;
        }

        static AppendResult empty() {
            return new AppendResult(0, 0, 0, 0, true);
        }

        static AppendResult failed(int requested, int accepted) {
            return new AppendResult(requested, accepted, 0, 0, false);
        }
    }

    // ─────────────────────── 公开 API ───────────────────────

    static boolean appendRow(Context context, UserDataRow row) {
        if (row == null) return false;
        return appendRows(context, Collections.singletonList(row)).writtenCount > 0;
    }

    static AppendResult appendRows(Context context, List<UserDataRow> rows) {
        synchronized (LOCK) {
            if (!ensureFileReadyLocked(context)) {
                return AppendResult.failed(rows == null ? 0 : rows.size(), 0);
            }
            if (rows == null || rows.isEmpty()) {
                return AppendResult.empty();
            }
            int requestedCount = rows.size();
            int acceptedCount = 0;
            int duplicateSkipped = 0;
            ArrayList<String> pendingLines = new ArrayList<>(Math.max(8, requestedCount));
            HashSet<String> pendingSet = new HashSet<>(Math.max(8, requestedCount));

            for (UserDataRow row : rows) {
                if (row == null) continue;
                acceptedCount++;
                String csvLine = buildCsvLine(new String[]{
                        formatTime(row.collectTimeMs),
                        row.userId,
                        row.userName,
                        row.gender,
                        row.age,
                        row.wealthLevel,
                        row.charmLevel,
                        row.followers,
                        row.location,
                        row.roomName,
                        row.roomId
                });
                if (pendingSet.contains(csvLine)) {
                    duplicateSkipped++;
                    continue;
                }
                pendingSet.add(csvLine);
                pendingLines.add(csvLine);
            }

            if (pendingLines.isEmpty()) {
                return new AppendResult(requestedCount, acceptedCount, 0, duplicateSkipped, true);
            }

            if (!ensureFileInitializedLocked(sDataFile, CSV_HEADER)) {
                return AppendResult.failed(requestedCount, acceptedCount);
            }

            StringBuilder sb = new StringBuilder(Math.max(256, pendingLines.size() * 128));
            for (String line : pendingLines) {
                sb.append(line).append('\n');
            }

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(sDataFile, true);
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                return new AppendResult(
                        requestedCount, acceptedCount,
                        pendingLines.size(), duplicateSkipped, true
                );
            } catch (Throwable e) {
                return new AppendResult(requestedCount, acceptedCount, 0, duplicateSkipped, false);
            } finally {
                closeQuietly(fos);
            }
        }
    }

    static String getDataCsvPath(Context context) {
        synchronized (LOCK) {
            if (!ensureFileReadyLocked(context) || sDataFile == null) return "";
            ensureFileInitializedLocked(sDataFile, CSV_HEADER);
            return sDataFile.getAbsolutePath();
        }
    }

    // ─────────────────────── 内部实现 ───────────────────────

    private static boolean ensureFileReadyLocked(Context context) {
        if (context == null) return false;
        String dayToken = formatDayToken();
        if (sDataFile != null && TextUtils.equals(sPreparedDayToken, dayToken)) {
            return true;
        }
        File dir = resolveWritableDir(context);
        if (dir == null) return false;

        int version = resolveNextVersion(context, dir, dayToken);
        String fileName = UiComponentConfig.LIVE_RANK_CSV_PREFIX
                + "_" + dayToken + "_" + version
                + UiComponentConfig.LIVE_RANK_CSV_SUFFIX;
        sDataFile = new File(dir, fileName);
        sPreparedDayToken = dayToken;
        return true;
    }

    private static boolean ensureFileInitializedLocked(File file, String header) {
        if (file == null || TextUtils.isEmpty(header)) return false;
        ensureFile(file);
        if (!file.exists()) return false;
        if (file.length() <= 0L) {
            writeHeaderIfEmpty(file, header);
        }
        return file.exists();
    }

    private static int resolveNextVersion(Context context, File dir, String dayToken) {
        SharedPreferences prefs = null;
        try {
            prefs = ModuleSettings.appPrefs(context);
        } catch (Throwable ignore) {
        }
        if (prefs != null) {
            String storedDay = safeTrim(prefs.getString(KEY_RUNTIME_CSV_BASE_DAY, ""));
            int storedVersion = Math.max(0, prefs.getInt(KEY_RUNTIME_CSV_BASE_VERSION, 0));
            if (storedVersion > 0 && TextUtils.equals(storedDay, dayToken)) {
                return storedVersion;
            }
        }
        int maxVersion = scanMaxVersion(dir, dayToken);
        int nextVersion = Math.max(1, maxVersion + 1);
        if (prefs != null) {
            prefs.edit()
                    .putString(KEY_RUNTIME_CSV_BASE_DAY, dayToken)
                    .putInt(KEY_RUNTIME_CSV_BASE_VERSION, nextVersion)
                    .commit();
        }
        return nextVersion;
    }

    private static int scanMaxVersion(File dir, String dayToken) {
        int max = 0;
        if (dir == null || !dir.isDirectory()) return max;
        File[] files = dir.listFiles();
        if (files == null) return max;
        String prefix = UiComponentConfig.LIVE_RANK_CSV_PREFIX + "_" + dayToken + "_";
        String suffix = UiComponentConfig.LIVE_RANK_CSV_SUFFIX;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.startsWith(prefix) || !name.endsWith(suffix)) continue;
            String vStr = name.substring(prefix.length(), name.length() - suffix.length());
            if (!vStr.matches("^\\d{1,6}$")) continue;
            try {
                int v = Integer.parseInt(vStr);
                if (v > max) max = v;
            } catch (Throwable ignore) {
            }
        }
        return max;
    }

    private static void writeHeaderIfEmpty(File file, String header) {
        if (file == null || file.length() > 0L) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file, true);
            fos.write((header + "\n").getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignore) {
        } finally {
            closeQuietly(fos);
        }
    }

    private static String buildCsvLine(String[] fields) {
        StringBuilder sb = new StringBuilder(Math.max(48, fields.length * 16));
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeCsvField(fields[i]));
        }
        return sb.toString();
    }

    private static String escapeCsvField(String raw) {
        String value = safeTrim(raw).replace('\n', ' ').replace('\r', ' ');
        boolean needQuote = value.contains(",") || value.contains("\"");
        if (!needQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String formatDayToken() {
        synchronized (FILE_DAY_FORMAT) {
            return FILE_DAY_FORMAT.format(new Date());
        }
    }

    private static String formatTime(long ms) {
        long safe = Math.max(0L, ms);
        if (safe <= 0L) safe = System.currentTimeMillis();
        synchronized (TIME_FORMAT) {
            return TIME_FORMAT.format(new Date(safe));
        }
    }

    private static File resolveWritableDir(Context context) {
        File[] candidates = new File[]{
                new File(UiComponentConfig.LIVE_RANK_CSV_EXTERNAL_DIR),
                buildExternalContextDir(context),
                buildInternalContextDir(context)
        };
        for (File c : candidates) {
            if (c != null && isWritableDir(c)) return c;
        }
        return null;
    }

    private static File buildExternalContextDir(Context context) {
        if (context == null) return null;
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null) return null;
            return new File(base, UiComponentConfig.LIVE_RANK_CSV_FALLBACK_DIR_NAME);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static File buildInternalContextDir(Context context) {
        if (context == null) return null;
        try {
            File base = context.getFilesDir();
            if (base == null) return null;
            return new File(base, UiComponentConfig.LIVE_RANK_CSV_FALLBACK_DIR_NAME);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean isWritableDir(File dir) {
        try {
            if (dir.exists()) {
                if (!dir.isDirectory()) return false;
            } else if (!dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".probe");
            FileOutputStream fos = new FileOutputStream(probe, false);
            fos.write('1');
            fos.flush();
            closeQuietly(fos);
            if (probe.exists()) probe.delete();
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void ensureFile(File file) {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!file.exists()) file.createNewFile();
        } catch (Throwable ignore) {
        }
    }

    private static void closeQuietly(FileOutputStream fos) {
        if (fos == null) return;
        try { fos.close(); } catch (Throwable ignore) { }
    }

    private static String safeTrim(String value) {
        if (value == null) return "";
        return value.trim();
    }
}
