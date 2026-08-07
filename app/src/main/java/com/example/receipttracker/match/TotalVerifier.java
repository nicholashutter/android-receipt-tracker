package com.example.receipttracker.match;

import androidx.annotation.Nullable;

import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ocr.DetectedNumber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Two-stage verification of a candidate receipt total, with an
 * entered-vs-circled cross-check and a sub+tax+tip sanity check.
 *
 * <p>Stage 1 ({@link PriceClassifier}): every detected number is
 * classified as "is this a price?" Dates, phone numbers, auth codes,
 * transaction IDs, and quantities are dropped.</p>
 *
 * <p>Stage 2 ({@link LinearLearner}): each remaining price is scored
 * for "is this the total?" — and the highest-probability alternative
 * is reported so the UI can show the user what the model would have
 * picked.</p>
 *
 * <p>Cross-check: if the user typed an amount in the amount field,
 * we re-run stage 1 + stage 2 on the entered value too, and compare
 * it against the marked one. Agreement boosts confidence; disagreement
 * triggers a sanity check.</p>
 *
 * <p>Sanity check: if the receipt has labeled components (subtotal,
 * tax, tip), the predicted total is their sum. The candidate (and
 * the entered value) are compared to that prediction. Whichever
 * matches sub+tax better wins the disagreement.</p>
 *
 * <p>Final confidence is a blend of the three signals minus an
 * "alternative pressure" penalty when a different price scores
 * noticeably higher than the marked one.</p>
 */
public final class TotalVerifier {

    private TotalVerifier() {}

    public static class Result {
        public final double total;
        public final double confidence;
        public final String reasoning;
        public final boolean wasAdjusted;
        /** P(this number is a price) — from the stage 1 classifier. */
        public final double priceProbability;
        /** P(marked price is the real total) — from the stage 2 classifier. */
        public final double candidateProbability;
        /** Highest P among the other prices. */
        public final double bestAlternativeProbability;
        /** The number the stage 2 model thinks is the real total. */
        public final double modelChoice;
        // ---- entered vs circled cross-check ----
        /** What the user typed in the amount field. NaN if not provided. */
        public final double enteredAmount;
        /** P(entered is a price). NaN if not provided. */
        public final double enteredPriceProbability;
        /** P(entered is the real total). NaN if not provided. */
        public final double enteredProbability;
        /** true if entered and marked agree to within a dime. */
        public final boolean enteredMatchesMarked;
        /** "sub+tax+tip=$X (circled delta=$Y, entered delta=$Z)" or "no components". */
        public final String sanityCheck;
        /** What the combined verdict recommends the user actually enter. */
        public final double recommendedTotal;
        /** Why we picked recommendedTotal: "circled" / "entered" / "model-best" / "sub+tax". */
        public final String recommendedSource;
        /** |circled - sub+tax|, the absolute sanity delta. */
        public final double sanityDelta;

        public Result(double total, double confidence, String reasoning, boolean wasAdjusted,
                      double priceProbability, double candidateProbability,
                      double bestAlternativeProbability, double modelChoice,
                      double enteredAmount, double enteredPriceProbability,
                      double enteredProbability, boolean enteredMatchesMarked,
                      String sanityCheck, double recommendedTotal,
                      String recommendedSource, double sanityDelta) {
            this.total = total;
            this.confidence = confidence;
            this.reasoning = reasoning;
            this.wasAdjusted = wasAdjusted;
            this.priceProbability = priceProbability;
            this.candidateProbability = candidateProbability;
            this.bestAlternativeProbability = bestAlternativeProbability;
            this.modelChoice = modelChoice;
            this.enteredAmount = enteredAmount;
            this.enteredPriceProbability = enteredPriceProbability;
            this.enteredProbability = enteredProbability;
            this.enteredMatchesMarked = enteredMatchesMarked;
            this.sanityCheck = sanityCheck;
            this.recommendedTotal = recommendedTotal;
            this.recommendedSource = recommendedSource;
            this.sanityDelta = sanityDelta;
        }
    }

    private static final double TOL_STRICT = 0.10;
    private static final double TOL_TIGHT  = 0.50;
    private static final double TOL_LOOSE  = 1.00;

    /** Overload for callers that don't have an entered amount. */
    public static Result verify(double candidate, List<DetectedNumber> allNumbers) {
        return verify(candidate, allNumbers, Double.NaN);
    }

    /**
     * Verifies a circled/marked candidate AND, if {@code enteredAmount} is
     * a real number, the user-typed amount. Cross-checks the two and
     * runs a sanity check against the heuristic sub+tax+tip prediction.
     *
     * @param candidate     the number the user circled/marked
     * @param allNumbers    every number the parser detected on the receipt
     * @param enteredAmount the number the user typed in the amount field, or NaN
     */
    public static Result verify(double candidate, List<DetectedNumber> allNumbers,
                                 double enteredAmount) {
        Logger.section("TOTAL VERIFY");
        Logger.i("Verifier", "candidate(circled)=" + candidate
                + "  entered=" + (Double.isNaN(enteredAmount) ? "(none)" : fmt(enteredAmount))
                + "  (all numbers: " + (allNumbers == null ? 0 : allNumbers.size()) + ")");

        // ============ STAGE 1: PriceClassifier ============
        Logger.section("STAGE 1: PRICE CLASSIFIER");
        PriceClassifier.getWeights(); PriceClassifier.getBias();

        DetectedNumber candidateRecord = findLineWithValue(allNumbers, candidate);
        if (candidateRecord == null) {
            candidateRecord = new DetectedNumber(candidate, "(synthetic)", 0, null);
        }

        List<DetectedNumber> prices = new ArrayList<>();
        for (DetectedNumber n : allNumbers) {
            double[] f = PriceClassifier.extractFeatures(n);
            double p = PriceClassifier.predictProbability(f);
            boolean keep = p >= PriceClassifier.PRICE_THRESHOLD;
            Logger.i("PriceClf", String.format(Locale.US,
                    "  $%.2f (line %d, kw=%s)  P(isPrice)=%.3f  %s",
                    n.value, n.lineIndex, n.keyword, p, keep ? "[PRICE]" : "[drop]"));
            if (keep) prices.add(n);
        }
        Logger.i("PriceClf", "kept " + prices.size() + " of " + allNumbers.size() + " numbers as prices");

        double candPriceProb = PriceClassifier.predictProbability(
                PriceClassifier.extractFeatures(candidateRecord));
        Logger.i("PriceClf", String.format(Locale.US,
                "candidate(circled) $%.2f  P(isPrice)=%.3f", candidate, candPriceProb));

        // ============ Pass 1: delta heuristic on the price subset ============
        Logger.section("HEURISTIC ON PRICES");
        List<DetectedNumber> others = new ArrayList<>();
        for (DetectedNumber n : prices) {
            if (Math.abs(n.value - candidate) < 0.005) continue;
            others.add(n);
        }
        Collections.sort(others, (a, b) -> Double.compare(a.value, b.value));

        Double subtotal = pickOne(others, "subtotal");
        Double tax      = pickOne(others, "tax");
        Double tip      = pickOne(others, "tip");
        Logger.i("Verifier", "components: subtotal=" + subtotal
                + "  tax=" + tax + "  tip=" + tip);

        double predicted = 0;
        int componentCount = 0;
        StringBuilder predExpr = new StringBuilder();
        if (subtotal != null) { predicted += subtotal; componentCount++;
            predExpr.append("subtotal(").append(fmt(subtotal)).append(")"); }
        if (tax != null) { predicted += tax; componentCount++;
            if (predExpr.length() > 0) predExpr.append(" + ");
            predExpr.append("tax(").append(fmt(tax)).append(")"); }
        if (tip != null) { predicted += tip; componentCount++;
            if (predExpr.length() > 0) predExpr.append(" + ");
            predExpr.append("tip(").append(fmt(tip)).append(")"); }
        if (componentCount == 0) {
            double lineItemSum = 0;
            int itemCount = 0;
            for (DetectedNumber n : others) {
                if (n.value < candidate && n.value > 0.10) {
                    lineItemSum += n.value;
                    itemCount++;
                }
            }
            if (itemCount >= 2) {
                predicted = lineItemSum;
                componentCount = 1;
                predExpr.setLength(0);
                predExpr.append("lineItemsSum(").append(fmt(lineItemSum)).append(")");
            }
        }
        Logger.i("Verifier", "predicted(sub+tax+tip)=" + fmt(predicted) + "  expr=" + predExpr);

        // ============ STAGE 2: LinearLearner on the price subset ============
        Logger.section("STAGE 2: LINEAR LEARNER (on prices only)");
        LinearLearner.getWeights(); LinearLearner.getBias();

        int totalLines = maxLineIndex(allNumbers) + 1;
        double[] candFeatures = LinearLearner.extractFeatures(
                candidateRecord, prices, subtotal, tax, tip, totalLines);
        double candLogit = LinearLearner.predictLogit(candFeatures);
        double candProb  = LinearLearner.predictProbability(candFeatures);
        Logger.i("TotalLearner", "candidate(circled)=" + fmt(candidate)
                + "  logit=" + String.format(Locale.US, "%+.3f", candLogit)
                + "  P=" + String.format(Locale.US, "%.3f", candProb));

        double bestAltProb = 0;
        double bestAltVal  = candidate;
        DetectedNumber bestAltRecord = null;
        for (DetectedNumber n : others) {
            double[] f = LinearLearner.extractFeatures(n, prices, subtotal, tax, tip, totalLines);
            double p = LinearLearner.predictProbability(f);
            Logger.i("TotalLearner", String.format(Locale.US,
                    "  price=$%.2f (line %d, kw=%s)  P(isTotal)=%.3f",
                    n.value, n.lineIndex, n.keyword, p));
            if (p > bestAltProb) {
                bestAltProb = p;
                bestAltVal  = n.value;
                bestAltRecord = n;
            }
        }
        Logger.i("TotalLearner", String.format(Locale.US,
                "best alternative: $%.2f  P=%.3f", bestAltVal, bestAltProb));

        // ============ ENTERED: same stage 1 + stage 2 against the user-typed value ============
        double enteredPriceProb = Double.NaN;
        double enteredProb      = Double.NaN;
        boolean haveEntered = !Double.isNaN(enteredAmount) && enteredAmount > 0;
        if (haveEntered) {
            DetectedNumber enteredRecord = findLineWithValue(allNumbers, enteredAmount);
            if (enteredRecord == null) {
                enteredRecord = new DetectedNumber(enteredAmount, "(user-typed)", 0, null);
            }
            enteredPriceProb = PriceClassifier.predictProbability(
                    PriceClassifier.extractFeatures(enteredRecord));
            Logger.i("PriceClf", String.format(Locale.US,
                    "candidate(entered) $%.2f  P(isPrice)=%.3f", enteredAmount, enteredPriceProb));
            double[] enteredFeatures = LinearLearner.extractFeatures(
                    enteredRecord, prices, subtotal, tax, tip, totalLines);
            enteredProb = LinearLearner.predictProbability(enteredFeatures);
            Logger.i("TotalLearner", String.format(Locale.US,
                    "candidate(entered) $%.2f  P(isTotal)=%.3f", enteredAmount, enteredProb));
        }

        // ============ Cross-check: entered vs circled ============
        boolean enteredMatchesMarked = haveEntered
                && Math.abs(enteredAmount - candidate) <= TOL_STRICT;
        Logger.i("Verifier", String.format(Locale.US,
                "cross-check: entered=%s  circled=%s  match=%s",
                haveEntered ? fmt(enteredAmount) : "(none)",
                fmt(candidate),
                enteredMatchesMarked));

        // ============ Sanity check: both vs sub+tax+tip ============
        String sanityCheck;
        double sanityDelta;
        if (componentCount == 0) {
            sanityCheck = "no subtotal/tax/tip labels — sanity check skipped";
            sanityDelta = Double.NaN;
        } else {
            sanityCheck = String.format(Locale.US,
                    "sub+tax+tip=%s  (circled delta=$%.2f",
                    fmt(predicted), Math.abs(candidate - predicted));
            if (haveEntered) {
                sanityCheck += String.format(Locale.US,
                        ", entered delta=$%.2f", Math.abs(enteredAmount - predicted));
            }
            sanityCheck += ")";
            sanityDelta = Math.abs(candidate - predicted);
        }
        Logger.i("Verifier", "sanity: " + sanityCheck);

        // ============ Combine (circled) ============
        double delta = Math.abs(candidate - predicted);
        double deltaConfidence;
        if (componentCount == 0) {
            deltaConfidence = 0.40;
        } else if (delta <= TOL_STRICT)       deltaConfidence = 0.97;
        else if (delta <= TOL_TIGHT)          deltaConfidence = 0.88;
        else if (delta <= TOL_LOOSE)          deltaConfidence = 0.70;
        else if (predicted > 0 && delta <= predicted * 0.10) deltaConfidence = 0.55;
        else                                  deltaConfidence = 0.30;

        double combined = (deltaConfidence + candProb) / 2.0;
        double altPressure = Math.max(0.0, bestAltProb - candProb - 0.10);
        combined = Math.max(0.0, Math.min(0.99, combined - altPressure));
        if (candProb >= 0.85 && bestAltProb <= candProb) combined = Math.min(0.99, combined + 0.05);
        if (subtotal != null && candidate < subtotal - 0.01) combined = Math.max(0.10, combined - 0.20);
        if (delta <= TOL_LOOSE && candProb >= 0.70) combined = Math.min(0.99, combined + 0.05);
        if (candPriceProb < 0.5) combined = Math.max(0.05, combined - 0.30);

        // Cross-check nudge: if entered disagrees with circled, bump the discrepancy
        // into the combined score.
        if (haveEntered && !enteredMatchesMarked) {
            double disagreeDelta = Math.abs(enteredProb - candProb);
            combined = Math.max(0.0, combined - 0.10 - 0.10 * disagreeDelta);
        }
        if (enteredMatchesMarked) {
            combined = Math.min(0.99, combined + 0.05);
        }

        // ============ Decide what to recommend (3-way majority) ============
        String recommendedSource;
        double recommendedTotal;
        boolean adjusted;

        recommendedSource = "circled";
        recommendedTotal  = candidate;
        adjusted = false;

        if (haveEntered && !enteredMatchesMarked) {
            // Sanity check is the tie-breaker.
            double circledSanityDelta = Math.abs(candidate - predicted);
            double enteredSanityDelta = Math.abs(enteredAmount - predicted);
            boolean sanityHelpsCircled = (circledSanityDelta <= enteredSanityDelta)
                    && (circledSanityDelta <= TOL_LOOSE);
            boolean sanityHelpsEntered = (enteredSanityDelta <= circledSanityDelta)
                    && (enteredSanityDelta <= TOL_LOOSE);

            if (sanityHelpsCircled && !sanityHelpsEntered) {
                recommendedSource = "circled (sanity wins)";
                recommendedTotal  = candidate;
            } else if (sanityHelpsEntered && !sanityHelpsCircled) {
                recommendedSource = "entered (sanity wins)";
                recommendedTotal  = enteredAmount;
                adjusted = true;
            } else if (sanityHelpsCircled && sanityHelpsEntered) {
                if (enteredProb > candProb + 0.10) {
                    recommendedSource = "entered (model + sanity)";
                    recommendedTotal  = enteredAmount;
                    adjusted = true;
                } else {
                    recommendedSource = "circled (model + sanity)";
                    recommendedTotal  = candidate;
                }
            } else {
                if (bestAltRecord != null && bestAltProb > Math.max(candProb, enteredProb)) {
                    recommendedSource = "model-best (neither matches sub+tax)";
                    recommendedTotal  = bestAltVal;
                    adjusted = true;
                } else if (enteredProb > candProb) {
                    recommendedSource = "entered (model prefers it)";
                    recommendedTotal  = enteredAmount;
                    adjusted = true;
                }
            }
        } else if (combined < 0.55 && bestAltRecord != null && bestAltProb >= 0.70
                && bestAltProb > candProb + 0.10) {
            recommendedSource = "model-best (no components)";
            recommendedTotal  = bestAltVal;
            adjusted = true;
        }

        if (Math.abs(recommendedTotal - candidate) < 0.005) {
            adjusted = false;
        }

        // ============ Reasoning string ============
        StringBuilder reason = new StringBuilder();
        reason.append("Stage 1 (PriceClf):\n");
        reason.append(String.format(Locale.US, "  circled $%.2f  P(isPrice)=%.2f%n", candidate, candPriceProb));
        if (haveEntered) {
            reason.append(String.format(Locale.US,
                    "  entered $%.2f  P(isPrice)=%.2f%n", enteredAmount, enteredPriceProb));
        }
        reason.append("Stage 2 (TotalLearner):\n");
        reason.append(String.format(Locale.US,
                "  circled $%.2f  P(isTotal)=%.2f  (best other: $%.2f P=%.2f)%n",
                candidate, candProb, bestAltVal, bestAltProb));
        if (haveEntered) {
            reason.append(String.format(Locale.US,
                    "  entered $%.2f  P(isTotal)=%.2f%n", enteredAmount, enteredProb));
        }
        reason.append("Cross-check:\n");
        if (haveEntered) {
            reason.append(enteredMatchesMarked
                    ? "  OK entered and circled agree to within " + fmt(TOL_STRICT) + "\n"
                    : "  WARN entered and circled differ — sanity check decides\n");
        } else {
            reason.append("  (no entered value provided)\n");
        }
        reason.append("Sanity check:  ").append(sanityCheck).append("\n");
        reason.append(String.format(Locale.US,
                "Combined:  %.2f  ->  recommended %.2f (%s)%n",
                combined, recommendedTotal, recommendedSource));

        Logger.i("Verifier", String.format(Locale.US,
                "verdict: total=%.2f  confidence=%.2f  adjusted=%s  source=%s",
                recommendedTotal, combined, adjusted, recommendedSource));
        Logger.i("Verifier", "reason: " + reason);
        Logger.section("TOTAL VERIFY END");

        return new Result(recommendedTotal, combined, reason.toString(), adjusted,
                candPriceProb, candProb, bestAltProb,
                bestAltRecord != null && bestAltProb > candProb ? bestAltVal : candidate,
                haveEntered ? enteredAmount : Double.NaN,
                haveEntered ? enteredPriceProb : Double.NaN,
                haveEntered ? enteredProb     : Double.NaN,
                enteredMatchesMarked,
                sanityCheck,
                recommendedTotal,
                recommendedSource,
                sanityDelta);
    }

    @Nullable
    private static Double pickOne(List<DetectedNumber> numbers, String keyword) {
        Double found = null;
        for (DetectedNumber n : numbers) {
            if (keyword.equals(n.keyword)) found = n.value;
        }
        return found;
    }

    @Nullable
    private static DetectedNumber findLineWithValue(List<DetectedNumber> all, double v) {
        for (DetectedNumber n : all) {
            if (Math.abs(n.value - v) < 0.005) return n;
        }
        return null;
    }

    private static int maxLineIndex(List<DetectedNumber> all) {
        int m = 0;
        for (DetectedNumber n : all) if (n.lineIndex > m) m = n.lineIndex;
        return m;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "$%.2f", v);
    }
}
