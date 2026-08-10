package com.example.receipttracker.ocr;


import android.content.Context;

import android.graphics.Bitmap;

import android.graphics.Rect;


import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import com.example.receipttracker.ReceiptTrackerApp;

import com.example.receipttracker.log.Logger;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;

import java.io.FileOutputStream;

import java.io.InputStream;

import java.util.regex.Matcher;

import java.util.regex.Pattern;


/**
 * On-device handwriting recognizer, backed by Tesseract 4 (LSTM).
 *
 * <p>Why this exists: ML Kit's Latin text recognizer is print-optimised and
 * misses most pen-written digits ("$15.00" written as a tip on a receipt
 * often comes back as "$1S.00" or just empty). Tesseract's `eng` LSTM model
 * handles handwriting much better, at the cost of a ~22 MB traineddata
 * file. We only invoke Tesseract on visually-emphasised bboxes (yellow
 * highlighter, pen circle) — the case where handwriting is most likely
 * to be the actual total the user is pointing at.</p>
 *
 * <p>Lifecycle: this class is a process-wide singleton. {@link #shutdown()}
 * is wired up via {@link ReceiptTrackerApp#onCreate()} in case the process
 * needs to release native memory, but in practice the app just keeps the
 * engine around for the lifetime of the process.</p>
 *
 * <p>If {@code eng.traineddata} is missing from the APK assets, all
 * recognition methods return null and {@link #isAvailable()} returns
 * false. The visual-signal pipeline still works; it just trusts ML Kit
 * for the text in marked bboxes. See {@code assets/tessdata/README.md}
 * for how to add the traineddata.</p>
 */
public final class HandwritingOcr {

    private static final String TAG = "HandwritingOcr";


    private static final String TESSDATA_ASSET = "tessdata/eng.traineddata";

    private static final String LANG = "eng";


    /**
     * Match the first money-shaped number in Tesseract's text output.
     * Handwritten digits are often ambiguous (e.g. "1" vs "l"), so we
     * anchor on the decimal point + 2 digits pattern when present,
     * then fall back to a plain integer.
     */
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(\\d{1,4}\\.\\d{2})|(\\d{2,6})"
    );


    private static final HandwritingOcr INSTANCE = new HandwritingOcr();


    private volatile boolean initAttempted = false;

    private volatile boolean available = false;

    private volatile TessBaseAPI api;


    private HandwritingOcr() {}


    public static HandwritingOcr get() {
        return INSTANCE;
    }


    /**
     * Returns true if Tesseract is loaded and ready. False until the first
     * call to {@link #ensureInit(Context)} (or any {@code recognize*} call),
     * or if the traineddata file is missing.
     */
    public boolean isAvailable() {
        return available;
    }


    /**
     * Loads the traineddata from app assets into the app's private files
     * dir on first use, then initialises the native API. Idempotent and
     * thread-safe. Returns true on success, false if the traineddata is
     * missing or the native init throws (which is what happens on devices
     * that don't support the bundled .so).
     */
    public synchronized boolean ensureInit(@NonNull Context ctx) {
        if (initAttempted) {
            return available;
        }

        initAttempted = true;

        try {
            File tessDir = new File(ctx.getFilesDir(), "tessdata");

            tessDir.mkdirs();

            File dataFile = new File(tessDir, LANG + ".traineddata");

            if (!dataFile.exists()) {
                Logger.i(TAG, "Copying " + TESSDATA_ASSET + " from assets to "
                        + dataFile.getAbsolutePath());

                try (InputStream in = ctx.getAssets().open(TESSDATA_ASSET);
                     FileOutputStream out = new FileOutputStream(dataFile)) {

                    byte[] buf = new byte[8192];

                    int n;

                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }

                }

                Logger.i(TAG, "Traineddata ready, " + dataFile.length() + " bytes");

            }

            TessBaseAPI tess = new TessBaseAPI();

            boolean ok = tess.init(tessDir.getAbsolutePath(), LANG);

            if (!ok) {
                Logger.w(TAG, "tess.init returned false — Tesseract unavailable");

                tess.recycle();

                available = false;

                return false;

            }

            // LSTM OEM + line-mode PSM are the tesseract4android defaults,
            // so we don't need to override them. Older versions of the
            // library exposed setOcrEngineMode / setPageSegMode but the
            // current API is init-only; just go with the defaults.

            this.api = tess;

            this.available = true;

            Logger.i(TAG, "Tesseract ready (LSTM, eng)");

            return true;

        } catch (Exception e) {
            Logger.w(TAG, "Tesseract init failed: " + e.getMessage()
                    + " — handwriting recognition disabled");

            available = false;

            return false;

        }

    }


    /**
     * Recognises text in the given bitmap cropped to {@code bbox}.
     * Returns null if Tesseract is unavailable, the bitmap is null,
     * or Tesseract returns an empty string.
     */
    @Nullable
    public String recognizeText(@NonNull Bitmap bitmap, @NonNull Rect bbox) {
        if (!ensureInit(safeContext())) {
            return null;
        }

        if (bitmap == null || bitmap.isRecycled() || bbox.isEmpty()) {
            return null;
        }

        // Clamp the bbox to the bitmap bounds (Tesseract will throw on
        // out-of-range crop rects, and ML Kit bboxes sometimes extend
        // a few pixels past the image edge).
        int x0 = Math.max(0, bbox.left);

        int y0 = Math.max(0, bbox.top);

        int x1 = Math.min(bitmap.getWidth(), bbox.right);

        int y1 = Math.min(bitmap.getHeight(), bbox.bottom);

        int w = x1 - x0;

        int h = y1 - y0;

        if (w <= 0 || h <= 0) {
            return null;
        }

        try {
            Bitmap cropped = Bitmap.createBitmap(bitmap, x0, y0, w, h);

            try {
                api.setImage(cropped);

                String text = api.getUTF8Text();

                if (text == null) {
                    return null;
                }

                String trimmed = text.trim();

                if (trimmed.isEmpty()) {
                    return null;
                }

                Logger.d(TAG, "Tesseract: bbox=(" + x0 + "," + y0 + "," + x1 + "," + y1
                        + ") -> '" + trimmed + "'");

                return trimmed;

            } finally {
                cropped.recycle();

            }

        } catch (Throwable t) {
            Logger.w(TAG, "Tesseract recognise failed: " + t.getMessage());

            return null;

        }

    }


    /**
     * Recognises text in a bbox and parses out the first money-shaped
     * number. Returns null if Tesseract is unavailable, no text was
     * recognised, or the text didn't contain a number.
     */
    @Nullable
    public Double recognizeFirstNumber(@NonNull Bitmap bitmap, @NonNull Rect bbox) {
        String text = recognizeText(bitmap, bbox);

        if (text == null) {
            return null;
        }

        return parseFirstNumber(text);

    }


    /**
     * Extracts the first money-shaped number from a Tesseract text result.
     * Visible for callers that already have the text and just want the
     * parse step.
     */
    @Nullable
    public static Double parseFirstNumber(@NonNull String text) {
        Matcher m = MONEY_PATTERN.matcher(text);

        if (!m.find()) {
            return null;
        }

        String raw = m.group(1) != null ? m.group(1) : m.group(2);

        if (raw == null) {
            return null;
        }

        try {
            double v = Double.parseDouble(raw);

            if (v <= 0) {
                return null;
            }

            return v;

        } catch (NumberFormatException e) {
            return null;

        }

    }


    /**
     * Releases the native Tesseract engine. Optional — the OS will reclaim
     * memory on process exit. Most callers should never need this.
     */
    public synchronized void shutdown() {
        if (api != null) {
            try {
                api.recycle();

            } catch (Throwable ignored) { }

            api = null;

        }

        available = false;

        initAttempted = false;

    }


    /**
     * Resolves an application Context for the traineddata copy step.
     * {@link ReceiptTrackerApp} sets a static reference in its onCreate.
     * Falls back to null if the app class isn't installed (shouldn't
     * happen in production).
     */
    private static Context safeContext() {
        return ReceiptTrackerApp.get();
    }

}
