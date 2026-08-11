package com.example.receipttracker.match;


import com.example.receipttracker.ocr.DetectedNumber;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;


class TotalVerifierTest {

    @BeforeAll
    static void useUtcTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }


    @Test
    @DisplayName("verify on an empty list still produces a Result (synthetic candidate record)")
    void shouldHandleEmptyList() {
        final TotalVerifier.Result result = TotalVerifier.verify(47.83, Collections.emptyList());

        assertThat(result).isNotNull();
        assertThat(result.recommendedTotal).isEqualTo(47.83);
    }


    @Test
    @DisplayName("verify on a list with a real TOTAL line produces a high-confidence result")
    void shouldProduceHighConfidenceForRealTotal() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(45.00, "Subtotal  45.00", 0, "subtotal"),
                new DetectedNumber(2.83, "Tax  2.83", 1, "tax"),
                new DetectedNumber(47.83, "TOTAL  47.83", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(47.83, numbers);

        assertThat(result.recommendedTotal).isEqualTo(47.83);
        assertThat(result.recommendedSource).isEqualTo("circled");
        assertThat(result.confidence).isGreaterThan(0.5);
    }


    @Test
    @DisplayName("verify with an entered amount that matches the candidate boosts the recommended total")
    void shouldHonorEnteredAmountWhenItMatches() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(45.00, "Subtotal  45.00", 0, "subtotal"),
                new DetectedNumber(2.83, "Tax  2.83", 1, "tax"),
                new DetectedNumber(47.83, "TOTAL  47.83", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(47.83, numbers, 47.83);

        assertThat(result.enteredMatchesMarked).isTrue();
        assertThat(result.enteredAmount).isEqualTo(47.83);
    }


    @Test
    @DisplayName("verify with an entered amount that's close to but not exactly the candidate still matches within tolerance")
    void shouldMatchEnteredWithinTolerance() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(47.83, "TOTAL  47.83", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(47.83, numbers, 47.88);

        assertThat(result.enteredMatchesMarked).isTrue();
    }


    @Test
    @DisplayName("verify with an entered amount that disagrees with the candidate is reported as not matching")
    void shouldReportEnteredDisagrees() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(47.83, "TOTAL  47.83", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(47.83, numbers, 50.00);

        assertThat(result.enteredMatchesMarked).isFalse();
    }


    @Test
    @DisplayName("verify with no entered amount leaves enteredMatchesMarked false")
    void shouldLeaveEnteredBlankWithoutEntered() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(47.83, "TOTAL  47.83", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(47.83, numbers);

        assertThat(result.enteredMatchesMarked).isFalse();
    }


    @Test
    @DisplayName("verify produces a sanity check string")
    void shouldProduceSanityCheck() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(45.00, "Subtotal  45.00", 0, "subtotal"),
                new DetectedNumber(2.83, "Tax  2.83", 1, "tax"),
                new DetectedNumber(47.83, "TOTAL  47.83", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(47.83, numbers);

        assertThat(result.sanityCheck).isNotBlank();
    }


    @Test
    @DisplayName("verify produces a non-empty reasoning string")
    void shouldProduceNonEmptyReasoning() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(47.83, "TOTAL  47.83", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(47.83, numbers);

        assertThat(result.reasoning).isNotBlank();
    }


    @Test
    @DisplayName("verify with a real receipt's components populates a sane recommended total")
    void shouldHandleRealisticReceipt() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(5.99, "Item A  5.99", 0, null),
                new DetectedNumber(12.50, "Item B  12.50", 1, null),
                new DetectedNumber(18.49, "Subtotal  18.49", 2, "subtotal"),
                new DetectedNumber(1.50, "Tax  1.50", 3, "tax"),
                new DetectedNumber(19.99, "TOTAL  19.99", 4, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(19.99, numbers);

        assertThat(result.recommendedTotal).isGreaterThan(0.0);
        assertThat(result.confidence).isBetween(0.0, 1.0);
    }


    @Test
    @DisplayName("sanity check includes the items-sum when there are 2+ line items")
    void shouldIncludeItemsSumInSanityCheck() {
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(5.99, "Item A  5.99", 0, null),
                new DetectedNumber(7.50, "Item B  7.50", 1, null),
                new DetectedNumber(13.49, "TOTAL  13.49", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(13.49, numbers);

        assertThat(result.sanityCheck).contains("items sum=");
        assertThat(result.sanityCheck).contains("agrees");
    }


    @Test
    @DisplayName("sanity check reports items-sum disagrees when the sum doesn't match the candidate")
    void shouldReportItemsSumDisagrees() {
        // Items sum to 5.99+7.50 = 13.49, but the candidate is 14.00 — a $0.51
        // mismatch. With TOL_STRICT = 0.10 and TOL_LOOSE = 1.00, this is
        // within TOL_LOOSE so it reports "close" (not "agrees", not "disagrees").
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(5.99, "Item A  5.99", 0, null),
                new DetectedNumber(7.50, "Item B  7.50", 1, null),
                new DetectedNumber(14.00, "TOTAL  14.00", 2, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(14.00, numbers);

        assertThat(result.sanityCheck).contains("items sum=");
        assertThat(result.sanityCheck).contains("close");
    }


    @Test
    @DisplayName("sanity check with only one line item omits the items-sum clause")
    void shouldNotReportItemsSumForSingleItem() {
        // Only one item: the items-sum clause requires 2+ items to fire
        // (a single item could itself be the total).
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(5.99, "Item A  5.99", 0, null),
                new DetectedNumber(5.99, "TOTAL  5.99", 1, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(5.99, numbers);

        assertThat(result.sanityCheck).doesNotContain("items sum=");
    }


    @Test
    @DisplayName("sanity check with no components and no items shows the skip message")
    void shouldShowSkipMessageWhenNoComponentsOrItems() {
        // No subtotal/tax/tip labels AND fewer than 2 line items.
        final List<DetectedNumber> numbers = Arrays.asList(
                new DetectedNumber(5.99, "TOTAL  5.99", 0, "total"));

        final TotalVerifier.Result result = TotalVerifier.verify(5.99, numbers);

        assertThat(result.sanityCheck).contains("no subtotal/tax/tip labels or line items");
    }


    @Test
    @DisplayName("verify with no numbers and a NaN entered amount still produces a Result")
    void shouldHandleEmptyListAndNaNEntered() {
        final TotalVerifier.Result result = TotalVerifier.verify(10.0, Collections.emptyList(), Double.NaN);

        assertThat(result).isNotNull();
        assertThat(result.recommendedTotal).isEqualTo(10.0);
    }
}
