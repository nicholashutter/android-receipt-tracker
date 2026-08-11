package com.example.receipttracker.ocr;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;


class ReceiptParserTest {

    @BeforeAll
    static void useUtcTimezone() {
        // ReceiptParser.toMidnightMillis uses the default TimeZone so the
        // date millis represent midnight in the JVM's local zone. Pin to
        // UTC for deterministic test assertions.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Test
    @DisplayName("parse(null) returns ParsedReceipt.EMPTY")
    void shouldReturnEmptyOnNull() {
        final ParsedReceipt parsed = ReceiptParser.parse(null);

        assertThat(parsed).isSameAs(ParsedReceipt.EMPTY);
    }


    @Test
    @DisplayName("parse('') returns ParsedReceipt.EMPTY")
    void shouldReturnEmptyOnBlankText() {
        final ParsedReceipt parsed = ReceiptParser.parse("   \n  \n");

        assertThat(parsed).isSameAs(ParsedReceipt.EMPTY);
    }


    @Test
    @DisplayName("parse picks the all-caps merchant line near the top")
    void shouldPickAllCapsMerchantAtTop() {
        final String text = "WHOLE FOODS MARKET\n"
                + "123 Main St\n"
                + "Subtotal  45.00\n"
                + "Tax  2.83\n"
                + "TOTAL  47.83\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.merchant).isEqualTo("WHOLE FOODS MARKET");
    }


    @Test
    @DisplayName("parse falls back to first non-junk line when no caps line is present")
    void shouldFallBackToFirstNonJunkLine() {
        final String text = "Costco Wholesale\n"
                + "456 Side Ave\n"
                + "Subtotal  12.00\n"
                + "TOTAL  12.00\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.merchant).isEqualTo("Costco Wholesale");
    }


    @Test
    @DisplayName("parse skips lines that are just a price")
    void shouldSkipPriceOnlyLines() {
        final String text = "5.99\n"
                + "Joe's Pizza\n"
                + "TOTAL  5.99\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.merchant).isEqualTo("Joe's Pizza");
    }


    @Test
    @DisplayName("parse skips junk keywords like 'receipt' and 'thank you'")
    void shouldSkipJunkKeywordLines() {
        final String text = "Receipt # 1234\n"
                + "Thank You\n"
                + "Trader Joe's\n"
                + "TOTAL  10.00\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.merchant).isEqualTo("Trader Joe's");
    }


    @Test
    @DisplayName("parse picks the LAST number on a TOTAL-keyword line (subtotal/tax/total pattern)")
    void shouldPickLastNumberOnTotalLine() {
        final String text = "Subtotal  45.00\n"
                + "Tax  2.83\n"
                + "TOTAL  47.83\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.amount).isEqualTo(47.83);
    }


    @Test
    @DisplayName("parse falls back to the largest decimal when no total keyword is present")
    void shouldFallBackToLargestDecimalWhenNoTotalKeyword() {
        final String text = "Item A  5.99\n"
                + "Item B  12.50\n"
                + "Item C  3.25\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.amount).isEqualTo(12.50);
    }


    @Test
    @DisplayName("parse parses YYYY-MM-DD date format")
    void shouldParseIsoDate() {
        final String text = "Date: 2024-03-15\nTOTAL  10.00\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.dateMillis).isNotNull();
        final java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(parsed.dateMillis);
        assertThat(calendar.get(java.util.Calendar.YEAR)).isEqualTo(2024);
        assertThat(calendar.get(java.util.Calendar.MONTH)).isEqualTo(2);
        assertThat(calendar.get(java.util.Calendar.DAY_OF_MONTH)).isEqualTo(15);
    }


    @Test
    @DisplayName("parse parses M/D/YYYY date format")
    void shouldParseSlashDate() {
        final String text = "03/15/2024\nTOTAL  10.00\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.dateMillis).isNotNull();
        final java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(parsed.dateMillis);
        assertThat(calendar.get(java.util.Calendar.YEAR)).isEqualTo(2024);
        assertThat(calendar.get(java.util.Calendar.MONTH)).isEqualTo(2);
        assertThat(calendar.get(java.util.Calendar.DAY_OF_MONTH)).isEqualTo(15);
    }


    @Test
    @DisplayName("parse parses '5 Jan 2024' day-month-year format")
    void shouldParseDayMonthYear() {
        final String text = "5 Jan 2024\nTOTAL  10.00\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.dateMillis).isNotNull();
        final java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(parsed.dateMillis);
        assertThat(calendar.get(java.util.Calendar.YEAR)).isEqualTo(2024);
        assertThat(calendar.get(java.util.Calendar.MONTH)).isEqualTo(0);
        assertThat(calendar.get(java.util.Calendar.DAY_OF_MONTH)).isEqualTo(5);
    }


    @Test
    @DisplayName("parse parses 'Jan 5, 2024' month-day-year format")
    void shouldParseMonthDayYear() {
        final String text = "Jan 5, 2024\nTOTAL  10.00\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.dateMillis).isNotNull();
        final java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(parsed.dateMillis);
        assertThat(calendar.get(java.util.Calendar.YEAR)).isEqualTo(2024);
        assertThat(calendar.get(java.util.Calendar.MONTH)).isEqualTo(0);
        assertThat(calendar.get(java.util.Calendar.DAY_OF_MONTH)).isEqualTo(5);
    }


    @Test
    @DisplayName("parse returns null dateMillis when no date pattern matches")
    void shouldReturnNullDateWhenNoPatternMatches() {
        final String text = "WHOLE FOODS\nTOTAL  10.00\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.dateMillis).isNull();
    }


    @Test
    @DisplayName("parse returns null amount when no money is found")
    void shouldReturnNullAmountWhenNoMoney() {
        final String text = "WHOLE FOODS\nNo totals here\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.amount).isNull();
    }


    @Test
    @DisplayName("parse attaches the raw text to the result")
    void shouldAttachRawText() {
        final String text = "WHOLE FOODS\nTOTAL  47.83\n";

        final ParsedReceipt parsed = ReceiptParser.parse(text);

        assertThat(parsed.rawText).isSameAs(text);
    }


    // ---------- pickCircledCandidate priorities ----------

    @Test
    @DisplayName("pickCircledCandidate prioritises visually-emphasised numbers over TOTAL keyword")
    void shouldPickEmphasisedOverKeyword() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(10.00, "TOTAL  10.00", 0, "total", 0f, 0f, null),
                new DetectedNumber(50.00, "Circled one  50.00", 1, null, 0.8f, 0f, null));

        final DetectedNumber picked = ReceiptParser.pickCircledCandidate(numbers);

        assertThat(picked.value).isEqualTo(50.00);
    }


    @Test
    @DisplayName("pickCircledCandidate picks TOTAL-keyword line when no visual emphasis present")
    void shouldPickTotalKeywordWhenNoEmphasis() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(12.00, "Subtotal  12.00", 0, "subtotal"),
                new DetectedNumber(15.00, "TOTAL  15.00", 1, "total"));

        final DetectedNumber picked = ReceiptParser.pickCircledCandidate(numbers);

        assertThat(picked.value).isEqualTo(15.00);
    }


    @Test
    @DisplayName("pickCircledCandidate returns the bottom-half largest when no keyword and no emphasis")
    void shouldPickBottomHalfLargest() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(50.00, "huge  50.00", 0, null),
                new DetectedNumber(5.99, "small  5.99", 5, null),
                new DetectedNumber(8.00, "mid  8.00", 6, null));

        final DetectedNumber picked = ReceiptParser.pickCircledCandidate(numbers);

        assertThat(picked.value).isEqualTo(8.00);
    }


    @Test
    @DisplayName("pickCircledCandidate returns the whole-receipt largest when bottom-half is empty")
    void shouldPickWholeReceiptLargest() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(99.00, "top  99.00", 0, null));

        final DetectedNumber picked = ReceiptParser.pickCircledCandidate(numbers);

        assertThat(picked.value).isEqualTo(99.00);
    }


    @Test
    @DisplayName("pickCircledCandidate returns null on empty input")
    void shouldReturnNullOnEmpty() {
        final DetectedNumber picked = ReceiptParser.pickCircledCandidate(java.util.Collections.emptyList());

        assertThat(picked).isNull();
    }


    // ---------- extractAllNumbers ----------

    @Test
    @DisplayName("extractAllNumbers on a 3-line receipt returns all money matches with their line index")
    void shouldExtractAllNumbersWithLineIndices() {
        final String text = "Item  5.99\n"
                + "Subtotal  5.99\n"
                + "Tax  0.50\n"
                + "TOTAL  6.49\n";

        final List<DetectedNumber> numbers = ReceiptParser.extractAllNumbers(text);

        assertThat(numbers).hasSize(4);
        assertThat(numbers.get(0).value).isEqualTo(5.99);
        assertThat(numbers.get(0).lineIndex).isEqualTo(0);
        assertThat(numbers.get(3).value).isEqualTo(6.49);
        assertThat(numbers.get(3).lineIndex).isEqualTo(3);
    }


    @Test
    @DisplayName("extractAllNumbers on null or empty returns an empty list")
    void shouldReturnEmptyListForNullOrEmpty() {
        assertThat(ReceiptParser.extractAllNumbers(null)).isEmpty();
        assertThat(ReceiptParser.extractAllNumbers("")).isEmpty();
        assertThat(ReceiptParser.extractAllNumbers("   \n   ")).isEmpty();
    }


    @Test
    @DisplayName("extractAllNumbers propagates a keyword from an adjacent keyword-only line")
    void shouldPropagateAdjacentKeyword() {
        final String text = "Subtotal\n"
                + "12.50\n"
                + "TOTAL  12.50\n";

        final List<DetectedNumber> numbers = ReceiptParser.extractAllNumbers(text);

        final DetectedNumber propagated = numbers.get(0);
        assertThat(propagated.value).isEqualTo(12.50);
        assertThat(propagated.keyword).isEqualTo("subtotal");
    }
}
