package com.example.receipttracker.ocr;


import android.graphics.Bitmap;

import android.graphics.Rect;


import androidx.annotation.NonNull;


import com.example.receipttracker.log.Logger;


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

    private static final String LOG_TAG = "VisualSig";

    private static final float HIGHLIGHT_R_MIN = 0.78f;  // 200/255
    private static final float HIGHLIGHT_G_MIN = 0.70f;  // 178/255
    private static final float HIGHLIGHT_B_MAX = 0.40f;  // 102/255
    private static final float DARK_LUMINANCE_MAX = 0.35f; // 89/255

    private static final int DEFAULT_SAMPLE_STRIDE = 2;

    private static final float RING_MARGIN_FRACTION = 0.30f;
    private static final float ASPECT_MIN = 0.6f;
    private static final float ASPECT_MAX = 1.6f;
    private static final int SMALL_BBOX_MAX_WIDTH = 30;
    private static final int SMALL_BBOX_MAX_HEIGHT = 12;
    private static final float SMALL_BBOX_SCORE_FACTOR = 0.5f;
    private static final float DENSITY_MIN_FOR_RATIO = 0.01f;
    private static final float RING_DENSITY_TARGET = 0.25f;
    private static final float RATIO_ABOVE_ONE_NORMALISER = 1.5f;
    private static final float RING_DENSITY_FLOOR = 0.0f;
    private static final int ARGB_ALPHA_SHIFT = 24;
    private static final int ARGB_RED_SHIFT = 16;
    private static final int ARGB_GREEN_SHIFT = 8;
    private static final int ARGB_CHANNEL_MASK = 0xFF;
    private static final int PIXEL_VALUE_MAX = 255;
    private static final float LUMA_RED_WEIGHT = 0.299f;
    private static final float LUMA_GREEN_WEIGHT = 0.587f;
    private static final float LUMA_BLUE_WEIGHT = 0.114f;


    private VisualSignalDetector() {}


    /** A detected visual emphasis on a number's bounding box. */
    public static final class Signals {
        public final float highlightScore;
        public final float circleScore;

        public Signals(final float highlightScore, final float circleScore) {
            this.highlightScore = clamp01(highlightScore);
            this.circleScore = clamp01(circleScore);
        }

        /** Either signal tripped its threshold. */
        public boolean isEmphasised() {
            return highlightScore >= 0.20f || circleScore >= 0.25f;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format("highlight=%.2f circle=%.2f", highlightScore, circleScore);
        }
    }


    public static Signals detect(@NonNull Bitmap bitmap, @NonNull Rect bbox) {
        if (bitmap == null || bitmap.isRecycled() || bbox.isEmpty()) {
            return new Signals(0f, 0f);
        }

        // Clamp the bbox to the bitmap bounds (ML Kit sometimes
        // returns boxes that extend a few pixels past the edge).
        final int x0 = Math.max(0, bbox.left);
        final int y0 = Math.max(0, bbox.top);
        final int x1 = Math.min(bitmap.getWidth(), bbox.right);
        final int y1 = Math.min(bitmap.getHeight(), bbox.bottom);
        if (x1 <= x0 || y1 <= y0) {
            return new Signals(0f, 0f);
        }

        final int width = x1 - x0;
        final int height = y1 - y0;
        final int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, x0, y0, width, height);

        final SampleStats stats = samplePixels(pixels, width, height);
        final float highlightScore = (stats.totalSamples == 0)
                ? 0f
                : (float) stats.yellowSamples / stats.totalSamples;

        final Signals signals = computeCircleScore(pixels, width, height,
                stats.darkSamples, stats.totalSamples, x0, y0, x1, y1, highlightScore);

        Logger.d(LOG_TAG, "bbox=(" + x0 + "," + y0 + "," + x1 + "," + y1 + ") "
                + "yellow=" + stats.yellowSamples + "/" + stats.totalSamples
                + " dark=" + stats.darkSamples
                + " -> " + new Signals(highlightScore, signals.circleScore));
        return new Signals(highlightScore, signals.circleScore);
    }


    /** Sampler output: how many pixels we looked at and which were yellow/dark. */
    private static final class SampleStats {
        final int totalSamples;
        final int yellowSamples;
        final int darkSamples;

        SampleStats(int totalSamples, int yellowSamples, int darkSamples) {
            this.totalSamples = totalSamples;
            this.yellowSamples = yellowSamples;
            this.darkSamples = darkSamples;
        }
    }


    private static SampleStats samplePixels(int[] pixels, int width, int height) {
        int totalSamples = 0;
        int yellowSamples = 0;
        int darkSamples = 0;
        final int stride = Math.max(1, DEFAULT_SAMPLE_STRIDE);
        for (int y = 0; y < height; y += stride) {
            final int rowOffset = y * width;
            for (int x = 0; x < width; x += stride) {
                final int pixel = pixels[rowOffset + x];
                totalSamples++;
                if (isHighlightYellow(pixel)) yellowSamples++;
                if (isDark(pixel)) darkSamples++;
            }
        }
        return new SampleStats(totalSamples, yellowSamples, darkSamples);
    }


    /**
     * Computes the circle score; or returns a zero-score Signals if
     * the bbox is the wrong shape for a circle, or the highlight
     * signal already tripped (in which case circle is redundant).
     */
    private static Signals computeCircleScore(int[] pixels, int width, int height,
                                              int darkSamples, int totalSamples,
                                              int x0, int y0, int x1, int y1,
                                              float highlightScore) {
        final float aspectRatio = (height > 0) ? (float) width / height : 0f;
        final boolean plausibleCircle = aspectRatio >= ASPECT_MIN && aspectRatio <= ASPECT_MAX;

        if (!plausibleCircle) {
            Logger.d(LOG_TAG, "bbox=(" + x0 + "," + y0 + "," + x1 + "," + y1 + ") "
                    + "skipped circle: aspect " + String.format("%.2f", aspectRatio)
                    + " out of " + ASPECT_MIN + "-" + ASPECT_MAX + " range (w=" + width + ", h=" + height + ")");
            return new Signals(0f, 0f);
        }
        if (highlightScore >= 0.20f) {
            Logger.d(LOG_TAG, "bbox=(" + x0 + "," + y0 + "," + x1 + "," + y1 + ") "
                    + "skipped circle: highlight score already tripped");
            return new Signals(0f, 0f);
        }
        final float circleScore = estimateCircleScore(pixels, width, height, darkSamples, totalSamples);
        return new Signals(0f, circleScore);
    }


    /**
     * For the circle heuristic, we want to know whether the dark
     * pixels form a ring (high in the perimeter, low in the centre)
     * rather than filling the whole bbox (which would just be a bold
     * printed number). We use the same stride as the highlight pass
     * for consistency.
     */
    private static float estimateCircleScore(int[] pixels, int width, int height,
                                             int darkSampled, int totalSampled) {
        int ring = 0;
        int core = 0;
        int ringSamples = 0;
        int coreSamples = 0;
        final int stride = Math.max(1, DEFAULT_SAMPLE_STRIDE);
        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                final boolean inRing = isInOuterRing(x, y, width, height);
                if (inRing) {
                    ringSamples++;
                    if (isDark(pixels[y * width + x])) ring++;
                } else {
                    coreSamples++;
                    if (isDark(pixels[y * width + x])) core++;
                }
            }
        }
        if (ringSamples == 0 || coreSamples == 0) return 0f;

        final float ringDensity = (float) ring / ringSamples;
        final float coreDensity = (float) core / coreSamples;
        if (ringDensity <= coreDensity) return 0f; // not a ring

        // Ratio: how much more dark is the ring than the core?
        // A ratio >= 2 with non-trivial ring density is a clear circle.
        final float ratio = ringDensity / Math.max(DENSITY_MIN_FOR_RATIO, coreDensity);
        float score = clamp01((ratio - 1.0f) / RATIO_ABOVE_ONE_NORMALISER)
                * clamp01(ringDensity / RING_DENSITY_TARGET);

        // Suppress the circle signal on bboxes that are too small to
        // plausibly contain a circle (heuristic: needs to be > 12px
        // tall and > 30px wide; OCR box noise below that is junk).
        if (height < SMALL_BBOX_MAX_HEIGHT || width < SMALL_BBOX_MAX_WIDTH) {
            score *= SMALL_BBOX_SCORE_FACTOR;
        }

        Logger.d(LOG_TAG, "  ring=" + ring + "/" + ringSamples
                + " core=" + core + "/" + coreSamples
                + " ratio=" + String.format("%.2f", ratio)
                + " raw=" + String.format("%.2f", score));
        return Math.max(RING_DENSITY_FLOOR, score);
    }


    private static boolean isInOuterRing(int x, int y, int width, int height) {
        // Outer 30% on each side is "ring", inner 40% is "core".
        final int marginX = (int) (width * RING_MARGIN_FRACTION);
        final int marginY = (int) (height * RING_MARGIN_FRACTION);
        return x < marginX || x >= width - marginX || y < marginY || y >= height - marginY;
    }


    private static boolean isHighlightYellow(int pixel) {
        // ARGB -> RGB
        final int r = (pixel >> ARGB_RED_SHIFT) & ARGB_CHANNEL_MASK;
        final int g = (pixel >> ARGB_GREEN_SHIFT) & ARGB_CHANNEL_MASK;
        final int b = pixel & ARGB_CHANNEL_MASK;
        final float redFraction = r / (float) PIXEL_VALUE_MAX;
        final float greenFraction = g / (float) PIXEL_VALUE_MAX;
        final float blueFraction = b / (float) PIXEL_VALUE_MAX;
        return redFraction >= HIGHLIGHT_R_MIN
                && greenFraction >= HIGHLIGHT_G_MIN
                && blueFraction <= HIGHLIGHT_B_MAX;
    }


    private static boolean isDark(int pixel) {
        final int r = (pixel >> ARGB_RED_SHIFT) & ARGB_CHANNEL_MASK;
        final int g = (pixel >> ARGB_GREEN_SHIFT) & ARGB_CHANNEL_MASK;
        final int b = pixel & ARGB_CHANNEL_MASK;

        // Perceptual luminance approximation.
        final float luminance = (LUMA_RED_WEIGHT * r + LUMA_GREEN_WEIGHT * g + LUMA_BLUE_WEIGHT * b)
                / (float) PIXEL_VALUE_MAX;
        return luminance <= DARK_LUMINANCE_MAX;
    }


    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
