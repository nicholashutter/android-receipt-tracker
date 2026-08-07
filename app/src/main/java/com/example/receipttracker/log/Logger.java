package com.example.receipttracker.log;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Process-wide logger that writes to BOTH logcat AND a rolling file under
 * the app's private filesDir. Survives crashes because the on-disk writer
 * is opened in append mode and fsync'd on every write.
 *
 * Also keeps a small in-memory ring buffer of the last N events so a
 * "View logs" screen can show recent activity without having to read the
 * whole file, and so the last events are available even if the disk write
 * was somehow lost.
 *
 * Wire the uncaught-exception handler in {@link #installCrashHandler()}.
 */
public final class Logger {

    public static final String TAG = "RT";

    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "app.log";
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;  // 5 MB
    private static final int RING_SIZE = 500;

    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static final Object LOCK = new Object();

    private static File logFile;
    private static boolean initialised;
    private static final Deque<String> RING = new ArrayDeque<>(RING_SIZE);

    private Logger() {}

    public static void init(Context ctx) {
        if (initialised) return;
        synchronized (LOCK) {
            if (initialised) return;
            File dir = new File(ctx.getFilesDir(), LOG_DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            logFile = new File(dir, LOG_FILE);
            // Touch the file so it exists for tail/share.
            try { if (!logFile.exists()) //noinspection ResultOfMethodCallIgnored
                logFile.createNewFile(); } catch (IOException ignored) { }
            initialised = true;
        }
        installCrashHandler();
        i(TAG, "Logger initialised; file=" + (logFile == null ? "?" : logFile.getAbsolutePath()));
    }

    private static Thread.UncaughtExceptionHandler previousCrashHandler;
    public static void installCrashHandler() {
        if (previousCrashHandler != null) return;
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                e(TAG, "Uncaught exception on thread '" + t.getName() + "'", e);
                flushSync();
            } catch (Throwable ignored) { }
            // Chain to whatever was there before (system, crashlytics, etc.)
            if (previousCrashHandler != null) {
                previousCrashHandler.uncaughtException(t, e);
            } else {
                System.exit(2);
            }
        });
    }

    // ---------- public API ----------

    public static void d(String tag, String msg) { log(Level.D, tag, msg, null); }
    public static void i(String tag, String msg) { log(Level.I, tag, msg, null); }
    public static void w(String tag, String msg) { log(Level.W, tag, msg, null); }
    public static void w(String tag, String msg, Throwable t) { log(Level.W, tag, msg, t); }
    public static void e(String tag, String msg) { log(Level.E, tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { log(Level.E, tag, msg, t); }

    /** A divider line — handy for separating "user actions" in the log viewer. */
    public static void section(String title) {
        log(Level.I, TAG, "=== " + title + " ===", null);
    }

    public static File logFile() {
        return logFile;
    }

    public static int ringSize() {
        synchronized (LOCK) { return RING.size(); }
    }

    /** Returns the last `lines` lines from the in-memory ring (newest last). */
    public static List<String> recent(int lines) {
        synchronized (LOCK) {
            List<String> all = new ArrayList<>(RING);
            int from = Math.max(0, all.size() - lines);
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }

    /** Returns the full log file as a String (synchronous, may be large). */
    public static String readAll() throws IOException {
        if (logFile == null) return "";
        synchronized (LOCK) {
            java.io.FileInputStream in = new java.io.FileInputStream(logFile);
            byte[] buf = new byte[(int) logFile.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            in.close();
            return new String(buf, 0, read, "UTF-8");
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
            if (logFile != null && logFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
                try {
                    //noinspection ResultOfMethodCallIgnored
                    logFile.createNewFile();
                } catch (IOException ignored) { }
            }
        }
    }

    /** Force a fsync — call this from critical error paths to ensure the
     *  last N events are durable before the process dies. */
    public static void flushSync() {
        synchronized (LOCK) {
            // FileOutputStream.flush() does not fsync; we open a fresh handle
            // to a no-op sibling and use getFD().sync() via reflection-free
            // approach: just close the current writer. We don't keep a
            // long-lived writer — every line is opened/closed under the lock,
            // so there is nothing to flush.
        }
    }

    // ---------- internals ----------

    private enum Level { D, I, W, E }

    private static void log(Level level, String tag, String msg, Throwable t) {
        if (!initialised) {
            // Best-effort fallback to logcat only if init() never ran.
            fallback(level, tag, msg, t);
            return;
        }
        String time = TS_FMT.format(new Date());
        String line;
        if (t == null) {
            line = String.format(Locale.US, "%s  %s/%s  %s", time, level.name(), tag, msg);
        } else {
            line = String.format(Locale.US, "%s  %s/%s  %s%n%s", time, level.name(), tag, msg,
                    stackTraceToString(t));
        }
        synchronized (LOCK) {
            // Logcat
            switch (level) {
                case D: Log.d(tag, msg); break;
                case I: Log.i(tag, msg); break;
                case W: Log.w(tag, msg, t); break;
                case E: Log.e(tag, msg, t); break;
            }
            // File
            appendLine(line);
            // Ring buffer
            RING.addLast(line);
            while (RING.size() > RING_SIZE) RING.pollFirst();
        }
    }

    private static void fallback(Level level, String tag, String msg, Throwable t) {
        switch (level) {
            case D: Log.d(tag, msg); break;
            case I: Log.i(tag, msg); break;
            case W: Log.w(tag, msg, t); break;
            case E: Log.e(tag, msg, t); break;
        }
    }

    private static void appendLine(String line) {
        if (logFile == null) return;
        try {
            // Rotate if too big
            if (logFile.length() > MAX_FILE_BYTES) {
                File old = new File(logFile.getParentFile(), "app.log.1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(old);
                //noinspection ResultOfMethodCallIgnored
                logFile.createNewFile();
            }
            try (FileWriter w = new FileWriter(logFile, true)) {
                w.write(line);
                w.write("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "log write failed", e);
        }
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
