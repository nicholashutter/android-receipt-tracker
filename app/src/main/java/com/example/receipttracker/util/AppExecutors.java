package com.example.receipttracker.util;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Tiny two-executor pool shared by the app.
 * <p>
 * - {@link #diskIO()} - single-thread executor for all Room work. One thread keeps writes
 *   serialized and avoids the "main thread is waiting for a write it just kicked off"
 *   surprise that bites you with bigger pools.
 * - {@link #mainThread()} - the UI thread, used to post results back after disk work.
 * <p>
 * This is the deliberate "Executor + LiveData" half of the AsyncTask replacement. LiveData
 * is used for the list-loaders in the activities; the one-shot operations (save, delete,
 * export, match confirm/unlink) use these executors directly.
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    public static AppExecutors get() { return INSTANCE; }

    private final Executor diskIO;
    private final Executor mainThread;

    private AppExecutors() {
        this.diskIO = Executors.newSingleThreadExecutor();
        this.mainThread = new MainThreadExecutor();
    }

    public Executor diskIO() { return diskIO; }

    public Executor mainThread() { return mainThread; }

    private static class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());
        @Override
        public void execute(@NonNull Runnable command) {
            handler.post(command);
        }
    }
}
