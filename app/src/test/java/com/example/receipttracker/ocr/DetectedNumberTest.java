package com.example.receipttracker.ocr;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class DetectedNumberTest {

    @Test
    @DisplayName("four-arg constructor defaults visual scores to 0 and bbox to null")
    void shouldDefaultVisualScoresAndBbox() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 3, "total");

        assertThat(number.value).isEqualTo(47.83);
        assertThat(number.line).isEqualTo("TOTAL  47.83");
        assertThat(number.lineIndex).isEqualTo(3);
        assertThat(number.keyword).isEqualTo("total");
        assertThat(number.highlightScore).isEqualTo(0.0f);
        assertThat(number.circleScore).isEqualTo(0.0f);
        assertThat(number.bbox).isNull();
    }


    @Test
    @DisplayName("seven-arg constructor accepts all visual-signal fields")
    void shouldAcceptAllVisualSignalFields() {
        final android.graphics.Rect bbox = new android.graphics.Rect(0, 0, 10, 10);

        final DetectedNumber number = new DetectedNumber(
                12.50, "Tip  12.50", 5, "tip", 0.6f, 0.4f, bbox);

        assertThat(number.highlightScore).isEqualTo(0.6f);
        assertThat(number.circleScore).isEqualTo(0.4f);
        assertThat(number.bbox).isSameAs(bbox);
    }


    @Test
    @DisplayName("isVisuallyEmphasised: both below threshold returns false")
    void shouldNotBeEmphasisedWhenBothBelowThreshold() {
        final DetectedNumber number = new DetectedNumber(1.0, "x", 0, null, 0.1f, 0.1f, null);

        assertThat(number.isVisuallyEmphasised()).isFalse();
    }


    @Test
    @DisplayName("isVisuallyEmphasised: highlight at exactly 0.20 returns true (inclusive threshold)")
    void shouldBeEmphasisedAtHighlightThreshold() {
        final DetectedNumber number = new DetectedNumber(1.0, "x", 0, null, 0.20f, 0.0f, null);

        assertThat(number.isVisuallyEmphasised()).isTrue();
    }


    @Test
    @DisplayName("isVisuallyEmphasised: circle at exactly 0.25 returns true (inclusive threshold)")
    void shouldBeEmphasisedAtCircleThreshold() {
        final DetectedNumber number = new DetectedNumber(1.0, "x", 0, null, 0.0f, 0.25f, null);

        assertThat(number.isVisuallyEmphasised()).isTrue();
    }


    @Test
    @DisplayName("isVisuallyEmphasised: highlight below threshold but circle above returns true")
    void shouldBeEmphasisedOnCircleOnly() {
        final DetectedNumber number = new DetectedNumber(1.0, "x", 0, null, 0.0f, 0.5f, null);

        assertThat(number.isVisuallyEmphasised()).isTrue();
    }


    @Test
    @DisplayName("isVisuallyEmphasised: circle below threshold but highlight above returns true")
    void shouldBeEmphasisedOnHighlightOnly() {
        final DetectedNumber number = new DetectedNumber(1.0, "x", 0, null, 0.4f, 0.0f, null);

        assertThat(number.isVisuallyEmphasised()).isTrue();
    }


    @Test
    @DisplayName("withVisualSignals with same values returns the same instance (no-op)")
    void shouldReturnSameInstanceWhenVisualSignalsUnchanged() {
        final DetectedNumber original = new DetectedNumber(1.0, "x", 0, null, 0.3f, 0.3f, null);

        final DetectedNumber updated = original.withVisualSignals(0.3f, 0.3f, null);

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withVisualSignals with new highlight score returns a new instance with updated score")
    void shouldReturnNewInstanceOnHighlightChange() {
        final DetectedNumber original = new DetectedNumber(1.0, "x", 0, null, 0.0f, 0.0f, null);

        final DetectedNumber updated = original.withVisualSignals(0.5f, 0.0f, null);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.highlightScore).isEqualTo(0.5f);
        assertThat(updated.circleScore).isEqualTo(0.0f);
        assertThat(original.highlightScore).isEqualTo(0.0f);
    }


    @Test
    @DisplayName("withVisualSignals preserves value, line, lineIndex, and keyword")
    void shouldPreserveBaseFieldsOnVisualSignalChange() {
        final DetectedNumber original = new DetectedNumber(12.34, "TOTAL  12.34", 7, "total");

        final DetectedNumber updated = original.withVisualSignals(0.5f, 0.0f, null);

        assertThat(updated.value).isEqualTo(12.34);
        assertThat(updated.line).isEqualTo("TOTAL  12.34");
        assertThat(updated.lineIndex).isEqualTo(7);
        assertThat(updated.keyword).isEqualTo("total");
    }


    @Test
    @DisplayName("toString includes the value and line index when no keyword is present")
    void shouldFormatToStringWithoutKeyword() {
        final DetectedNumber number = new DetectedNumber(5.99, "Bananas  5.99", 4, null);

        final String text = number.toString();

        assertThat(text).contains("5.99");
        assertThat(text).doesNotContain("keyword=");
    }


    @Test
    @DisplayName("toString includes the keyword when present")
    void shouldFormatToStringWithKeyword() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 3, "total");

        final String text = number.toString();

        assertThat(text).contains("47.83");
        assertThat(text).contains("keyword=total");
    }


    @Test
    @DisplayName("toString includes visual-signal suffix when emphasised")
    void shouldFormatToStringWithVisualSuffix() {
        final DetectedNumber number = new DetectedNumber(12.34, "TOTAL  12.34", 3, "total", 0.5f, 0.0f, null);

        final String text = number.toString();

        assertThat(text).contains("hl=");
        assertThat(text).contains("cr=");
    }


    // ---------- classify() ----------

    @Test
    @DisplayName("classify returns TOTAL on a TOTAL keyword line")
    void shouldClassifyAsTotal() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 3, "total");

        assertThat(number.classify()).isEqualTo(NumberCategory.TOTAL);
    }


    @Test
    @DisplayName("classify returns SUBTOTAL on a Subtotal keyword line")
    void shouldClassifyAsSubtotal() {
        final DetectedNumber number = new DetectedNumber(40.00, "Subtotal  40.00", 2, "subtotal");

        assertThat(number.classify()).isEqualTo(NumberCategory.SUBTOTAL);
    }


    @Test
    @DisplayName("classify returns TAX on a Tax keyword line")
    void shouldClassifyAsTax() {
        final DetectedNumber number = new DetectedNumber(2.40, "Tax  2.40", 4, "tax");

        assertThat(number.classify()).isEqualTo(NumberCategory.TAX);
    }


    @Test
    @DisplayName("classify returns PERCENTAGE on a trailing-percent line")
    void shouldClassifyTrailingPercentAsPercentage() {
        final DetectedNumber number = new DetectedNumber(9.25, "Tax  9.25%", 1, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.PERCENTAGE);
    }


    @Test
    @DisplayName("classify returns LINE_ITEM for a bare decimal amount with no keyword")
    void shouldClassifyBareDecimalAsLineItem() {
        final DetectedNumber number = new DetectedNumber(5.99, "Milk  5.99", 0, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.LINE_ITEM);
    }


    @Test
    @DisplayName("classify returns LINE_ITEM for a round decimal amount like 50.00 or 8.00")
    void shouldClassifyRoundDecimalAsLineItem() {
        // The parser stores 50.00 as the double 50.0; without the line-text
        // decimal hint, the integer-shape fallbacks would misclassify 8.00 as
        // a quantity and 50.00 as auth code. The line-text check rescues
        // both into LINE_ITEM.
        final DetectedNumber fifty = new DetectedNumber(50.00, "huge  50.00", 0, null);
        final DetectedNumber eight = new DetectedNumber(8.00, "mid  8.00", 0, null);

        assertThat(fifty.classify()).isEqualTo(NumberCategory.LINE_ITEM);
        assertThat(eight.classify()).isEqualTo(NumberCategory.LINE_ITEM);
    }


    @Test
    @DisplayName("classify returns DATE on a date-shaped line")
    void shouldClassifyDateAsDate() {
        final DetectedNumber number = new DetectedNumber(12.25, "Date  12/25/24", 0, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.DATE);
    }


    @Test
    @DisplayName("classify returns PHONE on a phone-shaped line")
    void shouldClassifyPhoneAsPhone() {
        final DetectedNumber number = new DetectedNumber(5551234.0, "Tel  (555) 123-4567", 0, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.PHONE);
    }


    @Test
    @DisplayName("classify returns AUTH_CODE on a long-integer auth-code line")
    void shouldClassifyAuthCodeAsAuthCode() {
        final DetectedNumber number = new DetectedNumber(348332.0, "Txn ID  348332", 0, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.AUTH_CODE);
    }


    @Test
    @DisplayName("classify returns QUANTITY on a small integer with a qty keyword")
    void shouldClassifyQuantityAsQuantity() {
        final DetectedNumber number = new DetectedNumber(3.0, "Qty  3", 0, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.QUANTITY);
    }


    @Test
    @DisplayName("classify returns LINE_ITEM for a bare integer with no keyword and no line text")
    void shouldClassifyBareIntegerAsLineItem() {
        // The old code returned OTHER here, which excluded the value from
        // the auto-pick candidate pool. With the line-text check, an
        // integer with no keyword or shape hit is treated as a money amount.
        final DetectedNumber number = new DetectedNumber(50.00, "50", 0, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.LINE_ITEM);
    }


    @Test
    @DisplayName("classify does NOT match 'mid' as AUTH_CODE (the merchant-id tag)")
    void shouldNotClassifyMidAsAuthCode() {
        // "mid" was previously in the KEYWORD_AUTH alternation, which caused
        // "mid  8.00" on a wine-by-the-glass receipt to be classified as
        // AUTH_CODE. Removed because the false-positive cost outweighs the
        // rare merchant-id-tag hit.
        final DetectedNumber number = new DetectedNumber(8.00, "mid  8.00", 0, null);

        assertThat(number.classify()).isEqualTo(NumberCategory.LINE_ITEM);
    }
}
