package com.example.receipttracker.ocr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One number that the parser found on the receipt, plus the context
 * (line text, line index, optional nearby keyword) we need to make
 * sense of it.
 *
 * Used both by the auto-detection pass and the user-driven "mark as
 * total" flow — {@link TotalVerifier} reads a list of these.
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

    public DetectedNumber(double value, @NonNull String line, int lineIndex, @Nullable String keyword) {
        this.value = value;
        this.line = line;
        this.lineIndex = lineIndex;
        this.keyword = keyword;
    }

    @Override
    public String toString() {
        if (keyword == null) return String.valueOf(value);
        return value + " (line " + lineIndex + ", keyword=" + keyword + ")";
    }
}
