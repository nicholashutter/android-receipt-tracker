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
}
