package com.oodbye.lsposedchathook;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

/**
 * 远程批量上传器：缓存待上传记录，并由后台单线程批量发送到 Worker。
 */
class RemoteBatchUploader {
    private static final int MAX_PENDING = 6000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Object lock = new Object();
    private final String tag;
    private final RemoteApiClient remoteApiClient;
    private final List<RemoteApiClient.ChatRecord> pending = new ArrayList<RemoteApiClient.ChatRecord>();

    RemoteBatchUploader(String tag, RemoteApiClient remoteApiClient) {
        this.tag = tag;
        this.remoteApiClient = remoteApiClient;
    }

    void enqueue(RemoteApiClient.ChatRecord rec) {
        if (rec == null) {
            return;
        }
        synchronized (lock) {
            pending.add(rec);
            if (pending.size() > MAX_PENDING) {
                int dropCount = pending.size() - MAX_PENDING;
                for (int i = 0; i < dropCount; i++) {
                    pending.remove(0);
                }
                XposedBridge.log(tag + " remote pending overflow, drop oldest=" + dropCount);
            }
        }
    }

    int pendingSize() {
        synchronized (lock) {
            return pending.size();
        }
    }

    void flushAsync() {
        final List<RemoteApiClient.ChatRecord> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<RemoteApiClient.ChatRecord>(pending);
            pending.clear();
        }

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = remoteApiClient.ingestBatch(batch);
                if (!ok) {
                    requeue(batch);
                    XposedBridge.log(tag + " remote ingest failed, requeue=" + batch.size());
                }
            }
        });
    }

    private void requeue(List<RemoteApiClient.ChatRecord> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        synchronized (lock) {
            List<RemoteApiClient.ChatRecord> merged = new ArrayList<RemoteApiClient.ChatRecord>(batch.size() + pending.size());
            merged.addAll(batch);
            merged.addAll(pending);
            pending.clear();
            if (merged.size() > MAX_PENDING) {
                int from = merged.size() - MAX_PENDING;
                for (int i = from; i < merged.size(); i++) {
                    pending.add(merged.get(i));
                }
            } else {
                pending.addAll(merged);
            }
        }
    }
}

