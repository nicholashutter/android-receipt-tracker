package com.example.receipttracker.ocr;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.example.receipttracker.log.Logger;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

/**
 * Thin synchronous wrapper around the ML Kit on-device text recognizer.
 * Safe to call from a worker thread.
 */
public final class ReceiptOcr {

    private static final TextRecognizer RECOGNIZER =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private ReceiptOcr() {}

    /** Returns the full recognized text, or null on failure / timeout. */
    public static String recognizeText(@NonNull Bitmap bitmap) {
        long t0 = System.currentTimeMillis();
        Logger.section("OCR START");
        Logger.i("OCR", "Input bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " config=" + bitmap.getConfig());
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        AtomicReference<String> result = new AtomicReference<>(null);
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
                    result.set(flatten(text));
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    error.set(e);
                    latch.countDown();
                });

        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                Logger.e("OCR", "Timeout after 20s waiting for ML Kit");
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
        String out = result.get();
        long ms = System.currentTimeMillis() - t0;
        Logger.i("OCR", "Recognized " + blockCount.get() + " blocks, "
                + lineCount.get() + " lines, " + (out == null ? 0 : out.length())
                + " chars in " + ms + "ms");
        // Echo the first 800 chars of OCR text so it's easy to find in the log.
        if (out != null) {
            String preview = out.length() > 800 ? out.substring(0, 800) + "..." : out;
            Logger.d("OCR", "Text preview:\n" + preview);
        }
        Logger.section("OCR END");
        return out;
    }

    private static String flatten(Text text) {
        // ML Kit does not always return text blocks in top-to-bottom order
        // — dense regions (e.g. the itemised body of a receipt) can come
        // first, with the merchant header later. Sort by the Y coordinate of
        // each block's bounding box so the resulting text reads in the
        // natural top-down order the user sees on the receipt.
        List<Text.TextBlock> blocks = new java.util.ArrayList<>(text.getTextBlocks());
        java.util.Collections.sort(blocks, (a, b) -> {
            android.graphics.Rect ra = a.getBoundingBox();
            android.graphics.Rect rb = b.getBoundingBox();
            int ya = ra == null ? 0 : ra.top;
            int yb = rb == null ? 0 : rb.top;
            return Integer.compare(ya, yb);
        });
        Logger.d("OCR", "Sorted " + blocks.size() + " text blocks by Y; order:");
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : blocks) {
            android.graphics.Rect bb = block.getBoundingBox();
            Logger.d("OCR", "  block top=" + (bb == null ? "?" : bb.top)
                    + " lines=" + block.getLines().size()
                    + " preview='" + block.getText().replace("\n", " | ") + "'");
            for (Text.Line line : block.getLines()) {
                sb.append(line.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
