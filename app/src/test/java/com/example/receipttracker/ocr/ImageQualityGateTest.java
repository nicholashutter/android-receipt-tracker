package com.example.receipttracker.ocr;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ImageQualityGateTest {

    /** Build a 2D luminance array from row-major 1D, dimensions w x h. */
    private static int[] grid(int w, int h, int[][] rows) {
        final int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = rows[y][x];
            }
        }
        return out;
    }


    @Test
    @DisplayName("acceptable: a clear, in-focus, horizontal-text grid is acceptable")
    void shouldAcceptClearImage() {
        // 50x50 sampled grid (simulating a 1200x800 photo, downsampled to 50x50)
        // with a strong horizontal black line at y=25 — clear, no tilt, mid-bright.
        final int[][] rows = new int[50][50];
        for (int x = 0; x < 50; x++) {
            rows[25][x] = 20;
        }
        for (int y = 0; y < 50; y++) {
            if (y != 25) {
                for (int x = 0; x < 50; x++) {
                    rows[y][x] = 200;
                }
            }
        }
        final int[] lum = grid(50, 50, rows);

        final ImageQualityGate.Verdict v = ImageQualityGate.assessLuminance(
                lum, 50, 50, 1, 1200, 800);

        assertThat(v.tiltDegrees).isEqualTo(0.0);
        // Clear sharp edges → high blur score
        assertThat(v.blurScore).isGreaterThan(ImageQualityGate.BLUR_THRESHOLD);
        assertThat(v.longEdgePixels).isEqualTo(1200);
        assertThat(v.acceptable).isTrue();
    }


    @Test
    @DisplayName("blurry: a smooth gradient has a low Laplacian variance")
    void shouldDetectBlurryImage() {
        // 50x50 smooth gradient, no sharp edges — should fail the blur threshold.
        final int[][] rows = new int[50][50];
        for (int y = 0; y < 50; y++) {
            for (int x = 0; x < 50; x++) {
                rows[y][x] = (int) (x * 2 + 50);
            }
        }
        final int[] lum = grid(50, 50, rows);

        final ImageQualityGate.Verdict v = ImageQualityGate.assessLuminance(
                lum, 50, 50, 1, 50, 50);

        assertThat(v.blurScore).isLessThan(ImageQualityGate.BLUR_THRESHOLD);
        assertThat(v.issues).contains("blurry");
        assertThat(v.acceptable).isFalse();
    }


    @Test
    @DisplayName("too dark: an all-near-black image is flagged as dark")
    void shouldDetectDarkImage() {
        final int[][] rows = new int[50][50];
        for (int y = 0; y < 50; y++) {
            for (int x = 0; x < 50; x++) {
                rows[y][x] = 20;
            }
        }
        final int[] lum = grid(50, 50, rows);

        final ImageQualityGate.Verdict v = ImageQualityGate.assessLuminance(
                lum, 50, 50, 1, 50, 50);

        assertThat(v.brightness).isLessThan(ImageQualityGate.DARK_THRESHOLD);
        assertThat(v.issues).contains("too dark");
        assertThat(v.acceptable).isFalse();
    }


    @Test
    @DisplayName("too bright: an all-near-white image is flagged as too bright")
    void shouldDetectBlownOutImage() {
        final int[][] rows = new int[50][50];
        for (int y = 0; y < 50; y++) {
            for (int x = 0; x < 50; x++) {
                rows[y][x] = 250;
            }
        }
        final int[] lum = grid(50, 50, rows);

        final ImageQualityGate.Verdict v = ImageQualityGate.assessLuminance(
                lum, 50, 50, 1, 50, 50);

        assertThat(v.brightness).isGreaterThan(ImageQualityGate.BRIGHT_THRESHOLD);
        assertThat(v.issues).contains("too bright");
        assertThat(v.acceptable).isFalse();
    }


    @Test
    @DisplayName("tilted: a 45°-rotated text grid is flagged as tilted")
    void shouldDetectTiltedImage() {
        // 50x50 with a diagonal line at 45° — gradient angle should be 45°,
        // tilt (deviation from 90°) should be 45°.
        final int[][] rows = new int[50][50];
        for (int y = 0; y < 50; y++) {
            for (int x = 0; x < 50; x++) {
                rows[y][x] = 220;
            }
        }
        for (int index = 0; index < 50; index++) {
            final int y = index;
            final int x = index;
            rows[y][x] = 20;
        }
        final int[] lum = grid(50, 50, rows);

        final ImageQualityGate.Verdict v = ImageQualityGate.assessLuminance(
                lum, 50, 50, 1, 50, 50);

        assertThat(v.tiltDegrees).isGreaterThan(ImageQualityGate.TILT_THRESHOLD_DEGREES);
        assertThat(v.issues).contains("tilted");
        assertThat(v.acceptable).isFalse();
    }


    @Test
    @DisplayName("too small: a tiny original-dimension image is flagged as too small")
    void shouldDetectTooSmallImage() {
        // Sharp content but the original dimensions are too small (e.g. 200x200).
        final int[][] rows = new int[50][50];
        for (int x = 0; x < 50; x++) {
            rows[25][x] = 20;
        }
        for (int y = 0; y < 50; y++) {
            if (y != 25) {
                for (int x = 0; x < 50; x++) {
                    rows[y][x] = 200;
                }
            }
        }
        final int[] lum = grid(50, 50, rows);

        final ImageQualityGate.Verdict v = ImageQualityGate.assessLuminance(
                lum, 50, 50, 1, 200, 200);

        assertThat(v.longEdgePixels).isEqualTo(200);
        assertThat(v.issues).contains("too small");
    }


    @Test
    @DisplayName("Verdict.toString includes the key metrics")
    void shouldProduceHumanReadableVerdict() {
        final int[] lum = grid(50, 50, new int[50][50]);
        final ImageQualityGate.Verdict v = ImageQualityGate.assessLuminance(
                lum, 50, 50, 1, 1200, 800);

        final String text = v.toString();
        assertThat(text).contains("ok=");
        assertThat(text).contains("blur=");
        assertThat(text).contains("bright=");
        assertThat(text).contains("tilt=");
    }
}
