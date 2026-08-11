package com.example.receipttracker.match;


import com.example.receipttracker.data.BankTransaction;
import com.example.receipttracker.data.Receipt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class MatchEngineTest {

    private static Receipt receipt(long id, double amount, long dateMillis) {
        return new Receipt(id, "Merchant", dateMillis, amount, null, null, null, 0L, null, null, null);
    }


    private static BankTransaction transaction(long id, double amount, long dateMillis) {
        return new BankTransaction(id, "Description", dateMillis, amount, "Checking", 0L, null);
    }


    @Test
    @DisplayName("AMOUNT_TOLERANCE is $1.00")
    void shouldExposeAmountTolerance() {
        assertThat(MatchEngine.AMOUNT_TOLERANCE).isEqualTo(1.00);
    }


    @Test
    @DisplayName("DATE_TOLERANCE_MS is 3 days")
    void shouldExposeDateTolerance() {
        final long threeDaysMs = 3L * 24L * 60L * 60L * 1000L;

        assertThat(MatchEngine.DATE_TOLERANCE_MS).isEqualTo(threeDaysMs);
    }


    @Test
    @DisplayName("toCents(1.234) rounds to 123 cents")
    void shouldConvertToCents() {
        assertThat(MatchEngine.toCents(1.234)).isEqualTo(123L);
    }


    @Test
    @DisplayName("toCents(0.00) is 0 cents")
    void shouldReturnZeroForZero() {
        assertThat(MatchEngine.toCents(0.0)).isEqualTo(0L);
    }


    @Test
    @DisplayName("toCents(47.83) is 4783 cents")
    void shouldConvertWholeCents() {
        assertThat(MatchEngine.toCents(47.83)).isEqualTo(4783L);
    }


    @Test
    @DisplayName("suggest: identical amount and date matches")
    void shouldMatchExact() {
        final long date = 1_704_067_200_000L;
        final List<Receipt> receipts = Arrays.asList(receipt(1, 47.83, date));
        final List<BankTransaction> transactions = Arrays.asList(transaction(10, 47.83, date));

        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(receipts, transactions);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).best).isNotNull();
        assertThat(suggestions.get(0).amountDeltaCents).isEqualTo(0L);
        assertThat(suggestions.get(0).dateDeltaMs).isEqualTo(0L);
    }


    @Test
    @DisplayName("suggest: amount within $1.00 tolerance matches")
    void shouldMatchWithinAmountTolerance() {
        final long date = 1_704_067_200_000L;
        final List<Receipt> receipts = Arrays.asList(receipt(1, 47.83, date));
        final List<BankTransaction> transactions = Arrays.asList(transaction(10, 48.50, date));

        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(receipts, transactions);

        assertThat(suggestions.get(0).best).isNotNull();
    }


    @Test
    @DisplayName("suggest: amount beyond $1.00 tolerance does not match")
    void shouldNotMatchOutsideAmountTolerance() {
        final long date = 1_704_067_200_000L;
        final List<Receipt> receipts = Arrays.asList(receipt(1, 47.83, date));
        final List<BankTransaction> transactions = Arrays.asList(transaction(10, 50.00, date));

        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(receipts, transactions);

        assertThat(suggestions.get(0).best).isNull();
    }


    @Test
    @DisplayName("suggest: date beyond 3 days does not match")
    void shouldNotMatchOutsideDateTolerance() {
        final long date = 1_704_067_200_000L;
        final long fourDaysLater = date + (4L * 24L * 60L * 60L * 1000L);
        final List<Receipt> receipts = Arrays.asList(receipt(1, 47.83, date));
        final List<BankTransaction> transactions = Arrays.asList(transaction(10, 47.83, fourDaysLater));

        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(receipts, transactions);

        assertThat(suggestions.get(0).best).isNull();
    }


    @Test
    @DisplayName("suggest: empty inputs produce empty suggestions")
    void shouldHandleEmptyInputs() {
        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(
                Collections.emptyList(), Collections.emptyList());

        assertThat(suggestions).isEmpty();
    }


    @Test
    @DisplayName("suggest: each transaction is suggested at most once (greedy)")
    void shouldBeGreedyOneTransactionPerReceipt() {
        final long date = 1_704_067_200_000L;
        final List<Receipt> receipts = Arrays.asList(
                receipt(1, 50.00, date),
                receipt(2, 50.00, date));
        final List<BankTransaction> transactions = Arrays.asList(transaction(10, 50.00, date));

        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(receipts, transactions);

        final int matched = (int) suggestions.stream().filter(s -> s.best != null).count();
        assertThat(matched).isEqualTo(1);
    }


    @Test
    @DisplayName("suggest: larger receipts are matched first (greedy priority)")
    void shouldPrioritiseLargestReceipts() {
        final long date = 1_704_067_200_000L;
        final List<Receipt> receipts = new ArrayList<>(Arrays.asList(
                receipt(1, 5.00, date),
                receipt(2, 500.00, date)));
        final List<BankTransaction> transactions = Arrays.asList(transaction(10, 5.00, date));

        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(receipts, transactions);

        // The 500-receipt can't match the 5-transaction. The 5-receipt
        // should get the 5-transaction. Sorted by amount DESC, so the
        // 500-receipt is processed first and gets nothing, then the
        // 5-receipt gets the 5-transaction.
        assertThat(suggestions.get(0).receipt.amount).isEqualTo(500.00);
        assertThat(suggestions.get(0).best).isNull();
        assertThat(suggestions.get(1).receipt.amount).isEqualTo(5.00);
        assertThat(suggestions.get(1).best).isNotNull();
    }


    @Test
    @DisplayName("suggest: amountDeltaCents reflects the absolute distance")
    void shouldReportAmountDeltaCents() {
        final long date = 1_704_067_200_000L;
        final List<Receipt> receipts = Arrays.asList(receipt(1, 47.83, date));
        final List<BankTransaction> transactions = Arrays.asList(transaction(10, 48.33, date));

        final List<MatchEngine.Suggestion> suggestions = MatchEngine.suggest(receipts, transactions);

        assertThat(suggestions.get(0).amountDeltaCents).isEqualTo(50L);
    }
}
