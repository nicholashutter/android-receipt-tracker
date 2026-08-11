package com.example.receipttracker.ocr;


import android.graphics.Bitmap;

import androidx.annotation.NonNull;


import java.util.ArrayList;

import java.util.List;


/**
 * Pre-OCR image quality check. Runs four simple analyses on the photo
 * before we spend 4 seconds running ML Kit on it:
 * <ul>
 *   <li>blur — Laplacian variance, low = motion-blurred</li>
 *   <li>brightness — mean luma, too low = dark photo, too high = blown out</li>
 *   <li>tilt — dominant gradient angle, deviation from 90° (perpendicular
 *       to horizontal text) = receipt is rotated</li>
 *   <li>size — long-edge pixel count, too small = OCR will be poor</li>
 * </ul>
 *
 * <p>Returns a {@link Verdict} with {@code acceptable = true} only if all
 * four pass. The caller should show a "this might not scan well — try
 * again?" toast before launching the OCR when {@code acceptable = false}.
 * The gate never blocks; a poor-quality photo is still passed through
 * to the OCR in case the user wants to try anyway.</p>
 *
 * <p>Performance: the bitmap is sampled down to at most
 * {@value #MAX_SAMPLE_DIM}x{@value #MAX_SAMPLE_DIM} pixels before analysis,
 * so a 4000x3000 phone photo takes ~10-20ms to assess.</p>
 */
public final class ImageQualityGate {

    /** Laplacian variance below this = too blurry. */
    public static final double BLUR_THRESHOLD = 80.0;


    /** Mean luma (0..1) below this = too dark. */
    public static final double DARK_THRESHOLD = 0.15;


    /** Mean luma (0..1) above this = too bright (blown out). */
    public static final double BRIGHT_THRESHOLD = 0.92;


    /** Deviation of dominant gradient angle from 90° above this = too tilted. */
    public static final double TILT_THRESHOLD_DEGREES = 8.0;


    /** Long edge below this pixel count = too small. */
    public static final int MIN_LONG_EDGE_PIXELS = 600;


    /** Sample down to at most this many pixels on the long edge. */
    static final int MAX_SAMPLE_DIM = 256;


    private static final double RAD_TO_DEG = 180.0 / Math.PI;


    private ImageQualityGate() {}


    public static final class Verdict {

        public final boolean acceptable;

        public final List<String> issues;

        public final double blurScore;

        public final double brightness;

        public final double tiltDegrees;

        public final int longEdgePixels;


        public Verdict(final boolean acceptable,
                       final List<String> issues,
                       final double blurScore,
                       final double brightness,
                       final double tiltDegrees,
                       final int longEdgePixels) {
            this.acceptable = acceptable;
            this.issues = issues;
            this.blurScore = blurScore;
            this.brightness = brightness;
            this.tiltDegrees = tiltDegrees;
            this.longEdgePixels = longEdgePixels;
        }


        @NonNull
        @Override
        public String toString() {
            return "Verdict{ok=" + acceptable
                    + " issues=" + issues
                    + " blur=" + blurScore
                    + " bright=" + brightness
                    + " tilt=" + tiltDegrees + "deg"
                    + " longEdge=" + longEdgePixels + "}";
        }
    }


    /**
     * Runs the four quality checks on a real Android bitmap. Samples down
     * internally to keep the work under 20ms on a modern phone.
     */
    public static Verdict assess(@NonNull final Bitmap bitmap) {
        final int width = bitmap.getWidth();

        final int height = bitmap.getHeight();

        if (width <= 0 || height <= 0) {
            return new Verdict(false, List.of("empty"), 0.0, 0.0, 0.0, 0);
        }

        final int longEdge = Math.max(width, height);

        final int sampleStride = Math.max(1, longEdge / MAX_SAMPLE_DIM);

        final int sampledWidth = width / sampleStride;

        final int sampledHeight = height / sampleStride;

        if (sampledWidth < 3 || sampledHeight < 3) {
            return new Verdict(false, List.of("too small"), 0.0, 0.0, 0.0, longEdge);
        }

        final int[] argb = new int[sampledWidth * sampledHeight];

        bitmap.getPixels(argb, 0, sampledWidth, 0, 0, sampledWidth, sampledHeight);

        final int[] luminance = new int[argb.length];

        for (int index = 0; index < argb.length; index++) {
            luminance[index] = lumaFromArgb(argb[index]);
        }

        return assessLuminance(luminance, sampledWidth, sampledHeight, sampleStride, width, height);
    }


    /**
     * Pure-Java testable variant. Takes a pre-computed luminance array
     * (one int per pixel, 0..255), the sampled dimensions, the sample
     * stride, and the original (un-sampled) dimensions.
     */
    static Verdict assessLuminance(final int[] luminance,
                                   final int sampledWidth,
                                   final int sampledHeight,
                                   final int sampleStride,
                                   final int originalWidth,
                                   final int originalHeight) {
        final int longEdge = Math.max(originalWidth, originalHeight);

        final double brightnessRaw = (double) mean(luminance) / 255.0;

        final double blurScore = laplacianVariance(luminance, sampledWidth, sampledHeight);

        final double tiltDegrees = dominantTiltDegrees(luminance, sampledWidth, sampledHeight);

        final List<String> issues = new ArrayList<>();
        if (blurScore < BLUR_THRESHOLD) {
            issues.add("blurry");
        }

        if (brightnessRaw < DARK_THRESHOLD) {
            issues.add("too dark");
        }

        if (brightnessRaw > BRIGHT_THRESHOLD) {
            issues.add("too bright");
        }

        if (tiltDegrees > TILT_THRESHOLD_DEGREES) {
            issues.add("tilted");
        }

        if (longEdge < MIN_LONG_EDGE_PIXELS) {
            issues.add("too small");
        }

        return new Verdict(
                issues.isEmpty(),
                issues,
                blurScore,
                brightnessRaw,
                tiltDegrees,
                longEdge);
    }


    private static int lumaFromArgb(final int argb) {
        final int r = (argb >> 16) & 0xFF;

        final int g = (argb >> 8) & 0xFF;

        final int b = argb & 0xFF;

        // BT.601 luma, integer arithmetic, 0..255
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }


    private static long mean(final int[] values) {
        long sum = 0;

        for (int value : values) {
            sum += value;
        }

        if (values.length == 0) {
            return 0;
        }

        return sum / values.length;
    }


    /**
     * Laplacian variance. A 3x3 Laplacian kernel applied at every
     * interior pixel; we return the variance of the result. Lower =
     * blurrier. Threshold ~80 distinguishes in-focus from motion-blurred
     * for typical receipt photos.
     */
    static double laplacianVariance(final int[] lum, final int width, final int height) {
        if (width < 3 || height < 3) {
            return 0.0;
        }

        double sum = 0.0;

        double sumSquared = 0.0;

        int count = 0;

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                final int center = lum[y * width + x] * -4;

                final int top = lum[(y - 1) * width + x];

                final int bottom = lum[(y + 1) * width + x];

                final int left = lum[y * width + (x - 1)];

                final int right = lum[y * width + (x + 1)];

                final int laplacian = center + top + bottom + left + right;

                sum += laplacian;

                sumSquared += (double) laplacian * laplacian;

                count++;
            }
        }

        if (count == 0) {
            return 0.0;
        }

        final double meanValue = sum / count;

        return (sumSquared / count) - (meanValue * meanValue);
    }


    /**
     * Estimates the dominant edge angle in degrees and returns the
     * deviation from 90° (which is "edges perpendicular to horizontal
     * text" = an unrotated receipt).
     *
     * <p>Approach: Sobel gradients at every interior pixel, weighted by
     * magnitude, histogrammed into 1° bins from 0 to 90 (we use
     * atan2(|gy|, |gx|) so the result is always in [0, 90]). The
     * dominant bin's deviation from 90° is the tilt.</p>
     */
    static double dominantTiltDegrees(final int[] lum, final int width, final int height) {
        if (width < 3 || height < 3) {
            return 0.0;
        }

        // 91 bins, one per degree 0..90
        final long[] histogram = new long[91];

        long totalWeight = 0;

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                final int topLeft = lum[(y - 1) * width + (x - 1)];

                final int topCenter = lum[(y - 1) * width + x];

                final int topRight = lum[(y - 1) * width + (x + 1)];

                final int middleLeft = lum[y * width + (x - 1)];

                final int middleRight = lum[y * width + (x + 1)];

                final int bottomLeft = lum[(y + 1) * width + (x - 1)];

                final int bottomCenter = lum[(y + 1) * width + x];

                final int bottomRight = lum[(y + 1) * width + (x + 1)];

                final int gx = -topLeft - 2 * middleLeft - bottomLeft + topRight + 2 * middleRight + bottomRight;

                final int gy = -topLeft - 2 * topCenter - topRight + bottomLeft + 2 * bottomCenter + bottomRight;

                final int magnitude = Math.abs(gx) + Math.abs(gy);

                if (magnitude < 30) {
                    continue;
                }

                final double angleRad = Math.atan2((double) Math.abs(gy), (double) Math.abs(gx));

                final int angleDeg = (int) Math.round(angleRad * RAD_TO_DEG);

                final int bin = Math.min(90, Math.max(0, angleDeg));

                histogram[bin] += magnitude;

                totalWeight += magnitude;
            }
        }

        if (totalWeight == 0) {
            return 0.0;
        }

        // Find the dominant bin
        int bestBin = 0;

        long bestCount = 0;

        for (int bin = 0; bin < histogram.length; bin++) {
            if (histogram[bin] > bestCount) {
                bestCount = histogram[bin];
                bestBin = bin;
            }
        }

        // Deviation from 90° is the tilt. 0° = vertical edges (rare in
        // receipts), 90° = horizontal edges (text on a straight receipt).
        return Math.abs(90.0 - bestBin);
    }
}
