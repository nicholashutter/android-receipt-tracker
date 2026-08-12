package com.example.receipttracker.ocr;


/**
 * Semantic category of a detected number on a receipt. The auto-pick
 * heuristic uses this to filter out non-total candidates (tax
 * percentages, dates, phone numbers, auth codes) before choosing the
 * receipt total.
 *
 * <p>Classified by {@link DetectedNumber#classify()}, which combines
 * keyword matching on the line with value-shape heuristics (decimal
 * point, integer range, trailing %). Cheap enough to run on every
 * detected number during the auto-pick pass.</p>
 */
public enum NumberCategory {

    /** The grand total of the receipt. Most preferred for auto-pick. */
    TOTAL,

    /** Subtotal line. Used as a fallback when no TOTAL is labelled. */
    SUBTOTAL,

    /** A line-item price (e.g. "Milk  $3.49"). Only meaningful in aggregate. */
    LINE_ITEM,

    /** A tax line. Filtered out of total candidates by default. */
    TAX,

    /** A tip line. Filtered out of total candidates by default. */
    TIP,

    /** A discount line (negative or preceded by "discount"/"-"). Skipped. */
    DISCOUNT,

    /** A percentage value (e.g. "9.25%" tax rate). Never the total. */
    PERCENTAGE,

    /** Something that looks like a date (e.g. "12/25/24"). Never the total. */
    DATE,

    /** A phone number (e.g. "(555) 123-4567"). Never the total. */
    PHONE,

    /** A long integer that looks like an auth code, txn id, or ref. */
    AUTH_CODE,

    /** A small integer (1..9) that looks like a quantity. */
    QUANTITY,

    /** Year, version number, or other numeric label. */
    YEAR,

    /** Couldn't decide. Filtered out of total candidates by default. */
    OTHER
}
