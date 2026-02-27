package com.oodbye.lsposedchathook;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

class CsvAsyncBatchWriter {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Object lock = new Object();
    private final String tag;
    private final List<Entry> pending = new ArrayList<Entry>();

    CsvAsyncBatchWriter(String tag) {
        this.tag = tag;
    }

    void enqueue(File file, String line) {
        if (file == null || line == null) {
            return;
        }
        synchronized (lock) {
            pending.add(new Entry(file, line));
        }
    }

    int pendingSize() {
        synchronized (lock) {
            return pending.size();
        }
    }

    void flushAsync() {
        final List<Entry> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<Entry>(pending);
            pending.clear();
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                writeBatch(batch);
            }
        });
    }

    private void writeBatch(List<Entry> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        LinkedHashMap<String, File> fileMap = new LinkedHashMap<String, File>();
        LinkedHashMap<String, StringBuilder> lineMap = new LinkedHashMap<String, StringBuilder>();
        for (int i = 0; i < batch.size(); i++) {
            Entry entry = batch.get(i);
            if (entry == null || entry.file == null || entry.line == null) {
                continue;
            }
            String path = entry.file.getAbsolutePath();
            if (!fileMap.containsKey(path)) {
                fileMap.put(path, entry.file);
            }
            StringBuilder sb = lineMap.get(path);
            if (sb == null) {
                sb = new StringBuilder();
                lineMap.put(path, sb);
            }
            sb.append(entry.line);
        }

        for (Map.Entry<String, File> it : fileMap.entrySet()) {
            String path = it.getKey();
            File file = it.getValue();
            StringBuilder sb = lineMap.get(path);
            if (file == null || sb == null || sb.length() == 0) {
                continue;
            }
            try {
                File dir = file.getParentFile();
                if (dir == null) {
                    continue;
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    XposedBridge.log(tag + " mkdir failed: " + dir.getAbsolutePath());
                    continue;
                }

                boolean needHeader = !file.exists() || file.length() == 0;
                FileOutputStream fos = new FileOutputStream(file, true);
                try {
                    if (needHeader) {
                        fos.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                        fos.write("时间,聊天记录\n".getBytes(StandardCharsets.UTF_8));
                    }
                    fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                } finally {
                    fos.close();
                }
            } catch (Throwable e) {
                XposedBridge.log(tag + " write csv batch error: " + e);
            }
        }
    }

    private static class Entry {
        private final File file;
        private final String line;

        Entry(File file, String line) {
            this.file = file;
            this.line = line;
        }
    }
}

