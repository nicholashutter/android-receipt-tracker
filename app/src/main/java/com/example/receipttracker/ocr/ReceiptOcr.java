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
 * Thin synchronous wrapper around the ML Kit on-device text recognizer.
 * Safe to call from a worker thread.
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

    /**
     * One OCR line, with its bounding box and a list of element-level
     * bounding boxes (one per word/number/separator). The element
     * boxes are what the visual-signal detector uses.
     */
    public static final class OcrLine {
        @NonNull public final String text;
        @Nullable public final Rect bbox;
        /** Per-word boxes, in reading order. May be empty if ML Kit didn't emit elements. */
        @NonNull public final List<OcrElement> elements;

        public OcrLine(@NonNull String text, @Nullable Rect bbox, @NonNull List<OcrElement> elements) {
            this.text = text;
            this.bbox = bbox;
            this.elements = elements;
        }
    }

    /** One OCR element (roughly a word / number / symbol). */
    public static final class OcrElement {
        @NonNull public final String text;
        @Nullable public final Rect bbox;

        public OcrElement(@NonNull String text, @Nullable Rect bbox) {
            this.text = text;
            this.bbox = bbox;
        }
    }

    private static final TextRecognizer RECOGNIZER =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private static final long TIMEOUT_SECONDS = 20;

    private ReceiptOcr() {}

    /** Returns the full recognized text, or null on failure / timeout. */
    @Nullable
    public static String recognizeText(@NonNull Bitmap bitmap) {
        List<OcrLine> lines = recognizeWithBoxes(bitmap);
        if (lines == null) return null;
        StringBuilder sb = new StringBuilder();
        for (OcrLine l : lines) {
            sb.append(l.text).append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns the recognized text as a list of {@link OcrLine}s in
     * reading order (top-to-bottom), each with its bounding box and
     * element-level boxes. Returns null on failure / timeout.
     */
    @Nullable
    public static List<OcrLine> recognizeWithBoxes(@NonNull Bitmap bitmap) {
        long t0 = System.currentTimeMillis();
        Logger.section("OCR START");
        Logger.i("OCR", "Input bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " config=" + bitmap.getConfig());
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        AtomicReference<List<OcrLine>> result = new AtomicReference<>(null);
        AtomicReference<Throwable> error = new AtomicReference<>(null);
        AtomicReference<Integer> blockCount = new AtomicReference<>(0);
        AtomicReference<Integer> lineCount = new AtomicReference<>(0);
        CountDownLatch latch = new CountDownLatch(1);

        RECOGNIZER.process(image)
                .addOnSuccessListener(text -> {
                    int blocks = text.getTextBlocks().size();
                    int lines = 0;
                    for (Text.TextBlock b : text.getTextBlocks()) lines += b.getLines().size();
                    blockCount.set(blocks);
                    lineCount.set(lines);
                    result.set(structureText(text));
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    error.set(e);
                    latch.countDown();
                });

        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Logger.e("OCR", "Timeout after " + TIMEOUT_SECONDS + "s waiting for ML Kit");
                Logger.section("OCR END (timeout)");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.w("OCR", "Interrupted while waiting for ML Kit");
            Logger.section("OCR END (interrupted)");
            return null;
        }
        if (error.get() != null) {
            Logger.e("OCR", "ML Kit failed", error.get());
            Logger.section("OCR END (failure)");
            return null;
        }
        List<OcrLine> out = result.get();
        long ms = System.currentTimeMillis() - t0;
        Logger.i("OCR", "Recognized " + blockCount.get() + " blocks, "
                + lineCount.get() + " lines, " + (out == null ? 0 : out.size())
                + " structured lines in " + ms + "ms");
        Logger.section("OCR END");
        return out;
    }

    private static List<OcrLine> structureText(Text text) {
        // Sort blocks by Y first, same as the old flatten() did.
        List<Text.TextBlock> blocks = new ArrayList<>(text.getTextBlocks());
        Collections.sort(blocks, new Comparator<Text.TextBlock>() {
            @Override
            public int compare(Text.TextBlock a, Text.TextBlock b) {
                Rect ra = a.getBoundingBox();
                Rect rb = b.getBoundingBox();
                int ya = ra == null ? 0 : ra.top;
                int yb = rb == null ? 0 : rb.top;
                return Integer.compare(ya, yb);
            }
        });
        List<OcrLine> out = new ArrayList<>();
        for (Text.TextBlock block : blocks) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                Rect lineBox = line.getBoundingBox();
                List<OcrElement> elements = new ArrayList<>();
                for (Text.Element el : line.getElements()) {
                    elements.add(new OcrElement(el.getText(), el.getBoundingBox()));
                }
                out.add(new OcrLine(lineText, lineBox, elements));
            }
        }
        return out;
    }
}
