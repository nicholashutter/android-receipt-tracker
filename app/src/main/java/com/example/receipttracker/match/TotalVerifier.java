package com.example.receipttracker.match;


import androidx.annotation.Nullable;


import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ocr.DetectedNumber;


import java.util.ArrayList;

import java.util.Collections;

import java.util.HashMap;

import java.util.List;

import java.util.Locale;

import java.util.Map;


/**
 * Two-stage verification of a candidate receipt total, with an
 * entered-vs-circled cross-check and a sub+tax+tip sanity check.
 *
 * <p>Stage 1 ({@link PriceClassifier}): every detected number is
 * classified as "is this a price?" Dates, phone numbers, auth
 * codes, transaction IDs, and quantities are dropped.</p>
 *
 * <p>Stage 2 ({@link LinearLearner}): each remaining price is
 * scored for "is this the total?" — and the highest-probability
 * alternative is reported so the UI can show the user what the
 * model would have picked.</p>
 *
 * <p>Cross-check: if the user typed an amount in the amount field,
 * we re-run stage 1 + stage 2 on the entered value too, and compare
 * it against the marked one. Agreement boosts confidence;
 * disagreement triggers a sanity check.</p>
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

    private static final String LOG_TAG = "Verifier";
    private static final String PRICE_LOG_TAG = "PriceClf";
    private static final String LEARNER_LOG_TAG = "TotalLearner";

    private static final double TOL_STRICT = 0.10;
    private static final double TOL_TIGHT = 0.50;
    private static final double TOL_LOOSE = 1.00;
    private static final double PRICE_DELTA_TOLERANCE = 0.005;
    private static final double SUBTOTAL_FLOOR = 0.01;
    private static final double LINE_ITEM_MIN = 0.10;
    private static final int MIN_LINE_ITEMS_FOR_SUM = 2;

    private static final double CONF_NO_COMPONENTS = 0.40;
    private static final double CONF_DELTA_STRICT = 0.97;
    private static final double CONF_DELTA_TIGHT = 0.88;
    private static final double CONF_DELTA_LOOSE = 0.70;
    private static final double CONF_DELTA_RATIO = 0.55;
    private static final double CONF_DELTA_FLOOR = 0.30;
    private static final double DELTA_RATIO_THRESHOLD = 0.10;

    private static final double ALT_PRESSURE_MARGIN = 0.10;
    private static final double HIGH_CONFIDENCE_BUMP = 0.05;
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;
    private static final double DELTA_TIGHT_BUMP_THRESHOLD = 0.70;
    private static final double LOW_PRICE_PROB_THRESHOLD = 0.5;
    private static final double LOW_PRICE_PROB_PENALTY = 0.30;
    private static final double BELOW_SUBTOTAL_PENALTY = 0.20;
    private static final double BELOW_SUBTOTAL_FLOOR = 0.10;
    private static final double DISAGREE_PENALTY_BASE = 0.10;
    private static final double DISAGREE_PENALTY_SCALE = 0.10;
    private static final double MATCH_BOOST = 0.05;
    private static final double COMBINED_FLOOR = 0.0;
    private static final double COMBINED_MAX = 0.99;
    private static final double ENSEMBLE_MAX = 0.99;

    private static final double ENSEMBLE_WEIGHT_MAX_CONF = 0.55;
    private static final double ENSEMBLE_WEIGHT_AVG_CONF = 0.30;
    private static final double ENSEMBLE_WEIGHT_VOTE_SHARE = 0.15;
    private static final double ENSEMBLE_ADJUSTED_THRESHOLD = 0.10;
    private static final double CENTS_SCALE = 100.0;

    private static final String KEEPER_LABEL = "[PRICE]";
    private static final String DROP_LABEL = "[drop]";
    private static final String SYNTHETIC_LINE_TEXT = "(synthetic)";
    private static final String USER_TYPED_LINE_TEXT = "(user-typed)";
    private static final String NO_ENTERED_LABEL = "(none)";

    private static final String SRC_CIRCLED = "circled";
    private static final String SRC_ENTERED_SANITY = "entered (sanity wins)";
    private static final String SRC_CIRCLED_SANITY = "circled (sanity wins)";
    private static final String SRC_ENTERED_MODEL_SANITY = "entered (model + sanity)";
    private static final String SRC_CIRCLED_MODEL_SANITY = "circled (model + sanity)";
    private static final String SRC_MODEL_BEST_NO_MATCH = "model-best (neither matches sub+tax)";
    private static final String SRC_ENTERED_MODEL_PREFERS = "entered (model prefers it)";
    private static final String SRC_MODEL_BEST_NO_COMPONENTS = "model-best (no components)";


    private TotalVerifier() {}


    public static final class Result {

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

        // ---- ensemble (10-run consensus) ----
        /** How many of the top-N runs recommended the same total. */
        public final int ensembleVotesForWinner;

        /** The N used for the ensemble. 1 means "single run" (no ensemble). */
        public final int ensembleSize;

        /**
         * Consensus confidence derived from the ensemble. We take
         * the weighted average of the per-run confidences for the
         * winning total, where the weight is the run's own
         * P(isTotal). A run that was very sure of itself counts more
         * than a fence-sitter.
         */
        public final double ensembleConfidence;

        /** Short human-readable summary of the ensemble (e.g. "8/10 -> $47.83"). */
        public final String ensembleSummary;


        public Result(double total, double confidence, String reasoning, boolean wasAdjusted,
                      double priceProbability, double candidateProbability,
                      double bestAlternativeProbability, double modelChoice,
                      double enteredAmount, double enteredPriceProbability,
                      double enteredProbability, boolean enteredMatchesMarked,
                      String sanityCheck, double recommendedTotal,
                      String recommendedSource, double sanityDelta) {
            this(total, confidence, reasoning, wasAdjusted,
                    priceProbability, candidateProbability, bestAlternativeProbability,
                    modelChoice, enteredAmount, enteredPriceProbability, enteredProbability,
                    enteredMatchesMarked, sanityCheck, recommendedTotal, recommendedSource,
                    sanityDelta, 1, 1, confidence, "");
        }


        public Result(double total, double confidence, String reasoning, boolean wasAdjusted,
                      double priceProbability, double candidateProbability,
                      double bestAlternativeProbability, double modelChoice,
                      double enteredAmount, double enteredPriceProbability,
                      double enteredProbability, boolean enteredMatchesMarked,
                      String sanityCheck, double recommendedTotal,
                      String recommendedSource, double sanityDelta,
                      int ensembleVotesForWinner, int ensembleSize,
                      double ensembleConfidence, String ensembleSummary) {
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
            this.ensembleVotesForWinner = ensembleVotesForWinner;
            this.ensembleSize = ensembleSize;
            this.ensembleConfidence = ensembleConfidence;
            this.ensembleSummary = (ensembleSummary == null) ? "" : ensembleSummary;
        }
    }


    /** Overload for callers that don't have an entered amount. */
    public static Result verify(double candidate, List<DetectedNumber> allNumbers) {
        return verify(candidate, allNumbers, Double.NaN);
    }


    /**
     * Verifies a circled/marked candidate AND, if {@code enteredAmount}
     * is a real number, the user-typed amount. Cross-checks the two
     * and runs a sanity check against the heuristic sub+tax+tip
     * prediction.
     */
    public static Result verify(double candidate, List<DetectedNumber> allNumbers,
                                 double enteredAmount) {
        Logger.section("TOTAL VERIFY");
        logVerifyHeader(candidate, enteredAmount, allNumbers);

        // === STAGE 1: PriceClassifier ===
        Logger.section("STAGE 1: PRICE CLASSIFIER");
        // Touch the trained model so its weights are loaded even if
        // every other number is filtered out.
        PriceClassifier.getWeights();
        PriceClassifier.getBias();

        final Stage1Result stage1 = runStage1(candidate, allNumbers);
        final DetectedNumber candidateRecord = stage1.candidateRecord;
        final List<DetectedNumber> prices = stage1.prices;
        final double candPriceProb = stage1.candidatePriceProbability;

        // === HEURISTIC ON PRICES ===
        Logger.section("HEURISTIC ON PRICES");
        final HeuristicResult heuristic = runHeuristic(candidate, prices);
        final Double subtotal = heuristic.subtotal;
        final Double tax = heuristic.tax;
        final Double tip = heuristic.tip;
        final List<DetectedNumber> others = heuristic.others;
        final ComponentSum components = heuristic.components;
        final double predicted = components.predictedTotal;
        final int componentCount = components.componentCount;
        final double sanityDelta = components.sanityDelta;

        // === STAGE 2: LinearLearner ===
        Logger.section("STAGE 2: LINEAR LEARNER (on prices only)");
        LinearLearner.getWeights();
        LinearLearner.getBias();

        final int totalLines = maxLineIndex(allNumbers) + 1;
        final Stage2Result stage2 = runStage2(candidateRecord, others, prices,
                subtotal, tax, tip, totalLines);
        final double candProb = stage2.candidateProbability;
        final BestAlternative bestAlt = stage2.bestAlternative;

        // === ENTERED: same stage 1 + stage 2 against the user-typed value ===
        final boolean haveEntered = !Double.isNaN(enteredAmount) && enteredAmount > 0;
        final EnteredResult enteredResult = runEnteredIfPresent(haveEntered, enteredAmount,
                allNumbers, prices, subtotal, tax, tip, totalLines);
        final double enteredProb = enteredResult.enteredProbability;
        final double enteredPriceProb = enteredResult.enteredPriceProbability;

        // === Cross-check: entered vs circled ===
        final boolean enteredMatchesMarked = haveEntered
                && Math.abs(enteredAmount - candidate) <= TOL_STRICT;
        logCrossCheck(haveEntered, enteredAmount, candidate, enteredMatchesMarked);

        // === Sanity check: both vs sub+tax+tip AND vs items sum ===
        final LineItemSum lineItemSum = summarizeLineItems(others, subtotal, tax, tip);
        final double itemSumDelta = Math.abs(lineItemSum.sum - candidate);
        final SanityCheck sanityCheck = buildSanityCheck(haveEntered, enteredAmount,
                componentCount, candidate, predicted, sanityDelta,
                lineItemSum.sum, lineItemSum.count, itemSumDelta);
        Logger.i(LOG_TAG, "sanity: " + sanityCheck.text);
        if (lineItemSum.count >= 2) {
            Logger.i(LOG_TAG, String.format(Locale.US,
                    "items: sum=$%.2f  (n=%d, delta=$%.2f)",
                    lineItemSum.sum, lineItemSum.count, itemSumDelta));
        }

        // === Combine (circled) and decide what to recommend ===
        final double combinedConfidence = combineAndAdjust(
                candidate, predicted, componentCount, candProb, candPriceProb,
                bestAlt, subtotal, haveEntered, enteredMatchesMarked, enteredProb);
        final Recommendation recommendation = decideRecommendation(
                candidate, enteredAmount, predicted, haveEntered, enteredMatchesMarked,
                bestAlt, candProb, enteredProb, combinedConfidence);
        final boolean adjusted = (Math.abs(recommendation.total - candidate) >= PRICE_DELTA_TOLERANCE);

        final String reasoning = buildReasoning(candidate, candPriceProb, haveEntered,
                enteredAmount, enteredPriceProb, candProb, bestAlt, enteredProb,
                enteredMatchesMarked, sanityCheck.text, combinedConfidence, recommendation);

        final double modelChoice = (bestAlt.record != null && bestAlt.probability > candProb)
                ? bestAlt.value
                : candidate;

        Logger.i(LOG_TAG, String.format(Locale.US,
                "verdict: total=%.2f  confidence=%.2f  adjusted=%s  source=%s",
                recommendation.total, combinedConfidence, adjusted, recommendation.source));
        Logger.i(LOG_TAG, "reason: " + reasoning);
        Logger.section("TOTAL VERIFY END");

        return new Result(recommendation.total, combinedConfidence, reasoning.toString(),
                adjusted,
                candPriceProb, candProb, bestAlt.probability,
                modelChoice,
                enteredResult.enteredAmount, enteredResult.enteredPriceProbability, enteredResult.enteredProbability,
                enteredMatchesMarked,
                sanityCheck.text,
                recommendation.total,
                recommendation.source,
                sanityDelta);
    }


    private static void logVerifyHeader(double candidate, double enteredAmount,
                                        List<DetectedNumber> allNumbers) {
        final String enteredLabel;
        if (Double.isNaN(enteredAmount)) {
            enteredLabel = NO_ENTERED_LABEL;
        } else {
            enteredLabel = fmt(enteredAmount);
        }
        final int allCount;
        if (allNumbers == null) {
            allCount = 0;
        } else {
            allCount = allNumbers.size();
        }
        Logger.i(LOG_TAG, "candidate(circled)=" + candidate
                + "  entered=" + enteredLabel
                + "  (all numbers: " + allCount + ")");
    }


    // ============ Stage 1: PriceClassifier ============

    private static final class Stage1Result {
        final DetectedNumber candidateRecord;
        final List<DetectedNumber> prices;
        final double candidatePriceProbability;

        Stage1Result(DetectedNumber candidateRecord, List<DetectedNumber> prices,
                     double candidatePriceProbability) {
            this.candidateRecord = candidateRecord;
            this.prices = prices;
            this.candidatePriceProbability = candidatePriceProbability;
        }
    }


    private static Stage1Result runStage1(double candidate, List<DetectedNumber> allNumbers) {
        final List<DetectedNumber> safeAll = (allNumbers == null)
                ? Collections.emptyList() : allNumbers;

        final DetectedNumber candidateRecord = ensureCandidateRecord(safeAll, candidate);

        final List<DetectedNumber> prices = new ArrayList<>();
        for (final DetectedNumber detected : safeAll) {
            final double[] features = PriceClassifier.extractFeatures(detected);
            final double probability = PriceClassifier.predictProbability(features);
            final boolean keep = probability >= PriceClassifier.PRICE_THRESHOLD;

            final String keepLabel;
            if (keep) {
                keepLabel = KEEPER_LABEL;
            } else {
                keepLabel = DROP_LABEL;
            }
            Logger.i(PRICE_LOG_TAG, String.format(Locale.US,
                    "  $%.2f (line %d, kw=%s)  P(isPrice)=%.3f  %s",
                    detected.value, detected.lineIndex, detected.keyword, probability, keepLabel));

            if (keep) {
                prices.add(detected);
            }
        }
        Logger.i(PRICE_LOG_TAG, "kept " + prices.size() + " of " + safeAll.size() + " numbers as prices");

        final double candidatePriceProb = PriceClassifier.predictProbability(
                PriceClassifier.extractFeatures(candidateRecord));
        Logger.i(PRICE_LOG_TAG, String.format(Locale.US,
                "candidate(circled) $%.2f  P(isPrice)=%.3f", candidate, candidatePriceProb));

        return new Stage1Result(candidateRecord, prices, candidatePriceProb);
    }


    private static DetectedNumber ensureCandidateRecord(List<DetectedNumber> allNumbers, double candidate) {
        final DetectedNumber found = findLineWithValue(allNumbers, candidate);
        if (found != null) return found;
        return new DetectedNumber(candidate, SYNTHETIC_LINE_TEXT, 0, null);
    }


    // ============ Heuristic: pick components, build predicted total ============

    private static final class HeuristicResult {
        final Double subtotal;
        final Double tax;
        final Double tip;
        final List<DetectedNumber> others;
        final ComponentSum components;

        HeuristicResult(Double subtotal, Double tax, Double tip,
                        List<DetectedNumber> others, ComponentSum components) {
            this.subtotal = subtotal;
            this.tax = tax;
            this.tip = tip;
            this.others = others;
            this.components = components;
        }
    }


    private static final class ComponentSum {
        double predictedTotal;
        int componentCount;
        double sanityDelta;
        final StringBuilder expression = new StringBuilder();

        ComponentSum() { this.sanityDelta = Double.NaN; }
    }


    private static HeuristicResult runHeuristic(double candidate, List<DetectedNumber> prices) {
        final List<DetectedNumber> others = buildOthersList(candidate, prices);

        final Double subtotal = pickOne(others, "subtotal");
        final Double tax = pickOne(others, "tax");
        final Double tip = pickOne(others, "tip");
        Logger.i(LOG_TAG, "components: subtotal=" + subtotal
                + "  tax=" + tax + "  tip=" + tip);

        final ComponentSum components = buildComponentSum(others, candidate, subtotal, tax, tip);
        Logger.i(LOG_TAG, "predicted(sub+tax+tip)=" + fmt(components.predictedTotal)
                + "  expr=" + components.expression);

        return new HeuristicResult(subtotal, tax, tip, others, components);
    }


    private static List<DetectedNumber> buildOthersList(double candidate, List<DetectedNumber> prices) {
        final List<DetectedNumber> others = new ArrayList<>();
        for (final DetectedNumber detected : prices) {
            if (Math.abs(detected.value - candidate) < PRICE_DELTA_TOLERANCE) continue;
            others.add(detected);
        }
        Collections.sort(others, (a, b) -> Double.compare(a.value, b.value));
        return others;
    }


    private static ComponentSum buildComponentSum(List<DetectedNumber> others, double candidate,
                                                 Double subtotal, Double tax, Double tip) {
        final ComponentSum sum = new ComponentSum();
        appendLabeledComponent(sum, "subtotal", subtotal);
        appendLabeledComponent(sum, "tax", tax);
        appendLabeledComponent(sum, "tip", tip);
        if (sum.componentCount == 0) {
            tryLineItemSumFallback(sum, others, candidate);
        }
        return sum;
    }


    private static void appendLabeledComponent(ComponentSum sum, String label, Double value) {
        if (value == null) return;
        sum.predictedTotal += value;
        sum.componentCount++;
        if (sum.expression.length() > 0) {
            sum.expression.append(" + ");
        }
        sum.expression.append(label).append("(").append(fmt(value)).append(")");
    }


    private static void tryLineItemSumFallback(ComponentSum sum, List<DetectedNumber> others, double candidate) {
        double lineItemSum = 0.0;
        int itemCount = 0;
        for (final DetectedNumber detected : others) {
            if (detected.value < candidate && detected.value > LINE_ITEM_MIN) {
                lineItemSum += detected.value;
                itemCount++;
            }
        }
        if (itemCount >= MIN_LINE_ITEMS_FOR_SUM) {
            sum.predictedTotal = lineItemSum;
            sum.componentCount = 1;
            sum.sanityDelta = Double.NaN;  // sanity is skipped for line-item-sum total
            sum.expression.setLength(0);
            sum.expression.append("lineItemsSum(").append(fmt(lineItemSum)).append(")");
        }
    }


    // ============ Stage 2: LinearLearner ============

    private static final class Stage2Result {
        final double candidateProbability;
        final BestAlternative bestAlternative;

        Stage2Result(double candidateProbability, BestAlternative bestAlternative) {
            this.candidateProbability = candidateProbability;
            this.bestAlternative = bestAlternative;
        }
    }


    private static final class BestAlternative {
        final double probability;
        final double value;
        @Nullable final DetectedNumber record;

        BestAlternative(double probability, double value, @Nullable DetectedNumber record) {
            this.probability = probability;
            this.value = value;
            this.record = record;
        }
    }


    private static Stage2Result runStage2(DetectedNumber candidateRecord,
                                          List<DetectedNumber> others,
                                          List<DetectedNumber> prices,
                                          Double subtotal, Double tax, Double tip,
                                          int totalLines) {
        final double[] candidateFeatures = LinearLearner.extractFeatures(
                candidateRecord, prices, subtotal, tax, tip, totalLines);
        final double candLogit = LinearLearner.predictLogit(candidateFeatures);
        final double candProb = LinearLearner.predictProbability(candidateFeatures);
        Logger.i(LEARNER_LOG_TAG, "candidate(circled)=" + fmt(candidateRecord.value)
                + "  logit=" + String.format(Locale.US, "%+.3f", candLogit)
                + "  P=" + String.format(Locale.US, "%.3f", candProb));

        final BestAlternative bestAlt = findBestAlternative(others, prices, subtotal, tax, tip, totalLines);
        Logger.i(LEARNER_LOG_TAG, String.format(Locale.US,
                "best alternative: $%.2f  P=%.3f", bestAlt.value, bestAlt.probability));

        return new Stage2Result(candProb, bestAlt);
    }


    private static BestAlternative findBestAlternative(List<DetectedNumber> others,
                                                      List<DetectedNumber> prices,
                                                      Double subtotal, Double tax, Double tip,
                                                      int totalLines) {
        double bestProb = 0.0;
        double bestValue = others.isEmpty() ? 0.0 : others.get(0).value;
        DetectedNumber bestRecord = null;

        for (final DetectedNumber other : others) {
            final double[] features = LinearLearner.extractFeatures(
                    other, prices, subtotal, tax, tip, totalLines);
            final double probability = LinearLearner.predictProbability(features);
            Logger.i(LEARNER_LOG_TAG, String.format(Locale.US,
                    "  price=$%.2f (line %d, kw=%s)  P(isTotal)=%.3f",
                    other.value, other.lineIndex, other.keyword, probability));
            if (probability > bestProb) {
                bestProb = probability;
                bestValue = other.value;
                bestRecord = other;
            }
        }
        return new BestAlternative(bestProb, bestValue, bestRecord);
    }


    // ============ Entered-vs-circled cross-check ============

    private static final class EnteredResult {
        final double enteredProbability;
        final double enteredPriceProbability;
        final double enteredAmount;

        EnteredResult(double enteredProbability, double enteredPriceProbability,
                      double enteredAmount) {
            this.enteredProbability = enteredProbability;
            this.enteredPriceProbability = enteredPriceProbability;
            this.enteredAmount = enteredAmount;
        }
    }


    private static EnteredResult runEnteredIfPresent(boolean haveEntered, double enteredAmount,
                                                     List<DetectedNumber> allNumbers,
                                                     List<DetectedNumber> prices,
                                                     Double subtotal, Double tax, Double tip,
                                                     int totalLines) {
        if (!haveEntered) {
            return new EnteredResult(Double.NaN, Double.NaN, Double.NaN);
        }
        final DetectedNumber enteredRecord = ensureEnteredRecord(allNumbers, enteredAmount);
        final double enteredPriceProb = PriceClassifier.predictProbability(
                PriceClassifier.extractFeatures(enteredRecord));
        Logger.i(PRICE_LOG_TAG, String.format(Locale.US,
                "candidate(entered) $%.2f  P(isPrice)=%.3f", enteredAmount, enteredPriceProb));

        final double[] enteredFeatures = LinearLearner.extractFeatures(
                enteredRecord, prices, subtotal, tax, tip, totalLines);
        final double enteredProb = LinearLearner.predictProbability(enteredFeatures);
        Logger.i(LEARNER_LOG_TAG, String.format(Locale.US,
                "candidate(entered) $%.2f  P(isTotal)=%.3f", enteredAmount, enteredProb));

        return new EnteredResult(enteredProb, enteredPriceProb, enteredAmount);
    }


    private static DetectedNumber ensureEnteredRecord(List<DetectedNumber> allNumbers, double enteredAmount) {
        final DetectedNumber found = findLineWithValue(allNumbers, enteredAmount);
        if (found != null) return found;
        return new DetectedNumber(enteredAmount, USER_TYPED_LINE_TEXT, 0, null);
    }


    private static void logCrossCheck(boolean haveEntered, double enteredAmount,
                                      double candidate, boolean enteredMatchesMarked) {
        final String enteredDisplay;
        if (haveEntered) {
            enteredDisplay = fmt(enteredAmount);
        } else {
            enteredDisplay = NO_ENTERED_LABEL;
        }
        Logger.i(LOG_TAG, String.format(Locale.US,
                "cross-check: entered=%s  circled=%s  match=%s",
                enteredDisplay, fmt(candidate), enteredMatchesMarked));
    }


    // ============ Sanity check ============

    private static final class SanityCheck {
        final String text;
        final double sanityDelta;

        SanityCheck(String text, double sanityDelta) {
            this.text = text;
            this.sanityDelta = sanityDelta;
        }
    }


    private static SanityCheck buildSanityCheck(boolean haveEntered, double enteredAmount,
                                               int componentCount, double candidate,
                                               double predicted, double sanityDelta,
                                               double itemSum, int itemCount,
                                               double itemSumDelta) {
        if (componentCount == 0 && itemCount == 0) {
            return new SanityCheck("no subtotal/tax/tip labels or line items — sanity check skipped", Double.NaN);
        }

        final StringBuilder msg = new StringBuilder();
        boolean firstClause = true;

        if (componentCount > 0) {
            msg.append(buildSubPlusTaxMessage(haveEntered, enteredAmount, candidate, predicted));
            firstClause = false;
        }

        if (itemCount >= 2) {
            // Only report the items-sum check when we have at least 2
            // line items — a single item could be a total itself and
            // "sum == total" would be trivially true.
            if (!firstClause) {
                msg.append("; ");
            }

            final String agreeLabel;
            if (itemSumDelta <= TOL_STRICT) {
                agreeLabel = "agrees";
            } else if (itemSumDelta <= TOL_LOOSE) {
                agreeLabel = "close";
            } else {
                agreeLabel = "disagrees";
            }

            msg.append(String.format(Locale.US,
                    "items sum=%s  (circled delta=$%.2f, %s)",
                    fmt(itemSum), itemSumDelta, agreeLabel));
        }

        return new SanityCheck(msg.toString(), sanityDelta);
    }


    private static String buildSubPlusTaxMessage(boolean haveEntered, double enteredAmount,
                                                double candidate, double predicted) {
        final StringBuilder msg = new StringBuilder();
        msg.append(String.format(Locale.US, "sub+tax+tip=%s  (circled delta=$%.2f",
                fmt(predicted), Math.abs(candidate - predicted)));
        if (haveEntered) {
            msg.append(String.format(Locale.US, ", entered delta=$%.2f",
                    Math.abs(enteredAmount - predicted)));
        }
        msg.append(")");
        return msg.toString();
    }


    /**
     * Sums the "line item" prices on a receipt — the prices that aren't
     * the candidate, aren't subtotal/tax/tip, and look like real prices
     * (have two decimal places). Used as a free consistency check: a
     * receipt's total should match the sum of its line items.
     *
     * <p>Returns {@code <0, 0, 0>} when no line items are found.</p>
     */
    private static LineItemSum summarizeLineItems(List<DetectedNumber> others,
                                                  Double subtotal, Double tax, Double tip) {
        double sum = 0.0;
        int count = 0;

        for (final DetectedNumber number : others) {
            if (number.keyword != null) {
                continue;
            }

            if (!looksLikeRealPrice(number.value)) {
                continue;
            }

            // Skip values that match a known component — they're already
            // accounted for in the sub+tax+tip sum, not in the line items.
            if (subtotal != null && approxEquals(number.value, subtotal)) {
                continue;
            }

            if (tax != null && approxEquals(number.value, tax)) {
                continue;
            }

            if (tip != null && approxEquals(number.value, tip)) {
                continue;
            }

            sum += number.value;
            count++;
        }

        return new LineItemSum(sum, count);
    }


    private static boolean looksLikeRealPrice(double value) {
        // Two decimal places and positive. Catches the "5.00" of a real
        // price while skipping integers like a quantity "3" or a code "133337".
        if (value <= 0.0) {
            return false;
        }

        final double cents = value * 100.0;
        return Math.abs(cents - Math.round(cents)) < 0.005;
    }


    private static boolean approxEquals(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }


    /** Tuple of (sum, count) returned by {@link #summarizeLineItems}. */
    private static final class LineItemSum {
        final double sum;
        final int count;

        LineItemSum(double sum, int count) {
            this.sum = sum;
            this.count = count;
        }
    }


    // ============ Combine + adjust + recommend ============

    private static double combineAndAdjust(double candidate, double predicted, int componentCount,
                                          double candProb, double candPriceProb,
                                          BestAlternative bestAlt, Double subtotal,
                                          boolean haveEntered, boolean enteredMatchesMarked,
                                          double enteredProb) {
        final double delta = Math.abs(candidate - predicted);
        double combined = (deltaConfidence(componentCount, predicted, delta) + candProb) / 2.0;
        combined = applyAltPressure(combined, candProb, bestAlt.probability);
        combined = applyHighlightBoost(combined, candProb, bestAlt.probability);
        combined = applyBelowSubtotalPenalty(combined, candidate, subtotal);
        combined = applyDeltaBump(combined, delta, candProb);
        combined = applyLowPriceProbPenalty(combined, candPriceProb);
        combined = applyDisagreePenalty(combined, haveEntered, enteredMatchesMarked, candProb, enteredProb);
        combined = applyMatchBoost(combined, enteredMatchesMarked);
        return clampConfidence(combined);
    }


    private static double deltaConfidence(int componentCount, double predicted, double delta) {
        if (componentCount == 0) return CONF_NO_COMPONENTS;
        if (delta <= TOL_STRICT) return CONF_DELTA_STRICT;
        if (delta <= TOL_TIGHT) return CONF_DELTA_TIGHT;
        if (delta <= TOL_LOOSE) return CONF_DELTA_LOOSE;
        if (predicted > 0 && delta <= predicted * DELTA_RATIO_THRESHOLD) return CONF_DELTA_RATIO;
        return CONF_DELTA_FLOOR;
    }


    private static double applyAltPressure(double combined, double candProb, double bestAltProb) {
        final double altPressure = Math.max(0.0, bestAltProb - candProb - ALT_PRESSURE_MARGIN);
        return Math.max(COMBINED_FLOOR, Math.min(COMBINED_MAX, combined - altPressure));
    }


    private static double applyHighlightBoost(double combined, double candProb, double bestAltProb) {
        if (candProb >= HIGH_CONFIDENCE_THRESHOLD && bestAltProb <= candProb) {
            return Math.min(COMBINED_MAX, combined + HIGH_CONFIDENCE_BUMP);
        }
        return combined;
    }


    private static double applyBelowSubtotalPenalty(double combined, double candidate, Double subtotal) {
        if (subtotal != null && candidate < subtotal - SUBTOTAL_FLOOR) {
            return Math.max(BELOW_SUBTOTAL_FLOOR, combined - BELOW_SUBTOTAL_PENALTY);
        }
        return combined;
    }


    private static double applyDeltaBump(double combined, double delta, double candProb) {
        if (delta <= TOL_LOOSE && candProb >= DELTA_TIGHT_BUMP_THRESHOLD) {
            return Math.min(COMBINED_MAX, combined + HIGH_CONFIDENCE_BUMP);
        }
        return combined;
    }


    private static double applyLowPriceProbPenalty(double combined, double candPriceProb) {
        if (candPriceProb < LOW_PRICE_PROB_THRESHOLD) {
            return Math.max(0.05, combined - LOW_PRICE_PROB_PENALTY);
        }
        return combined;
    }


    private static double applyDisagreePenalty(double combined, boolean haveEntered,
                                              boolean enteredMatchesMarked,
                                              double candProb, double enteredProb) {
        if (haveEntered && !enteredMatchesMarked) {
            final double disagreeDelta = Math.abs(enteredProb - candProb);
            return Math.max(COMBINED_FLOOR,
                    combined - DISAGREE_PENALTY_BASE - DISAGREE_PENALTY_SCALE * disagreeDelta);
        }
        return combined;
    }


    private static double applyMatchBoost(double combined, boolean enteredMatchesMarked) {
        if (enteredMatchesMarked) {
            return Math.min(COMBINED_MAX, combined + MATCH_BOOST);
        }
        return combined;
    }


    private static double clampConfidence(double confidence) {
        return Math.max(COMBINED_FLOOR, Math.min(COMBINED_MAX, confidence));
    }


    // ============ Recommendation ============

    private static final class Recommendation {
        final double total;
        final String source;

        Recommendation(double total, String source) {
            this.total = total;
            this.source = source;
        }
    }


    private static Recommendation decideRecommendation(double candidate, double enteredAmount,
                                                      double predicted,
                                                      boolean haveEntered,
                                                      boolean enteredMatchesMarked,
                                                      BestAlternative bestAlt,
                                                      double candProb, double enteredProb,
                                                      double combinedConfidence) {
        // Default: trust the circled value.
        if (!(haveEntered && !enteredMatchesMarked)) {
            if (shouldPickModelBestForNoComponents(combinedConfidence, bestAlt, candProb)) {
                return new Recommendation(bestAlt.value, SRC_MODEL_BEST_NO_COMPONENTS);
            }
            return new Recommendation(candidate, SRC_CIRCLED);
        }
        return recommendOnDisagreement(candidate, enteredAmount, predicted, bestAlt, candProb, enteredProb);
    }


    private static boolean shouldPickModelBestForNoComponents(double combinedConfidence,
                                                             BestAlternative bestAlt, double candProb) {
        return combinedConfidence < 0.55
                && bestAlt.record != null
                && bestAlt.probability >= 0.70
                && bestAlt.probability > candProb + ALT_PRESSURE_MARGIN;
    }


    private static Recommendation recommendOnDisagreement(double candidate, double enteredAmount,
                                                        double predicted,
                                                        BestAlternative bestAlt,
                                                        double candProb, double enteredProb) {
        final double circledSanityDelta = Math.abs(candidate - predicted);
        final double enteredSanityDelta = Math.abs(enteredAmount - predicted);
        final boolean sanityHelpsCircled = (circledSanityDelta <= enteredSanityDelta)
                && (circledSanityDelta <= TOL_LOOSE);
        final boolean sanityHelpsEntered = (enteredSanityDelta <= circledSanityDelta)
                && (enteredSanityDelta <= TOL_LOOSE);

        if (sanityHelpsCircled && !sanityHelpsEntered) {
            return new Recommendation(candidate, SRC_CIRCLED_SANITY);
        }
        if (sanityHelpsEntered && !sanityHelpsCircled) {
            return new Recommendation(enteredAmount, SRC_ENTERED_SANITY);
        }
        if (sanityHelpsCircled && sanityHelpsEntered) {
            return recommendOnBothSane(candidate, enteredAmount, candProb, enteredProb);
        }
        return recommendOnNeitherSane(candidate, enteredAmount, bestAlt, candProb, enteredProb);
    }


    private static Recommendation recommendOnBothSane(double candidate, double enteredAmount,
                                                    double candProb, double enteredProb) {
        if (enteredProb > candProb + ALT_PRESSURE_MARGIN) {
            return new Recommendation(enteredAmount, SRC_ENTERED_MODEL_SANITY);
        }
        return new Recommendation(candidate, SRC_CIRCLED_MODEL_SANITY);
    }


    private static Recommendation recommendOnNeitherSane(double candidate, double enteredAmount,
                                                        BestAlternative bestAlt,
                                                        double candProb, double enteredProb) {
        if (bestAlt.record != null && bestAlt.probability > Math.max(candProb, enteredProb)) {
            return new Recommendation(bestAlt.value, SRC_MODEL_BEST_NO_MATCH);
        }
        if (enteredProb > candProb) {
            return new Recommendation(enteredAmount, SRC_ENTERED_MODEL_PREFERS);
        }
        return new Recommendation(candidate, SRC_CIRCLED);
    }


    // ============ Reasoning string ============

    private static String buildReasoning(double candidate, double candPriceProb,
                                         boolean haveEntered, double enteredAmount,
                                         double enteredPriceProb, double candProb,
                                         BestAlternative bestAlt, double enteredProb,
                                         boolean enteredMatchesMarked,
                                         String sanityCheckText,
                                         double combinedConfidence,
                                         Recommendation recommendation) {
        final StringBuilder reason = new StringBuilder();
        appendStage1Lines(reason, candidate, candPriceProb, haveEntered, enteredAmount, enteredPriceProb);
        appendStage2Lines(reason, candidate, candProb, bestAlt, haveEntered, enteredAmount, enteredProb);
        appendCrossCheckLines(reason, haveEntered, enteredMatchesMarked);
        appendSanityAndSummary(reason, sanityCheckText, combinedConfidence, recommendation);
        return reason.toString();
    }


    private static void appendStage1Lines(StringBuilder reason, double candidate, double candPriceProb,
                                          boolean haveEntered, double enteredAmount,
                                          double enteredPriceProb) {
        reason.append("Stage 1 (PriceClf):\n");
        reason.append(String.format(Locale.US, "  circled $%.2f  P(isPrice)=%.2f%n", candidate, candPriceProb));
        if (haveEntered) {
            reason.append(String.format(Locale.US,
                    "  entered $%.2f  P(isPrice)=%.2f%n", enteredAmount, enteredPriceProb));
        }
    }


    private static void appendStage2Lines(StringBuilder reason, double candidate, double candProb,
                                          BestAlternative bestAlt, boolean haveEntered,
                                          double enteredAmount, double enteredProb) {
        reason.append("Stage 2 (TotalLearner):\n");
        reason.append(String.format(Locale.US,
                "  circled $%.2f  P(isTotal)=%.2f  (best other: $%.2f P=%.2f)%n",
                candidate, candProb, bestAlt.value, bestAlt.probability));
        if (haveEntered) {
            reason.append(String.format(Locale.US,
                    "  entered $%.2f  P(isTotal)=%.2f%n", enteredAmount, enteredProb));
        }
    }


    private static void appendCrossCheckLines(StringBuilder reason, boolean haveEntered,
                                             boolean enteredMatchesMarked) {
        reason.append("Cross-check:\n");
        if (!haveEntered) {
            reason.append("  (no entered value provided)\n");
            return;
        }
        if (enteredMatchesMarked) {
            reason.append("  OK entered and circled agree to within " + fmt(TOL_STRICT) + "\n");
        } else {
            reason.append("  WARN entered and circled differ — sanity check decides\n");
        }
    }


    private static void appendSanityAndSummary(StringBuilder reason, String sanityCheckText,
                                                double combinedConfidence,
                                                Recommendation recommendation) {
        reason.append("Sanity check:  ").append(sanityCheckText).append("\n");
        reason.append(String.format(Locale.US,
                "Combined:  %.2f  ->  recommended %.2f (%s)%n",
                combinedConfidence, recommendation.total, recommendation.source));
    }


    // ============ Ensemble (10-run consensus) ============

    /**
     * The size of the default ensemble. The pipeline runs the full
     * verifier against this many of the top candidates and votes on
     * the final answer. 10 was picked because the OCR detector
     * typically surfaces ~10 "plausible" totals on a noisy receipt
     * (subtotal, tax, tip, line items, suggested tip, etc.) and we
     * want a full panel vote across all of them.
     */
    public static final int DEFAULT_ENSEMBLE_SIZE = 10;


    /**
     * Runs the verifier on the top {@code ensembleSize} candidates by
     * P(isTotal) and returns a single combined Result that reflects
     * the panel's consensus. This is the "10 runs" version of the
     * pipeline: instead of trusting one candidate, we trust
     * whatever most of the top candidates agree on.
     *
     * <p>The "confidence" of the returned Result is the per-run
     * confidence of the candidate that won the vote, blended with
     * the vote share (more votes = higher confidence).</p>
     *
     * <p>Cost: {@code ensembleSize} full {@link #verify} calls. On a
     * real device that's well under a second for 10 candidates.</p>
     */
    public static Result verifyEnsemble(double seedCandidate, List<DetectedNumber> allNumbers,
                                        double enteredAmount, int ensembleSize) {
        if (allNumbers == null || allNumbers.isEmpty()) {
            return verify(seedCandidate, allNumbers, enteredAmount);
        }
        if (ensembleSize <= 1) {
            return verify(seedCandidate, allNumbers, enteredAmount);
        }

        Logger.section("ENSEMBLE VERIFY (N=" + ensembleSize + ")");

        // Step 1: run PriceClassifier on every number once.
        final List<DetectedNumber> prices = filterToPrices(allNumbers);

        // Step 2: score every price with LinearLearner and rank.
        final Double subtotal = pickOne(prices, "subtotal");
        final Double tax = pickOne(prices, "tax");
        final Double tip = pickOne(prices, "tip");
        final int totalLines = maxLineIndex(allNumbers) + 1;
        final List<ScoredCandidate> scored = scoreAndRank(prices, subtotal, tax, tip, totalLines);
        final int candidateCount = Math.min(ensembleSize, scored.size());
        if (candidateCount < 1) {
            return verify(seedCandidate, allNumbers, enteredAmount);
        }

        // Step 3: full verify on the top N, count votes on recommendedTotal.
        // Use a small tolerance ($0.01) for matching recommended totals.
        final Map<Long, double[]> voteBuckets = new HashMap<>();
        final List<Result> runs = new ArrayList<>();
        for (int i = 0; i < candidateCount; i++) {
            final double candidate = scored.get(i).value;
            final Result runResult = verify(candidate, allNumbers, enteredAmount);
            runs.add(runResult);
            final long key = Math.round(runResult.recommendedTotal * CENTS_SCALE);
            double[] bucket = voteBuckets.get(key);
            if (bucket == null) {
                bucket = new double[]{0.0, 0.0};
                voteBuckets.put(key, bucket);
            }
            bucket[0] += 1.0;
            bucket[1] += runResult.confidence; // sum, divide by votes at the end
        }

        // Step 4: pick the bucket with the most votes.
        final VoteWinner winner = pickVoteWinner(voteBuckets);
        final int winnerVotes = winner.votes;
        final long winnerKey = winner.key;

        // Find the actual Result that produced the winning total (the
        // first one whose recommendedTotal matches the winner key,
        // gives the most verbose reasoning string).
        final Result winning = findWinningResult(runs, winnerKey);
        final double[] winningBucket = voteBuckets.get(winnerKey);
        final double weightedAvgConfidence = winningBucket[1] / winningBucket[0];
        final double voteShare = (double) winnerVotes / candidateCount;

        final double maxConfForWinner = maxConfidenceAmongWinners(runs, winnerKey);
        final double ensembleConf = clampEnsemble(
                ENSEMBLE_WEIGHT_MAX_CONF * maxConfForWinner
                        + ENSEMBLE_WEIGHT_AVG_CONF * weightedAvgConfidence
                        + ENSEMBLE_WEIGHT_VOTE_SHARE * voteShare);

        final String summary = String.format(Locale.US,
                "%d/%d runs voted $%.2f  max-conf=%.2f  avg-conf=%.2f  votes=%.0f%%",
                winnerVotes, candidateCount, winning.recommendedTotal, maxConfForWinner,
                weightedAvgConfidence, voteShare * 100);
        Logger.i(LOG_TAG, "ENSEMBLE: " + summary);
        logPerRun(runs, voteBuckets);
        Logger.section("ENSEMBLE VERIFY END");

        return new Result(winning.recommendedTotal, ensembleConf, winning.reasoning, winning.wasAdjusted,
                winning.priceProbability, winning.candidateProbability, winning.bestAlternativeProbability,
                winning.modelChoice, winning.enteredAmount, winning.enteredPriceProbability,
                winning.enteredProbability, winning.enteredMatchesMarked, winning.sanityCheck,
                winning.recommendedTotal, winning.recommendedSource, winning.sanityDelta,
                winnerVotes, candidateCount, ensembleConf, summary);
    }


    private static List<DetectedNumber> filterToPrices(List<DetectedNumber> allNumbers) {
        final List<DetectedNumber> prices = new ArrayList<>();
        for (final DetectedNumber detected : allNumbers) {
            final double p = PriceClassifier.predictProbability(
                    PriceClassifier.extractFeatures(detected));
            if (p >= PriceClassifier.PRICE_THRESHOLD) {
                prices.add(detected);
            }
        }
        return prices;
    }


    private static final class ScoredCandidate {
        final double value;
        final double probability;
        final double hasTotalFeature;
        final double hasComponentFeature;

        ScoredCandidate(double value, double probability, double hasTotalFeature, double hasComponentFeature) {
            this.value = value;
            this.probability = probability;
            this.hasTotalFeature = hasTotalFeature;
            this.hasComponentFeature = hasComponentFeature;
        }
    }


    private static List<ScoredCandidate> scoreAndRank(List<DetectedNumber> prices,
                                                      Double subtotal, Double tax, Double tip,
                                                      int totalLines) {
        final List<ScoredCandidate> scored = new ArrayList<>();
        for (final DetectedNumber detected : prices) {
            final double[] features = LinearLearner.extractFeatures(
                    detected, prices, subtotal, tax, tip, totalLines);
            final double probability = LinearLearner.predictProbability(features);
            scored.add(new ScoredCandidate(detected.value, probability, features[0], features[1]));
        }
        scored.sort((a, b) -> Double.compare(b.probability, a.probability)); // desc
        return scored;
    }


    private static final class VoteWinner {
        final long key;
        final int votes;

        VoteWinner(long key, int votes) {
            this.key = key;
            this.votes = votes;
        }
    }


    private static VoteWinner pickVoteWinner(Map<Long, double[]> voteBuckets) {
        long winnerKey = 0L;
        int winnerVotes = -1;
        for (final Map.Entry<Long, double[]> entry : voteBuckets.entrySet()) {
            final int votes = (int) entry.getValue()[0];
            if (votes > winnerVotes) {
                winnerVotes = votes;
                winnerKey = entry.getKey();
            }
        }
        return new VoteWinner(winnerKey, winnerVotes);
    }


    private static Result findWinningResult(List<Result> runs, long winnerKey) {
        for (final Result run : runs) {
            if (Math.round(run.recommendedTotal * CENTS_SCALE) == winnerKey) {
                return run;
            }
        }
        return runs.get(0);
    }


    private static double maxConfidenceAmongWinners(List<Result> runs, long winnerKey) {
        double maxConf = 0.0;
        for (final Result run : runs) {
            if (Math.round(run.recommendedTotal * CENTS_SCALE) == winnerKey
                    && run.confidence > maxConf) {
                maxConf = run.confidence;
            }
        }
        return maxConf;
    }


    private static double clampEnsemble(double confidence) {
        return Math.max(COMBINED_FLOOR, Math.min(ENSEMBLE_MAX, confidence));
    }


    private static void logPerRun(List<Result> runs, Map<Long, double[]> voteBuckets) {
        for (final Result run : runs) {
            final long key = Math.round(run.recommendedTotal * CENTS_SCALE);
            final double[] bucket = voteBuckets.get(key);
            Logger.i(LOG_TAG, String.format(Locale.US,
                    "  run: cand=$%.2f  rec=$%.2f  conf=%.2f  votes-for-rec=%d  source=%s",
                    run.total, run.recommendedTotal, run.confidence, (int) bucket[0], run.recommendedSource));
        }
    }


    // ============ Helpers ============

    @Nullable
    private static Double pickOne(List<DetectedNumber> numbers, String keyword) {
        Double found = null;
        for (final DetectedNumber detected : numbers) {
            if (keyword.equals(detected.keyword)) {
                found = detected.value;
            }
        }
        return found;
    }


    @Nullable
    private static DetectedNumber findLineWithValue(List<DetectedNumber> all, double value) {
        if (all == null) return null;
        for (final DetectedNumber detected : all) {
            if (Math.abs(detected.value - value) < PRICE_DELTA_TOLERANCE) {
                return detected;
            }
        }
        return null;
    }


    private static int maxLineIndex(List<DetectedNumber> all) {
        if (all == null) return 0;
        int max = 0;
        for (final DetectedNumber detected : all) {
            if (detected.lineIndex > max) max = detected.lineIndex;
        }
        return max;
    }


    private static String fmt(double value) {
        return String.format(Locale.US, "$%.2f", value);
    }
}
