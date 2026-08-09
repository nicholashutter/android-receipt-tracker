package com.example.receipttracker.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.example.receipttracker.log.Logger;

import java.util.Arrays;

/**
 * Pixel-level detector for "the user marked this on purpose" signals
 * on a receipt photo: a yellow highlighter swatch, or a pen circle
 * drawn around the total.
 *
 * <p>Why this exists: OCR alone sees a wall of black-on-white text.
 * But receipts are physical objects — the human who scanned them
 * often circled or highlighted the total before taking the photo,
 * and that visual signal is a much stronger "this is the total"
 * indicator than any text-based heuristic. By measuring the actual
 * pixels inside each detected number's bounding box, we can boost
 * candidates that the user visually emphasized, by a lot.</p>
 *
 * <p>Two signals, both 0..1:</p>
 * <ul>
 *   <li>{@code highlightScore} — fraction of pixels in the bbox that
 *       look "highlighter yellow" (R+G high, B low, with some
 *       saturation floor to reject paper-tan). 0.0 = no highlight,
 *       1.0 = the entire number is sitting on a yellow swatch.</li>
 *   <li>{@code circleScore} — fraction of "non-white" pixels in the
 *       bbox that form a closed loop around a roughly-square central
 *       area. A pen circle around a number leaves a ring of dark
 *       pixels with a white interior. Heuristic: count dark pixels
 *       in the four corner quadrants vs. the centre quadrant; a true
 *       circle has more dark in the ring than in the middle.</li>
 * </ul>
 *
 * <p>Sampling: we don't look at every pixel — for a 1200x1900 receipt
 * a single number's bbox might be 200x60, so we sample every Nth
 * pixel (configurable) to keep this well under 5ms per call even on
 * the full set of detected numbers.</p>
 */
public final class VisualSignalDetector {

    /** A detected visual emphasis on a number's bounding box. */
    public static final class Signals {
        public final float highlightScore;
        public final float circleScore;

        public Signals(float highlightScore, float circleScore) {
            this.highlightScore = clamp01(highlightScore);
            this.circleScore = clamp01(circleScore);
        }

        /** Either signal tripped its threshold. */
        public boolean isEmphasised() {
            return highlightScore >= 0.20f || circleScore >= 0.25f;
        }

        /** Higher = more likely the user marked this on purpose. */
        public float emphasis() {
            // Highlight is a stronger signal than circle (highlight is
            // unambiguous ink; circles can be any closed shape and
            // false-positive on dot-matrix printed numbers). Weight
            // highlight 2x, cap at 1.0.
            return clamp01(highlightScore * 0.66f + circleScore * 0.34f);
        }

        @NonNull
        @Override
        public String toString() {
            return String.format("highlight=%.2f circle=%.2f", highlightScore, circleScore);
        }
    }

    private static final float HIGHLIGHT_R_MIN = 0.78f;  // 200/255
    private static final float HIGHLIGHT_G_MIN = 0.70f;  // 178/255
    private static final float HIGHLIGHT_B_MAX = 0.40f;  // 102/255
    private static final float DARK_LUMINANCE_MAX = 0.35f; // 89/255

    private static final int DEFAULT_SAMPLE_STRIDE = 2;

    private VisualSignalDetector() {}

    public static Signals detect(@NonNull Bitmap bitmap, @NonNull Rect bbox) {
        if (bitmap == null || bitmap.isRecycled() || bbox.isEmpty()) {
            return new Signals(0f, 0f);
        }
        // Clamp the bbox to the bitmap bounds (ML Kit sometimes returns
        // boxes that extend a few pixels past the edge).
        int x0 = Math.max(0, bbox.left);
        int y0 = Math.max(0, bbox.top);
        int x1 = Math.min(bitmap.getWidth(), bbox.right);
        int y1 = Math.min(bitmap.getHeight(), bbox.bottom);
        if (x1 <= x0 || y1 <= y0) {
            return new Signals(0f, 0f);
        }
        int w = x1 - x0;
        int h = y1 - y0;
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h);

        int total = 0;
        int yellow = 0;
        int dark = 0;
        int stride = Math.max(1, DEFAULT_SAMPLE_STRIDE);
        for (int yy = 0; yy < h; yy += stride) {
            int rowOff = yy * w;
            for (int xx = 0; xx < w; xx += stride) {
                int p = pixels[rowOff + xx];
                total++;
                if (isHighlightYellow(p)) yellow++;
                if (isDark(p)) dark++;
            }
        }
        float highlightScore;
        if (total == 0) {
            highlightScore = 0f;
        } else {
            highlightScore = (float) yellow / total;
        }
        float circleScore = 0f;
        // Suppress circle detection on bboxes that are clearly wider
        // than they are tall — that's a line of text, not a number, and
        // a wide bbox will produce a false "ring" (dark pixels at the
        // left/right edges of the line + a white middle).
        boolean plausibleCircle = (w > 0 && h > 0 && ((float) w / h) >= 0.6f && ((float) w / h) <= 1.6f);
        if (!plausibleCircle) {
            Logger.d("VisualSig", "bbox=(" + x0 + "," + y0 + "," + x1 + "," + y1 + ") "
                    + "skipped circle: aspect " + String.format("%.2f", (float) w / h)
                    + " out of 0.6-1.6 range (w=" + w + ", h=" + h + ")");
        } else if (highlightScore >= 0.20f) {
            // Already highlighted — circle score is redundant.
            Logger.d("VisualSig", "bbox=(" + x0 + "," + y0 + "," + x1 + "," + y1 + ") "
                    + "skipped circle: highlight score already tripped");
        } else {
            circleScore = estimateCircleScore(pixels, w, h, dark, total);
        }
        Logger.d("VisualSig", "bbox=(" + x0 + "," + y0 + "," + x1 + "," + y1 + ") "
                + "yellow=" + yellow + "/" + total + " dark=" + dark
                + " -> " + new Signals(highlightScore, circleScore));
        return new Signals(highlightScore, circleScore);
    }

    private static float estimateCircleScore(int[] pixels, int w, int h, int darkSampled, int totalSampled) {
        // For the circle heuristic, we want to know whether the dark
        // pixels form a ring (high in the perimeter, low in the centre)
        // rather than filling the whole bbox (which would just be a
        // bold printed number). We use the same stride as the highlight
        // pass for consistency.
        int stride = Math.max(1, DEFAULT_SAMPLE_STRIDE);
        int ring = 0, core = 0;
        int ringSamples = 0, coreSamples = 0;
        for (int yy = 0; yy < h; yy += stride) {
            for (int xx = 0; xx < w; xx += stride) {
                boolean inRing = isInOuterRing(xx, yy, w, h);
                if (inRing) {
                    ringSamples++;
                    if (isDark(pixels[yy * w + xx])) ring++;
                } else {
                    coreSamples++;
                    if (isDark(pixels[yy * w + xx])) core++;
                }
            }
        }
        if (ringSamples == 0 || coreSamples == 0) return 0f;
        float ringDensity = (float) ring / ringSamples;
        float coreDensity = (float) core / coreSamples;
        if (ringDensity <= coreDensity) return 0f; // not a ring
        // Ratio: how much more dark is the ring than the core?
        // A ratio >= 2 with non-trivial ring density is a clear circle.
        float ratio = ringDensity / Math.max(0.01f, coreDensity);
        float score = clamp01((ratio - 1.0f) / 1.5f) * clamp01(ringDensity / 0.25f);
        // Suppress the circle signal on bboxes that are too small to
        // plausibly contain a circle (heuristic: needs to be > 12px tall
        // and > 30px wide; OCR box noise below that is junk).
        if (h < 12 || w < 30) score *= 0.5f;
        // Normalise using totalSampled / w/h to make a debug note.
        Logger.d("VisualSig", "  ring=" + ring + "/" + ringSamples
                + " core=" + core + "/" + coreSamples
                + " ratio=" + String.format("%.2f", ratio)
                + " raw=" + String.format("%.2f", score));
        return score;
    }

    private static boolean isInOuterRing(int x, int y, int w, int h) {
        // Outer 30% on each side is "ring", inner 40% is "core".
        int marginX = (int) (w * 0.30f);
        int marginY = (int) (h * 0.30f);
        return x < marginX || x >= w - marginX || y < marginY || y >= h - marginY;
    }

    private static boolean isHighlightYellow(int p) {
        // ARGB -> RGB
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        return rf >= HIGHLIGHT_R_MIN
                && gf >= HIGHLIGHT_G_MIN
                && bf <= HIGHLIGHT_B_MAX;
    }

    private static boolean isDark(int p) {
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        // Perceptual luminance approximation.
        float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
        return lum <= DARK_LUMINANCE_MAX;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    // Suppress an unused-import warning on Arrays in some toolchains.
    @SuppressWarnings("unused")
    private static void __touchArrays() { Arrays.asList(1, 2, 3).toString(); }
}
