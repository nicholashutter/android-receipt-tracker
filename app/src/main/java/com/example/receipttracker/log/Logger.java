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
import java.util.Date;
import java.util.Deque;
import java.util.Locale;


/**
 * Process-wide logger that writes to BOTH logcat AND a rolling file under
 * the app's private filesDir. Survives crashes because the on-disk writer
 * is opened in append mode and fsync'd on every write.
 *
 * <p>Also keeps a small in-memory ring buffer of the last N events so a
 * "View logs" screen can show recent activity without having to read the
 * whole file, and so the last events are available even if the disk write
 * was somehow lost.</p>
 *
 * <p>Wire the uncaught-exception handler in {@link #installCrashHandler()}.</p>
 */
public final class Logger {

    public static final String TAG = "RT";

    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "app.log";
    private static final String ROLLED_FILE = "app.log.1";
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;  // 5 MB
    private static final int RING_SIZE = 500;
    private static final String LOG_FILE_PATH_PLACEHOLDER = "?";

    private static final SimpleDateFormat TIMESTAMP_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static final Object LOCK = new Object();

    private static File logFile;
    private static boolean initialised;
    private static final Deque<String> RING = new ArrayDeque<>(RING_SIZE);

    private static Thread.UncaughtExceptionHandler previousCrashHandler;

    private Logger() {}


    public static void init(Context appContext) {
        if (initialised) return;
        synchronized (LOCK) {
            if (initialised) return;

            final File logDirectory = new File(appContext.getFilesDir(), LOG_DIR);
            final boolean directoryReady = logDirectory.mkdirs();
            if (!directoryReady && !logDirectory.isDirectory()) {
                Logger.w(TAG, "Could not create log directory: " + logDirectory);
            }

            logFile = new File(logDirectory, LOG_FILE);
            try {
                if (!logFile.exists()) {
                    final boolean created = logFile.createNewFile();
                    if (!created) {
                        Logger.w(TAG, "Log file already exists or could not be created: " + logFile);
                    }
                }
            } catch (IOException ioException) {
                Logger.w(TAG, "Failed to touch log file", ioException);
            }

            initialised = true;
        }
        installCrashHandler();

        final String resolvedFilePath;
        if (logFile == null) {
            resolvedFilePath = LOG_FILE_PATH_PLACEHOLDER;
        } else {
            resolvedFilePath = logFile.getAbsolutePath();
        }
        i(TAG, "Logger initialised; file=" + resolvedFilePath);
    }


    public static void installCrashHandler() {
        if (previousCrashHandler != null) return;
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((crashingThread, throwable) -> {
            try {
                e(TAG, "Uncaught exception on thread '" + crashingThread.getName() + "'", throwable);
            } catch (Throwable ignored) {
                // Never let the crash handler itself throw.
            }
            if (previousCrashHandler != null) {
                previousCrashHandler.uncaughtException(crashingThread, throwable);
            } else {
                System.exit(2);
            }
        });
    }


    // ---------- public API ----------

    public static void d(String tag, String msg) { log(Level.D, tag, msg, null); }
    public static void i(String tag, String msg) { log(Level.I, tag, msg, null); }
    public static void w(String tag, String msg) { log(Level.W, tag, msg, null); }
    public static void w(String tag, String msg, Throwable throwable) { log(Level.W, tag, msg, throwable); }
    public static void e(String tag, String msg) { log(Level.E, tag, msg, null); }
    public static void e(String tag, String msg, Throwable throwable) { log(Level.E, tag, msg, throwable); }


    /** A divider line — handy for separating "user actions" in the log viewer. */
    public static void section(String title) {
        log(Level.I, TAG, "=== " + title + " ===", null);
    }


    public static File logFile() {
        return logFile;
    }


    /** Returns the full log file as a String (synchronous, may be large). */
    public static String readAll() throws IOException {
        if (logFile == null) return "";
        synchronized (LOCK) {
            final java.io.FileInputStream input = new java.io.FileInputStream(logFile);
            final byte[] buffer = new byte[(int) logFile.length()];
            int totalRead = 0;
            while (totalRead < buffer.length) {
                final int bytesRead = input.read(buffer, totalRead, buffer.length - totalRead);
                if (bytesRead < 0) break;
                totalRead += bytesRead;
            }
            input.close();
            return new String(buffer, 0, totalRead, "UTF-8");
        }
    }


    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
            if (logFile != null && logFile.exists()) {
                final boolean deleted = logFile.delete();
                if (!deleted) {
                    Logger.w(TAG, "Failed to delete log file before clear: " + logFile);
                }
                try {
                    final boolean recreated = logFile.createNewFile();
                    if (!recreated) {
                        Logger.w(TAG, "Could not recreate log file after clear: " + logFile);
                    }
                } catch (IOException ioException) {
                    Logger.w(TAG, "Failed to recreate log file after clear", ioException);
                }
            }
        }
    }


    // ---------- internals ----------

    private enum Level { D, I, W, E }


    private static void log(Level level, String tag, String msg, Throwable throwable) {
        if (!initialised) {
            // Best-effort fallback to logcat only if init() never ran.
            fallback(level, tag, msg, throwable);
            return;
        }
        final String timestamp = TIMESTAMP_FORMAT.format(new Date());
        final String line;
        if (throwable == null) {
            line = String.format(Locale.US, "%s  %s/%s  %s", timestamp, level.name(), tag, msg);
        } else {
            line = String.format(Locale.US, "%s  %s/%s  %s%n%s",
                    timestamp, level.name(), tag, msg, stackTraceToString(throwable));
        }
        synchronized (LOCK) {
            writeToLogcat(level, tag, msg, throwable);
            appendLine(line);
            RING.addLast(line);
            while (RING.size() > RING_SIZE) RING.pollFirst();
        }
    }


    private static void writeToLogcat(Level level, String tag, String msg, Throwable throwable) {
        switch (level) {
            case D: Log.d(tag, msg); break;
            case I: Log.i(tag, msg); break;
            case W: Log.w(tag, msg, throwable); break;
            case E: Log.e(tag, msg, throwable); break;
        }
    }


    private static void fallback(Level level, String tag, String msg, Throwable throwable) {
        writeToLogcat(level, tag, msg, throwable);
    }


    private static void appendLine(String line) {
        if (logFile == null) return;
        try {
            rotateIfNeeded();
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(line);
                writer.write("\n");
            }
        } catch (IOException ioException) {
            Log.e(TAG, "log write failed", ioException);
        }
    }


    private static void rotateIfNeeded() throws IOException {
        if (logFile.length() <= MAX_FILE_BYTES) return;
        final File rolledFile = new File(logFile.getParentFile(), ROLLED_FILE);
        rolledFile.delete();
        final boolean renamed = logFile.renameTo(rolledFile);
        if (!renamed) {
            Log.w(TAG, "Failed to rotate log file to " + rolledFile);
        }
        final boolean recreated = logFile.createNewFile();
        if (!recreated) {
            Log.w(TAG, "Failed to recreate log file after rotation: " + logFile);
        }
    }


    private static String stackTraceToString(final Throwable throwable) {
        final StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
