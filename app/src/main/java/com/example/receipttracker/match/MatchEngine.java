package com.example.receipttracker.match;


import androidx.annotation.Nullable;


import com.example.receipttracker.data.BankTransaction;
import com.example.receipttracker.data.Receipt;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * Suggests and applies matches between receipts and bank transactions.
 *
 * <p>Heuristic: a candidate is "close enough" if the amounts are within
 * $1.00 of each other AND the dates are within 3 days of each other.
 * We score candidates by amount distance (primary) and date distance
 * (secondary); the user can override any match.</p>
 */
public final class MatchEngine {

    public static final double AMOUNT_TOLERANCE = 1.00;

    public static final long DATE_TOLERANCE_MS = 3L * 24L * 60L * 60L * 1000L;

    private static final Comparator<Receipt> BY_AMOUNT_DESC =
            Comparator.comparingDouble((Receipt receipt) -> -receipt.amount);


    private MatchEngine() {}


    /** A single suggested (or already-confirmed) pairing for the match screen. */
    public static final class MatchRow {
        public final Receipt receipt;

        @Nullable public final BankTransaction transaction;

        public final long amountDeltaCents;
        public final long dateDeltaMs;
        public final boolean confirmed;


        public MatchRow(Receipt receipt,
                        @Nullable BankTransaction transaction,
                        long amountDeltaCents,
                        long dateDeltaMs,
                        boolean confirmed) {
            this.receipt = receipt;
            this.transaction = transaction;
            this.amountDeltaCents = amountDeltaCents;
            this.dateDeltaMs = dateDeltaMs;
            this.confirmed = confirmed;
        }
    }


    public static final class Suggestion {
        public final Receipt receipt;

        @Nullable public final BankTransaction best;

        public final long amountDeltaCents;
        public final long dateDeltaMs;


        public Suggestion(Receipt receipt,
                          @Nullable BankTransaction best,
                          long amountDeltaCents,
                          long dateDeltaMs) {
            this.receipt = receipt;
            this.best = best;
            this.amountDeltaCents = amountDeltaCents;
            this.dateDeltaMs = dateDeltaMs;
        }
    }


    /**
     * For every unmatched receipt, pick the closest unmatched bank
     * transaction (if any). Greedy: each transaction is suggested at
     * most once (highest-scoring receipt wins).
     */
    public static List<Suggestion> suggest(List<Receipt> receipts,
                                           List<BankTransaction> transactions) {
        // Sort receipts so the most "specific" (largest amount) get
        // first pick of transactions.
        final List<Receipt> sortedByAmountDesc = new ArrayList<>(receipts);
        sortedByAmountDesc.sort(BY_AMOUNT_DESC);

        final boolean[] transactionTaken = new boolean[transactions.size()];
        final List<Suggestion> suggestions = new ArrayList<>();

        for (final Receipt receipt : sortedByAmountDesc) {
            final MatchResult bestMatch = findBestMatch(receipt, transactions, transactionTaken);
            final Suggestion suggestion;
            if (bestMatch.transactionIndex >= 0) {
                transactionTaken[bestMatch.transactionIndex] = true;
                final BankTransaction matched = transactions.get(bestMatch.transactionIndex);
                suggestion = new Suggestion(receipt, matched,
                        bestMatch.amountDeltaCents, bestMatch.dateDeltaMs);
            } else {
                suggestion = new Suggestion(receipt, null, 0L, 0L);
            }
            suggestions.add(suggestion);
        }

        return suggestions;
    }


    /**
     * Returns the index of the best matching transaction for {@code receipt},
     * or -1 if no transaction is within tolerance. Each transaction is only
     * eligible if it hasn't already been taken by a higher-priority receipt.
     */
    private static MatchResult findBestMatch(Receipt receipt,
                                             List<BankTransaction> transactions,
                                             boolean[] transactionTaken) {
        int bestIndex = -1;
        long bestAmountDeltaCents = Long.MAX_VALUE;
        long bestDateDeltaMs = Long.MAX_VALUE;
        final long toleranceCents = toCents(AMOUNT_TOLERANCE);

        for (int index = 0; index < transactions.size(); index++) {
            if (transactionTaken[index]) continue;

            final BankTransaction candidate = transactions.get(index);
            final long amountDeltaCents = Math.abs(toCents(receipt.amount) - toCents(candidate.amount));
            if (amountDeltaCents > toleranceCents) continue;

            final long dateDeltaMs = Math.abs(receipt.dateMillis - candidate.dateMillis);
            if (dateDeltaMs > DATE_TOLERANCE_MS) continue;

            final boolean isBetter = amountDeltaCents < bestAmountDeltaCents
                    || (amountDeltaCents == bestAmountDeltaCents && dateDeltaMs < bestDateDeltaMs);
            if (isBetter) {
                bestAmountDeltaCents = amountDeltaCents;
                bestDateDeltaMs = dateDeltaMs;
                bestIndex = index;
            }
        }

        return new MatchResult(bestIndex, bestAmountDeltaCents, bestDateDeltaMs);
    }


    /** Tuple of the winning match for a single receipt. */
    private static final class MatchResult {
        final int transactionIndex;
        final long amountDeltaCents;
        final long dateDeltaMs;

        MatchResult(int transactionIndex, long amountDeltaCents, long dateDeltaMs) {
            this.transactionIndex = transactionIndex;
            this.amountDeltaCents = amountDeltaCents;
            this.dateDeltaMs = dateDeltaMs;
        }
    }


    public static long toCents(double amount) {
        return Math.round(amount * 100.0);
    }
}
