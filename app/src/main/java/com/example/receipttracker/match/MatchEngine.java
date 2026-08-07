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
 * Heuristic: a candidate is "close enough" if the amounts are within $1.00 of each other
 * AND the dates are within 3 days of each other. We score candidates by amount distance
 * (primary) and date distance (secondary); the user can override any match.
 */
public final class MatchEngine {

    public static final double AMOUNT_TOLERANCE = 1.00;
    public static final long DATE_TOLERANCE_MS = 3L * 24 * 60 * 60 * 1000;

    private MatchEngine() {}

    /** A single suggested (or already-confirmed) pairing for the match screen. */
    public static class MatchRow {
        public final Receipt receipt;
        @Nullable public final BankTransaction transaction;
        public final long amountDeltaCents;
        public final long dateDeltaMs;
        public final boolean confirmed;

        public MatchRow(Receipt r, @Nullable BankTransaction t, long amountDeltaCents,
                        long dateDeltaMs, boolean confirmed) {
            this.receipt = r;
            this.transaction = t;
            this.amountDeltaCents = amountDeltaCents;
            this.dateDeltaMs = dateDeltaMs;
            this.confirmed = confirmed;
        }
    }

    public static class Suggestion {
        public final Receipt receipt;
        @Nullable public final BankTransaction best;
        public final long amountDeltaCents;
        public final long dateDeltaMs;

        public Suggestion(Receipt r, @Nullable BankTransaction best,
                          long amountDeltaCents, long dateDeltaMs) {
            this.receipt = r;
            this.best = best;
            this.amountDeltaCents = amountDeltaCents;
            this.dateDeltaMs = dateDeltaMs;
        }
    }

    /**
     * For every unmatched receipt, pick the closest unmatched bank transaction (if any).
     * Greedy: each transaction is suggested at most once (highest-scoring receipt wins).
     */
    public static List<Suggestion> suggest(List<Receipt> receipts,
                                           List<BankTransaction> transactions) {
        List<Suggestion> out = new ArrayList<>();
        boolean[] txTaken = new boolean[transactions.size()];

        // Sort receipts so the most "specific" (largest amount) get first pick of txs.
        List<Receipt> sorted = new ArrayList<>(receipts);
        sorted.sort(Comparator.comparingDouble((Receipt r) -> -r.amount));

        for (Receipt r : sorted) {
            int bestIdx = -1;
            long bestAmtCents = Long.MAX_VALUE;
            long bestDateDelta = Long.MAX_VALUE;

            for (int i = 0; i < transactions.size(); i++) {
                if (txTaken[i]) continue;
                BankTransaction t = transactions.get(i);
                long amtDelta = Math.abs(toCents(r.amount) - toCents(t.amount));
                if (amtDelta > toCents(AMOUNT_TOLERANCE)) continue;
                long dateDelta = Math.abs(r.dateMillis - t.dateMillis);
                if (dateDelta > DATE_TOLERANCE_MS) continue;
                if (amtDelta < bestAmtCents
                        || (amtDelta == bestAmtCents && dateDelta < bestDateDelta)) {
                    bestAmtCents = amtDelta;
                    bestDateDelta = dateDelta;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                txTaken[bestIdx] = true;
                out.add(new Suggestion(r, transactions.get(bestIdx), bestAmtCents, bestDateDelta));
            } else {
                out.add(new Suggestion(r, null, 0, 0));
            }
        }
        return out;
    }

    public static long toCents(double d) {
        return Math.round(d * 100.0);
    }
}
