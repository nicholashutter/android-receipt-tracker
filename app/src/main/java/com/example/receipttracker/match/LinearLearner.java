package com.example.receipttracker.match;


import androidx.annotation.NonNull;


import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ocr.DetectedNumber;


import java.util.ArrayList;

import java.util.List;

import java.util.Locale;


/**
 * Stage 2 of the two-stage receipt-classifier: given a number that's
 * already known to be a price (see {@link PriceClassifier}), what's
 * the probability it's the receipt's total?
 *
 * <p>Implemented as a small logistic-regression classifier trained
 * on synthetic receipts. Feature vector:</p>
 * <ul>
 *   <li>{@code hasTotalKeyword}     – 1 if the line has "total / amount / due / balance"</li>
 *   <li>{@code hasComponentKeyword} – 1 if the line has "subtotal / tax / tip"</li>
 *   <li>{@code isLargest}           – 1 if the value is the largest in the receipt</li>
 *   <li>{@code lineInBottomHalf}    – 1 if the value's line is in the bottom half of the OCR text</li>
 *   <li>{@code hasDecimal}          – 1 if the value has a decimal point (e.g. 5.99 not 6)</li>
 *   <li>{@code belowSubtotal}       – 1 if the value is smaller than a detected subtotal</li>
 *   <li>{@code closeToSubPlusTax}   – 1 if within $1 of subtotal+tax+tip</li>
 *   <li>{@code looksLikeDate}       – 1 if the line has a "n/n" pattern (date fragment)</li>
 *   <li>{@code looksLikeCode}       – 1 if integer and >= 100 (auth code, txn id)</li>
 *   <li>{@code highlightScore}      – 0..1, fraction of yellow pixels in the bounding box</li>
 *   <li>{@code circleScore}         – 0..1, ring-vs-core dark pixel ratio (pen circle heuristic)</li>
 * </ul>
 *
 * <p>The two visual-signal features are weighted strongly positive:
 * a human deliberately marked a number with highlighter or a pen
 * circle, and that's the strongest "this is the total" signal we
 * can get from the source photo.</p>
 *
 * <p>Output: sigmoid(weights · features + bias) ∈ [0, 1] — interpretable
 * as P(this price is the real total).</p>
 */
public final class LinearLearner {

    private static final String LOG_TAG = "TotalLearner";
    private static final String BIAS_LABEL = "bias";
    private static final String K_TOTAL = "total";
    private static final String K_AMOUNT = "amount";
    private static final String K_BALANCE = "balance";
    private static final String K_DUE = "due";
    private static final String K_SUBTOTAL = "subtotal";
    private static final String K_TAX = "tax";
    private static final String K_TIP = "tip";
    private static final String EMPTY_KEYWORD = "";
    private static final String EMPTY_LINE = "";
    private static final double VALUE_TOLERANCE = 0.005;
    private static final double SUBTOTAL_FLOOR = 0.01;
    private static final double CLOSE_TO_SUM_TOLERANCE = 1.00;
    private static final double LARGE_CODE_MIN = 100.0;
    private static final double LARGE_CODE_MAX = 1_000_000.0;
    private static final int EPOCHS = 800;
    private static final double LEARNING_RATE = 0.5;
    private static final double L2_LAMBDA = 0.01;


    public static final String[] FEATURE_NAMES = {
            "hasTotalKeyword",
            "hasComponentKeyword",
            "isLargest",
            "lineInBottomHalf",
            "hasDecimal",
            "belowSubtotal",
            "closeToSubPlusTax",
            "looksLikeDate",
            "looksLikeCode",
            "highlightScore",
            "circleScore"
    };


    public static final int FEATURE_COUNT = FEATURE_NAMES.length;


    public static int featureCount() {
        return FEATURE_COUNT;
    }


    // ---------- feature extraction ----------

    public static double[] extractFeatures(@NonNull DetectedNumber candidate,
                                           @NonNull List<DetectedNumber> allDetected,
                                           Double subtotal,
                                           Double tax,
                                           Double tip,
                                           int totalLines) {
        final double[] features = new double[FEATURE_COUNT];

        final String keyword = extractKeyword(candidate);
        final boolean hasTotalKeyword = isTotalKeyword(keyword);
        final boolean hasComponentKeyword = isComponentKeyword(keyword);

        setBinaryFeature(features, 0, hasTotalKeyword);
        setBinaryFeature(features, 1, hasComponentKeyword);

        final double largestOtherValue = findLargestOtherValue(candidate, allDetected);
        setBinaryFeature(features, 2, largestOtherValue <= 0.0 || candidate.value >= largestOtherValue);

        setBinaryFeature(features, 3, totalLines > 0 && candidate.lineIndex >= (totalLines / 2.0));
        setBinaryFeature(features, 4, candidate.value != Math.floor(candidate.value));
        setBinaryFeature(features, 5, subtotal != null && candidate.value < subtotal - SUBTOTAL_FLOOR);

        setBinaryFeature(features, 6, isCloseToSubPlusTax(candidate.value, subtotal, tax, tip));

        final String line = extractLine(candidate);
        setBinaryFeature(features, 7, line.matches(".*\\d+[/\\-]\\d+.*"));

        final boolean isInteger = candidate.value == Math.floor(candidate.value);
        setBinaryFeature(features, 8, isInteger
                && candidate.value >= LARGE_CODE_MIN
                && candidate.value < LARGE_CODE_MAX);

        // Visual signals: pass the raw 0..1 scores straight through.
        // The strong positive weight in the trained model will lift
        // emphasised numbers above non-emphasised ones even when the
        // text-based features are noisy.
        features[9] = clamp01(candidate.highlightScore);
        features[10] = clamp01(candidate.circleScore);

        return features;
    }


    private static String extractKeyword(DetectedNumber number) {
        if (number.keyword == null) {
            return EMPTY_KEYWORD;
        }
        return number.keyword.toLowerCase();
    }


    private static String extractLine(DetectedNumber number) {
        if (number.line == null) {
            return EMPTY_LINE;
        }
        return number.line;
    }


    private static boolean isTotalKeyword(String keyword) {
        return keyword.equals(K_TOTAL)
                || keyword.equals(K_AMOUNT)
                || keyword.equals(K_BALANCE)
                || keyword.equals(K_DUE);
    }


    private static boolean isComponentKeyword(String keyword) {
        return keyword.equals(K_SUBTOTAL) || keyword.equals(K_TAX) || keyword.equals(K_TIP);
    }


    private static double findLargestOtherValue(DetectedNumber candidate, List<DetectedNumber> all) {
        double largestOther = 0.0;
        for (final DetectedNumber other : all) {
            if (other == candidate) continue;
            if (Math.abs(other.value - candidate.value) < VALUE_TOLERANCE) continue;
            if (other.value > largestOther) {
                largestOther = other.value;
            }
        }
        return largestOther;
    }


    private static boolean isCloseToSubPlusTax(double value, Double subtotal, Double tax, Double tip) {
        if (subtotal == null) return false;
        final double taxValue = (tax == null) ? 0.0 : tax;
        final double tipValue = (tip == null) ? 0.0 : tip;
        final double predictedTotal = subtotal + taxValue + tipValue;
        return predictedTotal > 0.0
                && Math.abs(value - predictedTotal) <= CLOSE_TO_SUM_TOLERANCE;
    }


    private static void setBinaryFeature(double[] features, int index, boolean condition) {
        if (condition) {
            features[index] = 1.0;
        } else {
            features[index] = 0.0;
        }
    }


    private static double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }


    // ---------- trained model ----------

    private static volatile LogisticRegression.Trained trainedModel;
    private static volatile boolean trained = false;
    private static final LogisticRegression.HyperParams HYPER_PARAMS =
            new LogisticRegression.HyperParams(EPOCHS, LEARNING_RATE, L2_LAMBDA);


    private static synchronized void trainIfNeeded() {
        if (trained) return;

        Logger.i(LOG_TAG, "Training on " + TRAINING_DATA.size() + " examples, "
                + FEATURE_COUNT + " features, "
                + formatHyperParams(HYPER_PARAMS));

        trainedModel = LogisticRegression.train(LOG_TAG, FEATURE_COUNT, TRAINING_DATA, HYPER_PARAMS);
        trained = true;
        Logger.i(LOG_TAG, "Training complete");
        Logger.i(LOG_TAG, buildWeightsLog(trainedModel));
    }


    private static String buildWeightsLog(LogisticRegression.Trained model) {
        final StringBuilder logBuilder = new StringBuilder("Learned weights:\n");
        for (int i = 0; i < FEATURE_COUNT; i++) {
            logBuilder.append(String.format(Locale.US, "  %-22s = %+.3f%n",
                    FEATURE_NAMES[i], model.weights[i]));
        }
        logBuilder.append(String.format(Locale.US, "  %-22s = %+.3f", BIAS_LABEL, model.bias));
        return logBuilder.toString();
    }


    private static String formatHyperParams(LogisticRegression.HyperParams params) {
        return String.format(Locale.US, "epochs=%d lr=%.2f l2=%.3f",
                params.epochs, params.learningRate, params.l2Lambda);
    }


    public static double predictProbability(double[] features) {
        trainIfNeeded();
        return LogisticRegression.predictProbability(trainedModel, features);
    }


    public static double predictLogit(double[] features) {
        trainIfNeeded();
        return LogisticRegression.predictLogit(trainedModel, features);
    }


    public static double[] getWeights() {
        trainIfNeeded();
        return trainedModel.weights.clone();
    }


    public static double getBias() {
        trainIfNeeded();
        return trainedModel.bias;
    }


    public static String explain(double[] features) {
        trainIfNeeded();
        return LogisticRegression.explain(FEATURE_NAMES, trainedModel, features);
    }


    // ---------- training data ----------

    private static final List<LogisticRegression.Example> TRAINING_DATA = buildTrainingData();


    /**
     * 11 features per example (the 11 in {@link #FEATURE_NAMES}). The
     * visual-signal features are present in every example, but most
     * examples set them to 0 to match the historical "no visual
     * signal" baseline. The handful of emphasised positive examples
     * (with highlight=0.8 or circle=0.5) teach the model to give
     * those signals a strong positive weight.
     */
    private static List<LogisticRegression.Example> buildTrainingData() {
        final List<LogisticRegression.Example> examples = new ArrayList<>();

        // === POSITIVE (label=1): looks like a real total ===
        // hasTotal, hasComp, isLargest, inBottom, hasDec, belowSub,
        // close, date, code, hl, cr
        addPositive(examples, 1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        addPositive(examples, 1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        addPositive(examples, 1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        addPositive(examples, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0);
        addPositive(examples, 0, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        addPositive(examples, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0);
        addPositive(examples, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0);
        // Highlighted (yellow highlighter) — the visual signal alone
        // is enough to call it a total.
        addPositive(examples, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0.8, 0.0);
        addPositive(examples, 1, 0, 1, 1, 1, 0, 1, 0, 0, 0.6, 0.0);
        // Circled (pen circle around a number) — same: strong positive.
        addPositive(examples, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0.0, 0.6);
        addPositive(examples, 1, 0, 1, 1, 1, 0, 1, 0, 0, 0.0, 0.5);

        // === NEGATIVE (label=0): NOT a total ===
        addNegative(examples, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0);
        addNegative(examples, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0);
        addNegative(examples, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0);
        addNegative(examples, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0);
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0);
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        // A non-emphasised subtotal is still a subtotal, not a total.
        addNegative(examples, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0);

        return examples;
    }


    private static void addPositive(List<LogisticRegression.Example> examples, double... featureValues) {
        examples.add(new LogisticRegression.Example(featureValues, 1.0));
    }


    private static void addNegative(List<LogisticRegression.Example> examples, double... featureValues) {
        examples.add(new LogisticRegression.Example(featureValues, 0.0));
    }
}
