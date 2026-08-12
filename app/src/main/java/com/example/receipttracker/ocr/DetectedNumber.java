package com.example.receipttracker.ocr;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One number that the parser found on the receipt, plus the context
 * (line text, line index, optional nearby keyword, visual-signal
 * scores from {@link VisualSignalDetector}) we need to make sense of
 * it.
 *
 * <p>Used both by the auto-detection pass and the user-driven "mark as
 * total" flow — {@code TotalVerifier} reads a list of these.</p>
 *
 * <p>Immutable: every variant is constructed with all fields at once;
 * for the visual-signal augmentation use the {@code withVisualSignals}
 * factory. There is no {@code with*} per-field because callers always
 * need to produce a new number with all the OCR fields intact plus
 * the visual scores attached.</p>
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


    public DetectedNumber(
            final double value,
            @NonNull final String line,
            final int lineIndex,
            @Nullable final String keyword) {
        this(value, line, lineIndex, keyword, 0f, 0f, null);
    }


    public DetectedNumber(
            final double value,
            @NonNull final String line,
            final int lineIndex,
            @Nullable final String keyword,
            final float highlightScore,
            final float circleScore,
            @Nullable final android.graphics.Rect bbox) {
        this.value = value;
        this.line = line;
        this.lineIndex = lineIndex;
        this.keyword = keyword;
        this.highlightScore = highlightScore;
        this.circleScore = circleScore;
        this.bbox = bbox;
    }


    /**
     * Returns a new DetectedNumber with the visual-signal scores (and
     * optional bounding box) attached. Used by the structured-OCR
     * pipeline after {@link VisualSignalDetector} has scored the
     * source image.
     */
    public DetectedNumber withVisualSignals(
            final float newHighlightScore,
            final float newCircleScore,
            @Nullable final android.graphics.Rect newBbox) {
        if (newHighlightScore == highlightScore
                && newCircleScore == circleScore
                && (newBbox == null ? bbox == null : newBbox.equals(bbox))) {
            return this;
        }
        return new DetectedNumber(value, line, lineIndex, keyword,
                newHighlightScore, newCircleScore, newBbox);
    }


    /** True if the user marked this number visually (highlighter or circle). */
    public boolean isVisuallyEmphasised() {
        return highlightScore >= 0.20f || circleScore >= 0.25f;
    }


    // ---------- category classifier ----------
    //
    // The classifier is rule-based: a keyword on the line (or nearby)
    // picks the category, else a value-shape check (decimal, integer,
    // size, trailing '%') picks one. Designed to be cheap (no ML, just
    // regex + string ops) so it runs on every DetectedNumber during
    // the auto-pick pass without slowing the editor.

    private static final String KEYWORD_TOTAL =
            "total|grand total|amount due|balance due|amount|sum|to pay|net total|rtail total|etail total";

    private static final String KEYWORD_SUBTOTAL =
            "subtotal|sub total|sub-total";

    private static final String KEYWORD_TAX =
            "tax|vat|gst|hst|sales tax";

    private static final String KEYWORD_TIP =
            "tip|gratuity";

    private static final String KEYWORD_DISCOUNT =
            "discount|savings|off|coupon|promo";

    private static final String KEYWORD_PERCENT =
            "%|percent|rate";

    private static final String KEYWORD_DATE =
            "date|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|mon|tue|wed|thu|fri|sat|sun";

    private static final String KEYWORD_PHONE =
            "tel|phone|fax|call";

    private static final String KEYWORD_AUTH =
            "approval|auth|ref|txn|transaction|order|invoice|receipt #|trans #|check #|confirmation|conf #|code|host|terminal|exp|expir|cvv|cvc|account|acct|card";

    private static final String KEYWORD_QUANTITY =
            "qty|quantity|x |\\bx\\.|ea|each";

    private static final String KEYWORD_YEAR =
            "version|ver\\.|v\\.|build|release|update|copyright|©";

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            ".*(\\(\\d{3}\\)\\s*\\d{3}[\\s\\-]?\\d{4}|\\d{3}[\\s\\-]\\d{3}[\\s\\-]\\d{4}).*");

    private static final Pattern DATE_PATTERN = Pattern.compile(
            ".*\\b\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}\\b.*");

    private static final Pattern TIME_PATTERN = Pattern.compile(
            ".*\\b\\d{1,2}:\\d{2}(:\\d{2})?(\\s*[ap]m)?\\b.*");

    private static final Pattern PERCENT_PATTERN = Pattern.compile(".*\\d\\s*%.*");

    private static final Pattern NEGATIVE_OR_DISCOUNT = Pattern.compile(
            ".*(\\-|−|\\(\\s*\\d).*");

    private static final int QUANTITY_MIN = 1;
    private static final int QUANTITY_MAX = 9;
    private static final int AUTH_CODE_MIN = 100;
    private static final int AUTH_CODE_MAX_EXCLUSIVE = 10_000_000;
    private static final int YEAR_MIN = 1900;
    private static final int YEAR_MAX_EXCLUSIVE = 2100;

    /**
     * Classifies this number into a {@link NumberCategory} based on
     * the keyword on its line (or a nearby line, propagated by the
     * parser) plus the value shape and the surrounding line text.
     *
     * <p>The order of checks matters: keywords win over value shape,
     * and value shape wins over a generic {@code OTHER} fallback. The
     * result drives the auto-pick filter so a tax percentage like
     * "9.25%" never gets chosen as the receipt total.</p>
     */
    @NonNull
    public NumberCategory classify() {
        final String loweredLine = (line == null) ? "" : line.toLowerCase(Locale.US);
        final String loweredKeyword = (keyword == null) ? "" : keyword.toLowerCase(Locale.US);
        final String combined = loweredLine + " " + loweredKeyword;
        final boolean isInteger = value == Math.floor(value);

        // Keyword-driven classification (highest priority).
        // PERCENTAGE is checked before TAX because "Tax  9.25%" is a tax
        // RATE, not a tax amount — the value isn't money. Putting PERCENTAGE
        // first routes the value into the rate bucket and out of the total
        // candidate pool.
        if (matchesAny(combined, KEYWORD_PERCENT) || PERCENT_PATTERN.matcher(loweredLine).matches()) {
            return NumberCategory.PERCENTAGE;
        }
        if (matchesAny(combined, KEYWORD_TOTAL)) return NumberCategory.TOTAL;
        if (matchesAny(combined, KEYWORD_SUBTOTAL)) return NumberCategory.SUBTOTAL;
        if (matchesAny(combined, KEYWORD_TAX)) return NumberCategory.TAX;
        if (matchesAny(combined, KEYWORD_TIP)) return NumberCategory.TIP;
        if (matchesAny(combined, KEYWORD_DISCOUNT)) return NumberCategory.DISCOUNT;
        if (matchesAny(combined, KEYWORD_DATE)
                || DATE_PATTERN.matcher(loweredLine).matches()) {
            return NumberCategory.DATE;
        }
        if (matchesAny(combined, KEYWORD_PHONE)
                || PHONE_PATTERN.matcher(loweredLine).matches()) {
            return NumberCategory.PHONE;
        }
        if (matchesAny(combined, KEYWORD_AUTH)) return NumberCategory.AUTH_CODE;
        if (matchesAny(combined, KEYWORD_QUANTITY)) return NumberCategory.QUANTITY;
        if (matchesAny(combined, KEYWORD_YEAR)) return NumberCategory.YEAR;

        // Value-shape classification (no keyword present).
        if (NEGATIVE_OR_DISCOUNT.matcher(loweredLine).matches()) {
            return NumberCategory.DISCOUNT;
        }
        if (PERCENT_PATTERN.matcher(loweredLine).matches()) {
            return NumberCategory.PERCENTAGE;
        }
        // Only fire the integer-shape checks (AUTH/QUANTITY/YEAR) when the
        // line text doesn't carry a decimal point. If the OCR printed the
        // number as "8.00" or "50.00" the value parses to a whole-number
        // double but was originally a money amount, not a code/quantity/
        // year. Skipping these checks in that case is the difference
        // between the auto-pick picking $50 and picking "year 50".
        final boolean lineLooksDecimal = loweredLine.matches(".*\\d+\\.\\d+.*");
        if (isInteger && !lineLooksDecimal) {
            if (value >= AUTH_CODE_MIN && value < AUTH_CODE_MAX_EXCLUSIVE) {
                return NumberCategory.AUTH_CODE;
            }
            if (value >= QUANTITY_MIN && value <= QUANTITY_MAX) {
                return NumberCategory.QUANTITY;
            }
            if (value >= YEAR_MIN && value < YEAR_MAX_EXCLUSIVE) {
                return NumberCategory.YEAR;
            }
        }
        if (DATE_PATTERN.matcher(loweredLine).matches()) {
            return NumberCategory.DATE;
        }
        if (PHONE_PATTERN.matcher(loweredLine).matches()) {
            return NumberCategory.PHONE;
        }
        if (TIME_PATTERN.matcher(loweredLine).matches()) {
            return NumberCategory.OTHER;
        }

        // No keyword or shape match. Treat as a line-item price: a bare
        // positive value on a receipt is a money amount far more often
        // than it is "other" (and the few integer categories we cared
        // about — AUTH, QUANTITY, YEAR — are already excluded above so
        // anything left here is a plausible dollar amount). This is what
        // makes the auto-pick recoverable when the OCR reads "$50" as
        // 50.00 — we have to treat that as a line-item amount, not
        // garbage.
        return NumberCategory.LINE_ITEM;
    }

    private static boolean matchesAny(String haystack, String regexAlternation) {
        if (haystack.isEmpty()) return false;
        // Word-boundary anchored regex alternation: matches whole words only.
        final String anchored = "(?i)(?<![a-z0-9])(" + regexAlternation + ")(?![a-z0-9])";
        return Pattern.compile(anchored).matcher(haystack).find();
    }


    @NonNull
    @Override
    public String toString() {
        final String base;
        if (keyword == null) {
            base = String.valueOf(value);
        } else {
            base = value + " (line " + lineIndex + ", keyword=" + keyword + ")";
        }
        if (isVisuallyEmphasised()) {
            return base + String.format(" [hl=%.2f cr=%.2f]", highlightScore, circleScore);
        }
        return base;
    }
}
