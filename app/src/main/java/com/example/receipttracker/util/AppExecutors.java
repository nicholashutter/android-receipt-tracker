package com.example.receipttracker.util;


import android.os.Handler;

import android.os.Looper;


import androidx.annotation.NonNull;


import java.util.concurrent.Executor;

import java.util.concurrent.Executors;


/**
 * Tiny two-executor pool shared by the app.
 *
 * <ul>
 *   <li>{@link #diskIO()} - single-thread executor for all Room work.
 *       One thread keeps writes serialized and avoids the "main thread
 *       is waiting for a write it just kicked off" surprise that bites
 *       you with bigger pools.</li>
 *   <li>{@link #mainThread()} - the UI thread, used to post results
 *       back after disk work.</li>
 * </ul>
 *
 * <p>This is the deliberate "Executor + LiveData" half of the
 * AsyncTask replacement. LiveData is used for the list-loaders in the
 * activities; the one-shot operations (save, delete, export, match
 * confirm/unlink) use these executors directly.</p>
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final Executor diskIO;
    private final Executor mainThread;


    public static AppExecutors get() {
        return INSTANCE;
    }


    private AppExecutors() {
        this.diskIO = Executors.newSingleThreadExecutor();
        this.mainThread = new MainThreadExecutor();
    }


    public Executor diskIO() {
        return diskIO;
    }


    public Executor mainThread() {
        return mainThread;
    }


    /** Wraps the main looper as an {@link Executor}. */
    private static final class MainThreadExecutor implements Executor {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(@NonNull Runnable command) {
            mainHandler.post(command);
        }
    }
}
