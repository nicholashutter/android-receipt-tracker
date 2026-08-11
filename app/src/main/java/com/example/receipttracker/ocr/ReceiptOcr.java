package com.example.receipttracker.ocr;


import android.graphics.Bitmap;

import android.graphics.Rect;


import androidx.annotation.NonNull;

import androidx.annotation.Nullable;


import com.example.receipttracker.log.Logger;


import com.google.mlkit.vision.common.InputImage;

import com.google.mlkit.vision.text.Text;

import com.google.mlkit.vision.text.TextRecognition;

import com.google.mlkit.vision.text.TextRecognizer;

import com.google.mlkit.vision.text.latin.TextRecognizerOptions;


import java.util.ArrayList;

import java.util.Collections;

import java.util.Comparator;

import java.util.List;

import java.util.concurrent.CountDownLatch;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReference;


/**
 * Thin synchronous wrapper around the ML Kit on-device text
 * recognizer. Safe to call from a worker thread.
 *
 * <p>Two flavours:</p>
 * <ul>
 *   <li>{@link #recognizeText(Bitmap)} — the legacy string-only path,
 *       used by the test pipeline and any caller that doesn't need
 *       bounding boxes.</li>
 *   <li>{@link #recognizeWithBoxes(Bitmap)} — returns a list of
 *       {@link OcrLine} objects (text + per-line rect + per-element
 *       rects), used by the visual-signal detector and the
 *       highlighted-number heuristic.</li>
 * </ul>
 */
public final class ReceiptOcr {

    private static final String LOG_TAG = "OCR";
    private static final long TIMEOUT_SECONDS = 20L;
    private static final int SUCCESS_LINES_INITIAL = 0;
    private static final int ROTATION_DEGREES = 0;


    private static final TextRecognizer RECOGNIZER =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);


    private ReceiptOcr() {}


    /** One OCR line, with its bounding box and per-element boxes. */
    public static final class OcrLine {
        @NonNull public final String text;
        @Nullable public final Rect bbox;
        /** Per-word boxes, in reading order. May be empty if ML Kit didn't emit elements. */
        @NonNull public final List<OcrElement> elements;

        public OcrLine(final @NonNull String text, final @Nullable Rect bbox,
                       final @NonNull List<OcrElement> elements) {
            this.text = text;
            this.bbox = bbox;
            this.elements = elements;
        }
    }


    /** One OCR element (roughly a word / number / symbol). */
    public static final class OcrElement {
        @NonNull public final String text;
        @Nullable public final Rect bbox;

        public OcrElement(final @NonNull String text, final @Nullable Rect bbox) {
            this.text = text;
            this.bbox = bbox;
        }
    }


    /** Returns the full recognized text, or null on failure / timeout. */
    @Nullable
    public static String recognizeText(final @NonNull Bitmap bitmap) {
        final List<OcrLine> lines = recognizeWithBoxes(bitmap);
        if (lines == null) return null;

        final StringBuilder joined = new StringBuilder();
        for (final OcrLine line : lines) {
            joined.append(line.text).append('\n');
        }
        return joined.toString();
    }


    /**
     * Returns the recognized text as a list of {@link OcrLine}s in
     * reading order (top-to-bottom), each with its bounding box and
     * element-level boxes. Returns null on failure / timeout.
     */
    @Nullable
    public static List<OcrLine> recognizeWithBoxes(final @NonNull Bitmap bitmap) {
        final long startMillis = System.currentTimeMillis();
        Logger.section("OCR START");
        Logger.i(LOG_TAG, "Input bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " config=" + bitmap.getConfig());

        final InputImage image = InputImage.fromBitmap(bitmap, ROTATION_DEGREES);
        final AtomicReference<List<OcrLine>> resultHolder = new AtomicReference<>(null);
        final AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);
        final AtomicReference<Integer> blockCountHolder = new AtomicReference<>(0);
        final AtomicReference<Integer> lineCountHolder = new AtomicReference<>(SUCCESS_LINES_INITIAL);
        final CountDownLatch latch = new CountDownLatch(1);

        RECOGNIZER.process(image)
                .addOnSuccessListener(text -> {
                    final int blockCount = text.getTextBlocks().size();
                    int lineCount = SUCCESS_LINES_INITIAL;
                    for (final Text.TextBlock block : text.getTextBlocks()) {
                        lineCount += block.getLines().size();
                    }
                    blockCountHolder.set(blockCount);
                    lineCountHolder.set(lineCount);
                    resultHolder.set(structureText(text));
                    latch.countDown();
                })
                .addOnFailureListener(failure -> {
                    errorHolder.set(failure);
                    latch.countDown();
                });

        if (!awaitLatch(latch)) {
            return null;
        }
        if (errorHolder.get() != null) {
            Logger.e(LOG_TAG, "ML Kit failed", errorHolder.get());
            Logger.section("OCR END (failure)");
            return null;
        }

        final List<OcrLine> structured = resultHolder.get();
        final long elapsedMillis = System.currentTimeMillis() - startMillis;
        final int structuredCount;
        if (structured == null) {
            structuredCount = 0;
        } else {
            structuredCount = structured.size();
        }
        Logger.i(LOG_TAG, "Recognized " + blockCountHolder.get() + " blocks, "
                + lineCountHolder.get() + " lines, " + structuredCount
                + " structured lines in " + elapsedMillis + "ms");
        Logger.section("OCR END");
        return structured;
    }


    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Logger.w(LOG_TAG, "Interrupted while waiting for ML Kit");
            Logger.section("OCR END (interrupted)");
            return false;
        }
    }


    private static List<OcrLine> structureText(final Text text) {
        // Sort blocks by Y first, same as the old flatten() did.
        final List<Text.TextBlock> blocks = new ArrayList<>(text.getTextBlocks());
        Collections.sort(blocks, new Comparator<Text.TextBlock>() {
            @Override
            public int compare(Text.TextBlock a, Text.TextBlock b) {
                final Rect aBox = a.getBoundingBox();
                final Rect bBox = b.getBoundingBox();
                final int aTop = (aBox == null) ? 0 : aBox.top;
                final int bTop = (bBox == null) ? 0 : bBox.top;
                return Integer.compare(aTop, bTop);
            }
        });

        final List<OcrLine> structured = new ArrayList<>();
        for (final Text.TextBlock block : blocks) {
            for (final Text.Line line : block.getLines()) {
                final String lineText = line.getText();
                final Rect lineBox = line.getBoundingBox();
                final List<OcrElement> elements = new ArrayList<>();
                for (final Text.Element element : line.getElements()) {
                    elements.add(new OcrElement(element.getText(), element.getBoundingBox()));
                }
                structured.add(new OcrLine(lineText, lineBox, elements));
            }
        }
        return structured;
    }
}
