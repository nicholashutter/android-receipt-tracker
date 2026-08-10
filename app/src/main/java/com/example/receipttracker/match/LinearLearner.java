package com.example.receipttracker.match;


import androidx.annotation.NonNull;


import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ocr.DetectedNumber;


import java.util.ArrayList;

import java.util.List;

import java.util.Locale;


/**
 * Stage 2 of the two-stage receipt-classifier: given a number that's
 * already known to be a price (see {@link PriceClassifier}), what's the
 * probability it's the receipt's total?
 *
 * <p>Implemented as a small logistic-regression classifier trained on
 * synthetic receipts. Feature vector:</p>
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
 *   <li>{@code isHandwritten}       – 1 if the value came from Tesseract re-OCR (handwriting)
 *                                    on a visually-emphasised bbox, 0 otherwise</li>
 * </ul>
 *
 * <p>The two visual-signal features are weighted strongly positive:
 * a human deliberately marked a number with highlighter or a pen
 * circle, and that's the strongest "this is the total" signal we
 * can get from the source photo. The {@code isHandwritten} feature
 * is the "and the user wrote it by hand" corollary: a hand-written
 * digit the user pointed at with a highlighter is the highest-trust
 * total the pipeline can produce.</p>
 *
 * <p>Output: sigmoid(weights · features + bias) ∈ [0, 1] — interpretable
 * as P(this price is the real total).</p>
 */
public final class LinearLearner {

    private LinearLearner() {}


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
            "circleScore",
            "isHandwritten"
    };


    public static final int FEATURE_COUNT = FEATURE_NAMES.length;


    public static int featureCount() { return FEATURE_COUNT; }


    // ---------- feature extraction ----------

    public static double[] extractFeatures(@NonNull DetectedNumber n,
                                           @NonNull List<DetectedNumber> all,
                                           Double subtotal,
                                           Double tax,
                                           Double tip,
                                           int totalLines) {
        double[] f = new double[FEATURE_COUNT];

        String kw;

        if (n.keyword == null) {
            kw = "";
        } else {
            kw = n.keyword.toLowerCase();
        }

        boolean hasTotal = kw.equals("total") || kw.equals("amount")
                || kw.equals("balance") || kw.equals("due");

        boolean hasComponent = kw.equals("subtotal") || kw.equals("tax") || kw.equals("tip");

        if (hasTotal) {
            f[0] = 1.0;
        } else {
            f[0] = 0.0;
        }

        if (hasComponent) {
            f[1] = 1.0;
        } else {
            f[1] = 0.0;
        }


        double maxOther = 0;

        for (DetectedNumber m : all) {
            if (m == n) continue;

            if (Math.abs(m.value - n.value) < 0.005) continue;

            if (m.value > maxOther) maxOther = m.value;
        }

        if (maxOther <= 0 || n.value >= maxOther) {
            f[2] = 1.0;
        } else {
            f[2] = 0.0;
        }

        if (totalLines > 0 && n.lineIndex >= (totalLines / 2.0)) {
            f[3] = 1.0;
        } else {
            f[3] = 0.0;
        }

        if (n.value != Math.floor(n.value)) {
            f[4] = 1.0;
        } else {
            f[4] = 0.0;
        }

        if (subtotal != null && n.value < subtotal - 0.01) {
            f[5] = 1.0;
        } else {
            f[5] = 0.0;
        }


        if (subtotal != null) {
            double taxVal;

            if (tax == null) {
                taxVal = 0.0;
            } else {
                taxVal = tax;
            }

            double tipVal;

            if (tip == null) {
                tipVal = 0.0;
            } else {
                tipVal = tip;
            }

            double predicted = subtotal + taxVal + tipVal;

            if (predicted > 0 && Math.abs(n.value - predicted) <= 1.00) {
                f[6] = 1.0;
            } else {
                f[6] = 0.0;
            }
        } else {
            f[6] = 0.0;
        }


        String line;

        if (n.line == null) {
            line = "";
        } else {
            line = n.line;
        }

        if (line.matches(".*\\d+[/\\-]\\d+.*")) {
            f[7] = 1.0;
        } else {
            f[7] = 0.0;
        }

        boolean integer = (n.value == Math.floor(n.value));

        if (integer && n.value >= 100 && n.value < 1_000_000) {
            f[8] = 1.0;
        } else {
            f[8] = 0.0;
        }


        // Visual signals: pass the raw 0..1 scores straight through.
        // The strong positive weight in the trained model will lift
        // emphasised numbers above non-emphasised ones even when the
        // text-based features are noisy.
        f[9]  = clamp01(n.highlightScore);

        f[10] = clamp01(n.circleScore);


        // 12th feature: handwriting. True when the value was re-OCR'd
        // by Tesseract on a visually-emphasised bbox — i.e. the user
        // wrote a number in pen and pointed at it. This is the
        // strongest "this is the total" signal we have.
        if (n.isHandwrittenAndMarked()) {
            f[11] = 1.0;
        } else {
            f[11] = 0.0;
        }


        return f;
    }


    private static double clamp01(double v) {
        if (v < 0) return 0;

        if (v > 1) return 1;

        return v;
    }


    // ---------- trained model ----------

    private static final String LOG_TAG = "TotalLearner";


    private static volatile LogisticRegression.Trained model;

    private static volatile boolean trained = false;


    private static synchronized void trainIfNeeded() {
        if (trained) return;

        Logger.i(LOG_TAG, "Training on " + TRAINING_DATA.size() + " examples, "
                + FEATURE_COUNT + " features, "
                + HyperParamsLog(HyperParamsForTraining));

        model = LogisticRegression.train(LOG_TAG, FEATURE_COUNT, TRAINING_DATA, HyperParamsForTraining);

        trained = true;

        Logger.i(LOG_TAG, "Training complete");

        StringBuilder wlog = new StringBuilder("Learned weights:\n");

        for (int i = 0; i < FEATURE_COUNT; i++) {
            wlog.append(String.format(Locale.US, "  %-22s = %+.3f%n", FEATURE_NAMES[i], model.weights[i]));
        }

        wlog.append(String.format(Locale.US, "  %-22s = %+.3f", "bias", model.bias));

        Logger.i(LOG_TAG, wlog.toString());
    }


    private static final LogisticRegression.HyperParams HyperParamsForTraining =
            new LogisticRegression.HyperParams(800, 0.5, 0.01);


    private static String HyperParamsLog(LogisticRegression.HyperParams hp) {
        return String.format(Locale.US, "epochs=%d lr=%.2f l2=%.3f", hp.epochs, hp.learningRate, hp.l2Lambda);
    }


    public static double predictProbability(double[] features) {
        trainIfNeeded();

        return LogisticRegression.predictProbability(model, features);
    }


    public static double predictLogit(double[] features) {
        trainIfNeeded();

        return LogisticRegression.predictLogit(model, features);
    }


    public static double[] getWeights() {
        trainIfNeeded();

        return model.weights.clone();
    }


    public static double getBias() {
        trainIfNeeded();

        return model.bias;
    }


    public static String explain(double[] features) {
        trainIfNeeded();

        return LogisticRegression.explain(FEATURE_NAMES, model, features);
    }


    // ---------- training data ----------

    private static final List<LogisticRegression.Example> TRAINING_DATA = buildTrainingData();


    /**
     * 11 features per example. The visual-signal features are present
     * in every example, but most examples set them to 0 to match the
     * historical "no visual signal" baseline. The handful of emphasised
     * positive examples (with highlight=0.8 or circle=0.5) teach the
     * model to give those signals a strong positive weight.
     */
    private static List<LogisticRegression.Example> buildTrainingData() {
        List<LogisticRegression.Example> ex = new ArrayList<>();


        // === POSITIVE (label=1): looks like a real total ===
        // hasTotal, hasComp, isLargest, inBottom, hasDec, belowSub, close, date, code, hl, cr, handwriting
        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,1, 1, 0, 1, 0, 0, 0, 0, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,1, 1, 0, 1, 0, 0, 0, 0, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,1, 1, 0, 1, 0, 0, 0, 0, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,0, 1, 0, 1, 0, 0, 0, 0, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{0,0, 1,1, 1, 0, 1, 0, 0, 0, 0, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,1, 1, 0, 0, 0, 0, 0, 0, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,0, 1, 0, 1, 0, 0, 0, 0, 0}, 1.0));

        // Highlighted (yellow highlighter) — the visual signal alone is enough to call it a total.
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 1, 0, 0, 0, 0, 0.8, 0.0, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,1, 1, 0, 1, 0, 0, 0.6, 0.0, 0}, 1.0));

        // Circled (pen circle around a number) — same: strong positive.
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 1, 0, 0, 0, 0, 0.0, 0.6, 0}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,1, 1, 0, 1, 0, 0, 0.0, 0.5, 0}, 1.0));

        // Handwritten + marked: the user wrote a number in pen AND pointed
        // at it. The value came from Tesseract (so ML Kit's print-OCR
        // didn't see it as a number, and the hasTotalKeyword/isLargest
        // features are often 0 because the line text is unrecognised
        // garbage). The model should learn to give these a strong
        // "this is the total" signal anyway, because a human told us so.
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 1, 0, 0, 0, 0, 0.7, 0.0, 1}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,1, 1, 0, 0, 0, 0, 0.0, 0.6, 1}, 1.0));

        ex.add(new LogisticRegression.Example(new double[]{1,0, 1,1, 1, 0, 1, 0, 0, 0.5, 0.4, 1}, 1.0));


        // === NEGATIVE (label=0): NOT a total ===
        ex.add(new LogisticRegression.Example(new double[]{0,1, 0,0, 1, 0, 0, 0, 0, 0, 0, 0}, 0.0));

        ex.add(new LogisticRegression.Example(new double[]{0,1, 0,0, 1, 1, 0, 0, 0, 0, 0, 0}, 0.0));

        ex.add(new LogisticRegression.Example(new double[]{0,1, 0,0, 1, 1, 0, 0, 0, 0, 0, 0}, 0.0));

        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 1, 1, 0, 0, 0, 0, 0, 0}, 0.0));

        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 1, 0, 0, 0, 0}, 0.0));

        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 0, 1, 0, 0, 0}, 0.0));

        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 0, 0, 0, 0, 0}, 0.0));

        // A non-emphasised subtotal is still a subtotal, not a total.
        ex.add(new LogisticRegression.Example(new double[]{0,1, 0,1, 1, 0, 0, 0, 0, 0, 0, 0}, 0.0));

        return ex;
    }
}
