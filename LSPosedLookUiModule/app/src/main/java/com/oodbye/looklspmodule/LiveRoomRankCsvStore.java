package com.oodbye.looklspmodule;

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

final class LiveRoomRankCsvStore {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FILE_DAY_FORMAT =
            new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static long sPreparedCommandSeq = Long.MIN_VALUE;
    private static int sPreparedRuntimeCycleIndex = -1;
    private static File sContributionFile;
    private static File sCharmFile;
    private static final String CONTRIBUTION_HEADER =
            "homeid,ueseid,uesename,level,Data,Consumption,ueseip,time";
    private static final String CHARM_HEADER =
            "homeid,upid,upname,Data,followers,Consumption,upip,time";

    private LiveRoomRankCsvStore() {
    }

    static boolean appendContributionRow(
            Context context,
            String homeId,
            String userId,
            String userName,
            String level,
            String dataValue,
            String consumption,
            String userIp,
            long enterTimeMs
    ) {
        ContributionCsvRow row = new ContributionCsvRow(
                homeId,
                userId,
                userName,
                level,
                dataValue,
                consumption,
                userIp,
                enterTimeMs
        );
        return appendContributionRows(context, Collections.singletonList(row)) > 0;
    }

    static boolean appendCharmRow(
            Context context,
            String homeId,
            String upId,
            String upName,
            String dataValue,
            String followers,
            String consumption,
            String upIp,
            long enterTimeMs
    ) {
        CharmCsvRow row = new CharmCsvRow(
                homeId,
                upId,
                upName,
                dataValue,
                followers,
                consumption,
                upIp,
                enterTimeMs
        );
        return appendCharmRows(context, Collections.singletonList(row)) > 0;
    }

    static int appendContributionRows(Context context, List<ContributionCsvRow> rows) {
        AppendResult result = appendContributionRowsWithResult(context, rows);
        return result.acceptedCount;
    }

    static AppendResult appendContributionRowsWithResult(
            Context context,
            List<ContributionCsvRow> rows
    ) {
        synchronized (LOCK) {
            if (!ensureFilesReadyLocked(context)) {
                return AppendResult.failed(0, 0);
            }
            if (rows == null || rows.isEmpty()) {
                return AppendResult.empty();
            }
            ArrayList<String[]> lines = new ArrayList<String[]>(rows.size());
            for (ContributionCsvRow row : rows) {
                if (row == null) {
                    continue;
                }
                lines.add(new String[] {
                        safeTrim(row.homeId),
                        safeTrim(row.userId),
                        safeTrim(row.userName),
                        safeTrim(row.level),
                        safeTrim(row.dataValue),
                        safeTrim(row.consumption),
                        safeTrim(row.userIp),
                        formatEnterTime(row.enterTimeMs)
                });
            }
            if (lines.isEmpty()) {
                return AppendResult.empty();
            }
            if (!ensureFileInitializedForAppendLocked(sContributionFile, CONTRIBUTION_HEADER)) {
                return AppendResult.failed(lines.size(), 0);
            }
            return appendCsvLinesLocked(sContributionFile, lines);
        }
    }

    static int appendCharmRows(Context context, List<CharmCsvRow> rows) {
        AppendResult result = appendCharmRowsWithResult(context, rows);
        return result.acceptedCount;
    }

    static AppendResult appendCharmRowsWithResult(
            Context context,
            List<CharmCsvRow> rows
    ) {
        synchronized (LOCK) {
            if (!ensureFilesReadyLocked(context)) {
                return AppendResult.failed(0, 0);
            }
            if (rows == null || rows.isEmpty()) {
                return AppendResult.empty();
            }
            ArrayList<String[]> lines = new ArrayList<String[]>(rows.size());
            for (CharmCsvRow row : rows) {
                if (row == null) {
                    continue;
                }
                lines.add(new String[] {
                        safeTrim(row.homeId),
                        safeTrim(row.upId),
                        safeTrim(row.upName),
                        safeTrim(row.dataValue),
                        safeTrim(row.followers),
                        safeTrim(row.consumption),
                        safeTrim(row.upIp),
                        formatEnterTime(row.enterTimeMs)
                });
            }
            if (lines.isEmpty()) {
                return AppendResult.empty();
            }
            if (!ensureFileInitializedForAppendLocked(sCharmFile, CHARM_HEADER)) {
                return AppendResult.failed(lines.size(), 0);
            }
            return appendCsvLinesLocked(sCharmFile, lines);
        }
    }

    static String getContributionCsvPath(Context context) {
        synchronized (LOCK) {
            if (!ensureFilesReadyLocked(context) || sContributionFile == null) {
                return "";
            }
            return sContributionFile.getAbsolutePath();
        }
    }

    static String getCharmCsvPath(Context context) {
        synchronized (LOCK) {
            if (!ensureFilesReadyLocked(context) || sCharmFile == null) {
                return "";
            }
            return sCharmFile.getAbsolutePath();
        }
    }

    private static boolean ensureFilesReadyLocked(Context context) {
        if (context == null) {
            return false;
        }
        long commandSeq = resolveCurrentCommandSeq(context);
        int runtimeCycleIndex = resolveCurrentCycleIndex(context);
        boolean needPrepare = commandSeq != sPreparedCommandSeq
                || runtimeCycleIndex != sPreparedRuntimeCycleIndex
                || sContributionFile == null
                || sCharmFile == null
                || !sContributionFile.exists()
                || !sCharmFile.exists();
        if (!needPrepare) {
            return true;
        }
        File dir = resolveWritableDir(context);
        if (dir == null) {
            return false;
        }
        String dayToken = formatFileDayToken();
        int cycleVersion = resolveNextDailyCycleVersion(dir, dayToken);
        String suffix = "_" + dayToken + "_" + cycleVersion + UiComponentConfig.LIVE_RANK_CSV_SUFFIX;
        File contribution = new File(
                dir,
                UiComponentConfig.LIVE_RANK_CSV_CONTRIBUTION_PREFIX + suffix
        );
        File charm = new File(
                dir,
                UiComponentConfig.LIVE_RANK_CSV_CHARM_PREFIX + suffix
        );
        sContributionFile = contribution;
        sCharmFile = charm;
        sPreparedCommandSeq = commandSeq;
        sPreparedRuntimeCycleIndex = Math.max(1, runtimeCycleIndex);
        return true;
    }

    private static boolean ensureFileInitializedForAppendLocked(File file, String headerLine) {
        if (file == null || TextUtils.isEmpty(headerLine)) {
            return false;
        }
        ensureFile(file);
        if (!file.exists()) {
            return false;
        }
        writeHeaderIfEmpty(file, headerLine);
        return file.exists();
    }

    private static long resolveCurrentCommandSeq(Context context) {
        try {
            SharedPreferences prefs = ModuleSettings.appPrefs(context);
            long seq = ModuleSettings.getEngineCommandSeq(prefs);
            if (seq > 0L) {
                return seq;
            }
        } catch (Throwable ignore) {
        }
        return Math.max(1L, System.currentTimeMillis());
    }

    private static int resolveCurrentCycleIndex(Context context) {
        try {
            SharedPreferences prefs = ModuleSettings.appPrefs(context);
            int completed = ModuleSettings.getRuntimeCycleCompleted(prefs);
            if (completed >= 0) {
                return completed + 1;
            }
        } catch (Throwable ignore) {
        }
        return 1;
    }

    private static boolean appendCsvLineLocked(File file, String[] fields) {
        if (file == null || fields == null || fields.length == 0) {
            return false;
        }
        ArrayList<String[]> lines = new ArrayList<String[]>(1);
        lines.add(fields);
        return appendCsvLinesLocked(file, lines).writtenCount > 0;
    }

    private static AppendResult appendCsvLinesLocked(
            File file,
            List<String[]> lines
    ) {
        if (file == null || lines == null || lines.isEmpty()) {
            return AppendResult.empty();
        }
        int requestedCount = lines.size();
        int acceptedCount = 0;
        int duplicateSkippedCount = 0;
        ArrayList<String> pendingLines = new ArrayList<String>(Math.max(8, requestedCount));
        HashSet<String> pendingLineSet = new HashSet<String>(Math.max(8, requestedCount));
        for (String[] fields : lines) {
            if (fields == null || fields.length == 0) {
                continue;
            }
            acceptedCount++;
            String csvLine = buildCsvLine(fields);
            if (pendingLineSet.contains(csvLine)) {
                duplicateSkippedCount++;
                continue;
            }
            pendingLineSet.add(csvLine);
            pendingLines.add(csvLine);
        }
        int writeCount = pendingLines.size();
        if (writeCount <= 0) {
            return new AppendResult(
                    requestedCount,
                    acceptedCount,
                    0,
                    duplicateSkippedCount,
                    true
            );
        }
        StringBuilder sb = new StringBuilder(Math.max(256, writeCount * 96));
        for (String line : pendingLines) {
            sb.append(line).append('\n');
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file, true);
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            return new AppendResult(
                    requestedCount,
                    acceptedCount,
                    writeCount,
                    duplicateSkippedCount,
                    true
            );
        } catch (Throwable ignore) {
            return new AppendResult(
                    requestedCount,
                    acceptedCount,
                    0,
                    duplicateSkippedCount,
                    false
            );
        } finally {
            closeQuietly(fos);
        }
    }

    private static void writeHeaderIfEmpty(File file, String headerLine) {
        if (file == null || TextUtils.isEmpty(headerLine)) {
            return;
        }
        if (file.length() > 0L) {
            return;
        }
        appendCsvLineLocked(file, splitHeader(headerLine));
    }

    private static String[] splitHeader(String headerLine) {
        String[] parts = headerLine.split(",");
        if (parts == null || parts.length == 0) {
            return new String[] { headerLine };
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = safeTrim(parts[i]);
        }
        return parts;
    }

    private static String escapeCsvField(String raw) {
        String value = safeTrim(raw)
                .replace('\n', ' ')
                .replace('\r', ' ');
        boolean needQuote = value.contains(",") || value.contains("\"");
        if (!needQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String buildCsvLine(String[] fields) {
        StringBuilder line = new StringBuilder(Math.max(48, fields.length * 12));
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(escapeCsvField(fields[i]));
        }
        return line.toString();
    }

    private static String formatFileDayToken() {
        synchronized (FILE_DAY_FORMAT) {
            return FILE_DAY_FORMAT.format(new Date());
        }
    }

    private static String formatEnterTime(long enterTimeMs) {
        long safe = Math.max(0L, enterTimeMs);
        if (safe <= 0L) {
            safe = System.currentTimeMillis();
        }
        synchronized (TIME_FORMAT) {
            return TIME_FORMAT.format(new Date(safe));
        }
    }

    private static File resolveWritableDir(Context context) {
        File[] candidates = new File[] {
                new File(UiComponentConfig.LIVE_RANK_CSV_EXTERNAL_DIR),
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

    private static int resolveNextDailyCycleVersion(File dir, String dayToken) {
        int maxVersion = 0;
        if (dir != null && dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file == null || !file.isFile()) {
                        continue;
                    }
                    String name = safeTrim(file.getName());
                    if (TextUtils.isEmpty(name)) {
                        continue;
                    }
                    int version = extractCycleVersionFromFileName(name, dayToken);
                    if (version > maxVersion) {
                        maxVersion = version;
                    }
                }
            }
        }
        return Math.max(1, maxVersion + 1);
    }

    private static int extractCycleVersionFromFileName(String fileName, String dayToken) {
        String name = safeTrim(fileName);
        String date = safeTrim(dayToken);
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(date)) {
            return 0;
        }
        if (!name.endsWith(UiComponentConfig.LIVE_RANK_CSV_SUFFIX)) {
            return 0;
        }
        boolean prefixMatched = name.startsWith(UiComponentConfig.LIVE_RANK_CSV_CONTRIBUTION_PREFIX + "_")
                || name.startsWith(UiComponentConfig.LIVE_RANK_CSV_CHARM_PREFIX + "_");
        if (!prefixMatched) {
            return 0;
        }
        String marker = "_" + date + "_";
        int markerIndex = name.lastIndexOf(marker);
        if (markerIndex < 0) {
            return 0;
        }
        int versionStart = markerIndex + marker.length();
        int suffixStart = name.lastIndexOf(UiComponentConfig.LIVE_RANK_CSV_SUFFIX);
        if (versionStart >= suffixStart) {
            return 0;
        }
        String versionText = name.substring(versionStart, suffixStart);
        if (!versionText.matches("^\\d{1,6}$")) {
            return 0;
        }
        try {
            return Integer.parseInt(versionText);
        } catch (Throwable ignore) {
            return 0;
        }
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
            return new File(base, UiComponentConfig.LIVE_RANK_CSV_FALLBACK_DIR_NAME);
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
            return new File(base, UiComponentConfig.LIVE_RANK_CSV_FALLBACK_DIR_NAME);
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
            if (probe.exists()) {
                probe.delete();
            }
            return true;
        } catch (Throwable ignore) {
            return false;
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

    private static void closeQuietly(FileOutputStream fos) {
        if (fos == null) {
            return;
        }
        try {
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    private static String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    static final class ContributionCsvRow {
        final String homeId;
        final String userId;
        final String userName;
        final String level;
        final String dataValue;
        final String consumption;
        final String userIp;
        final long enterTimeMs;

        ContributionCsvRow(
                String homeId,
                String userId,
                String userName,
                String level,
                String dataValue,
                String consumption,
                String userIp,
                long enterTimeMs
        ) {
            this.homeId = safeTrim(homeId);
            this.userId = safeTrim(userId);
            this.userName = safeTrim(userName);
            this.level = safeTrim(level);
            this.dataValue = safeTrim(dataValue);
            this.consumption = safeTrim(consumption);
            this.userIp = safeTrim(userIp);
            this.enterTimeMs = Math.max(0L, enterTimeMs);
        }
    }

    static final class CharmCsvRow {
        final String homeId;
        final String upId;
        final String upName;
        final String dataValue;
        final String followers;
        final String consumption;
        final String upIp;
        final long enterTimeMs;

        CharmCsvRow(
                String homeId,
                String upId,
                String upName,
                String dataValue,
                String followers,
                String consumption,
                String upIp,
                long enterTimeMs
        ) {
            this.homeId = safeTrim(homeId);
            this.upId = safeTrim(upId);
            this.upName = safeTrim(upName);
            this.dataValue = safeTrim(dataValue);
            this.followers = safeTrim(followers);
            this.consumption = safeTrim(consumption);
            this.upIp = safeTrim(upIp);
            this.enterTimeMs = Math.max(0L, enterTimeMs);
        }
    }

    static final class AppendResult {
        final int requestedCount;
        final int acceptedCount;
        final int writtenCount;
        final int duplicateSkippedCount;
        final boolean ioSuccess;

        AppendResult(
                int requestedCount,
                int acceptedCount,
                int writtenCount,
                int duplicateSkippedCount,
                boolean ioSuccess
        ) {
            this.requestedCount = Math.max(0, requestedCount);
            this.acceptedCount = Math.max(0, acceptedCount);
            this.writtenCount = Math.max(0, writtenCount);
            this.duplicateSkippedCount = Math.max(0, duplicateSkippedCount);
            this.ioSuccess = ioSuccess;
        }

        static AppendResult empty() {
            return new AppendResult(0, 0, 0, 0, true);
        }

        static AppendResult failed(int requestedCount, int acceptedCount) {
            return new AppendResult(requestedCount, acceptedCount, 0, 0, false);
        }
    }
}
