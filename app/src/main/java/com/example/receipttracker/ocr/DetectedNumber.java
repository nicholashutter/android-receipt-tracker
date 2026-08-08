package com.example.receipttracker.ocr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One number that the parser found on the receipt, plus the context
 * (line text, line index, optional nearby keyword, visual-signal
 * scores from {@link VisualSignalDetector}) we need to make sense of
 * it.
 *
 * <p>Used both by the auto-detection pass and the user-driven "mark as
 * total" flow — {@code TotalVerifier} reads a list of these.</p>
 */
public final class DetectedNumber {

    /** Raw value, always positive. */
    public final double value;

    /** The line of OCR text this number was found on. */
    @NonNull public final String line;

    /** 0-based line index in the full OCR text (split on newlines). */
    public final int lineIndex;

    /**
     * If the number was found on the same line as a "subtotal", "tax",
     * "tip" or "total" keyword, that keyword is recorded here. Null
     * otherwise (e.g. line-item prices).
     */
    @Nullable public final String keyword;

    /**
     * Visual-signal scores from {@link VisualSignalDetector}. Both
     * default to 0.0 (no signal) when the bitmap wasn't supplied or
     * the bounding box couldn't be located. A non-zero highlight or
     * circle score is a strong "this is the total" indicator.
     */
    public final float highlightScore;
    public final float circleScore;

    /** Bounding box of the number within the source image, in pixels. */
    @Nullable public final android.graphics.Rect bbox;

    public DetectedNumber(double value, @NonNull String line, int lineIndex,
                          @Nullable String keyword) {
        this(value, line, lineIndex, keyword, 0f, 0f, null);
    }

    public DetectedNumber(double value, @NonNull String line, int lineIndex,
                          @Nullable String keyword, float highlightScore,
                          float circleScore, @Nullable android.graphics.Rect bbox) {
        this.value = value;
        this.line = line;
        this.lineIndex = lineIndex;
        this.keyword = keyword;
        this.highlightScore = highlightScore;
        this.circleScore = circleScore;
        this.bbox = bbox;
    }

    /** True if the user marked this number visually (highlighter or circle). */
    public boolean isVisuallyEmphasised() {
        return highlightScore >= 0.20f || circleScore >= 0.25f;
    }

    @Override
    public String toString() {
        String base = keyword == null
                ? String.valueOf(value)
                : value + " (line " + lineIndex + ", keyword=" + keyword + ")";
        if (isVisuallyEmphasised()) {
            return base + String.format(" [hl=%.2f cr=%.2f]", highlightScore, circleScore);
        }
        return base;
    }
}
