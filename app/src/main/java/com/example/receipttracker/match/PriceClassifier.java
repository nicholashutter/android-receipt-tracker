package com.example.receipttracker.match;


import androidx.annotation.NonNull;


import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ocr.DetectedNumber;


import java.util.ArrayList;

import java.util.List;

import java.util.Locale;

import java.util.regex.Pattern;


/**
 * Stage 1 of the two-stage receipt-classifier: given a number that
 * the parser found on a receipt, is it actually a price (a money
 * amount the user cares about) — or is it noise: a date, a phone
 * number, a transaction ID, an auth code, a quantity, a year?
 *
 * <p>Implemented as a small logistic-regression classifier trained on
 * a synthetic dataset that covers each "noise" shape we've seen in
 * real receipts. Feature vector (see {@link #FEATURE_NAMES}):</p>
 * <ul>
 *   <li>{@code hasDecimal}        – 1 if the value has a decimal point (e.g. 5.99 not 6)</li>
 *   <li>{@code valueInRange}      – 1 if the value is in a typical single-item price range</li>
 *   <li>{@code hasLetters}        – 1 if the OCR line has 3+ letters (real line items do)</li>
 *   <li>{@code hasCurrency}       – 1 if the line contains a "$"</li>
 *   <li>{@code hasPriceKeyword}   – 1 if the line has a price-component keyword (subtotal/tax/total/...)</li>
 *   <li>{@code looksLikeDate}     – 1 if the line has a "n/n" or "n-n" date pattern</li>
 *   <li>{@code looksLikePhone}    – 1 if the line has a phone-number shape</li>
 *   <li>{@code looksLikeAuthCode} – 1 if no decimal AND value is in the auth-code range</li>
 *   <li>{@code looksLikeQuantity} – 1 if the value is an integer 1-9 (likely a quantity)</li>
 *   <li>{@code hasNoiseKeyword}   – 1 if the line has a "this isn't a price" keyword</li>
 * </ul>
 *
 * <p>Output: sigmoid(weights · features + bias) ∈ [0, 1]. Numbers with
 * P(is price) &lt; {@link #PRICE_THRESHOLD} are dropped before stage 2.</p>
 */
public final class PriceClassifier {

    private static final String LOG_TAG = "PriceClf";
    private static final String BIAS_LABEL = "bias";
    private static final String EMPTY_LINE = "";

    private static final double PRICE_MIN = 1.0;
    private static final double PRICE_MAX = 1000.0;
    private static final int LETTERS_THRESHOLD = 3;
    private static final int AUTH_CODE_MIN = 100;
    private static final int AUTH_CODE_MAX_EXCLUSIVE = 10_000_000;
    private static final int QUANTITY_MIN = 1;
    private static final int QUANTITY_MAX = 9;
    private static final int EPOCHS = 800;
    private static final double LEARNING_RATE = 0.5;
    private static final double L2_LAMBDA = 0.01;


    /**
     * OCR line keywords that almost always mean "this number isn't a
     * price" even when it has a decimal point. Strong negative signal.
     */
    private static final String NOISE_KEYWORDS =
            "version|exp\\b|expir|auth\\b|approval|ref\\b|txn|mid\\b|aid\\b|" +
            "tsi\\b|tvr\\b|arc\\b|atc\\b|host\\b|terminal|cvm\\b|iad\\b|tvr|" +
            "suggested|recommended|rate\\b|fee\\b|service\\s*charge|gratuity\\s*guide|" +
            "balance\\s*due|amount\\s*due";


    /** Numbers below this probability are considered non-prices. */
    public static final double PRICE_THRESHOLD = 0.5;


    private static final Pattern PHONE_PATTERN = Pattern.compile(
            ".*(\\(\\d{3}\\)\\s*\\d{3}[\\s\\-]?\\d{4}|\\d{3}[\\s\\-]\\d{3}[\\s\\-]\\d{4}).*"
    );


    private static final String PRICE_KEYWORDS =
            "subtotal|sub\\.?\\s*total|tax|tip|gratuity|total|amount|balance|due|sum|to\\s*pay|grand\\s*total|net\\s*total|charge|price|cost";


    public static final String[] FEATURE_NAMES = {
            "hasDecimal",
            "valueInRange",
            "hasLetters",
            "hasCurrency",
            "hasPriceKeyword",
            "looksLikeDate",
            "looksLikePhone",
            "looksLikeAuthCode",
            "looksLikeQuantity",
            "hasNoiseKeyword"
    };


    public static final int FEATURE_COUNT = FEATURE_NAMES.length;


    public static int featureCount() {
        return FEATURE_COUNT;
    }


    // ---------- feature extraction ----------

    public static double[] extractFeatures(@NonNull DetectedNumber number) {
        final double[] features = new double[FEATURE_COUNT];
        final double value = number.value;
        final String line = (number.line == null) ? EMPTY_LINE : number.line;
        final boolean isInteger = (value == Math.floor(value));

        // hasDecimal: 5.99 yes, 6 no.
        setBinaryFeature(features, 0, value != Math.floor(value));

        // valueInRange: 1.00 < v < 1000.0 — most single-item prices
        // live here. Below 1 → typically a quantity or fraction;
        // above 1000 → usually a code or year.
        setBinaryFeature(features, 1, value > PRICE_MIN && value < PRICE_MAX);

        setBinaryFeature(features, 2, countLetters(line) >= LETTERS_THRESHOLD);

        // hasCurrency: "$" appears on the line.
        setBinaryFeature(features, 3, line.indexOf('$') >= 0);

        setBinaryFeature(features, 4, lineHasKeyword(line, PRICE_KEYWORDS));
        setBinaryFeature(features, 5, line.matches(".*\\b\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}\\b.*"));

        // looksLikePhone: parentheses or dashed 10-digit shape.
        setBinaryFeature(features, 6, PHONE_PATTERN.matcher(line).matches());

        // looksLikeAuthCode: integer, value in [100, 1e7), no decimal,
        // no price keyword.
        final boolean hasPriceKeyword = features[4] == 1.0;
        setBinaryFeature(features, 7,
                isInteger
                && value >= AUTH_CODE_MIN
                && value < AUTH_CODE_MAX_EXCLUSIVE
                && !hasPriceKeyword);

        // looksLikeQuantity: integer 1-9.
        setBinaryFeature(features, 8,
                isInteger && value >= QUANTITY_MIN && value <= QUANTITY_MAX);

        // hasNoiseKeyword: the line contains a "this isn't a price"
        // keyword (version, exp, auth, ref, txn, mid, aid, tsi, tvr,
        // suggested, ...).
        setBinaryFeature(features, 9, lineHasKeyword(line, NOISE_KEYWORDS));

        return features;
    }


    private static int countLetters(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (Character.isLetter(line.charAt(i))) count++;
            if (count >= LETTERS_THRESHOLD) break;
        }
        return count;
    }


    private static boolean lineHasKeyword(String line, String keywordGroup) {
        return Pattern.compile("(?i).*\\b(" + keywordGroup + ")\\b.*").matcher(line).matches();
    }


    private static void setBinaryFeature(double[] features, int index, boolean condition) {
        if (condition) {
            features[index] = 1.0;
        } else {
            features[index] = 0.0;
        }
    }


    // ---------- trained model ----------

    private static volatile LogisticRegression.Trained trainedModel;
    private static volatile boolean trained = false;
    private static final LogisticRegression.HyperParams HYPER_PARAMS =
            new LogisticRegression.HyperParams(EPOCHS, LEARNING_RATE, L2_LAMBDA);


    private static synchronized void trainIfNeeded() {
        if (trained) return;

        trainedModel = LogisticRegression.train(LOG_TAG, FEATURE_COUNT, TRAINING_DATA, HYPER_PARAMS);
        trained = true;
        Logger.i(LOG_TAG, "Training complete: " + TRAINING_DATA.size()
                + " examples, " + FEATURE_COUNT + " features");
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


    /** Returns P(this number is a price) in [0, 1]. */
    public static double predictProbability(double[] features) {
        trainIfNeeded();
        return LogisticRegression.predictProbability(trainedModel, features);
    }


    /** Returns the raw logit, useful for the reasoning string. */
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


    /** Convenience: returns true if P(is price) >= PRICE_THRESHOLD. */
    public static boolean isPrice(DetectedNumber number) {
        return predictProbability(extractFeatures(number)) >= PRICE_THRESHOLD;
    }


    // ---------- training data ----------
    //
    // Each row is (feature_vector, label) where label=1.0 means "this
    // is a real price on a receipt" and 0.0 means "this is noise
    // (date, phone, auth code, quantity, etc.)".

    private static final List<LogisticRegression.Example> TRAINING_DATA = buildTrainingData();


    private static List<LogisticRegression.Example> buildTrainingData() {
        final List<LogisticRegression.Example> examples = new ArrayList<>();

        // === POSITIVE (label=1): real prices ===
        // Bare price: "$5.99"
        addPositive(examples, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0);
        // Line item: "Bananas  1.99"
        addPositive(examples, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0);
        // Subtotal: "Subtotal  32.45"
        addPositive(examples, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        // Tax: "Sales Tax 6.5%  2.11"
        addPositive(examples, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        // Total: "TOTAL  $34.56"
        addPositive(examples, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0);
        // Tip: "Tip  5.00"
        addPositive(examples, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);
        // Discount: "-1.50"  (small negative-adjacent price)
        addPositive(examples, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        // Line item with currency: "Bread  $4.50"
        addPositive(examples, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0);
        // Big purchase: $850
        addPositive(examples, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0);
        // "Tax (included) 3.50"
        addPositive(examples, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0);

        // === NEGATIVE (label=0): NOT prices ===
        // Date: "08/06/2026"
        addNegative(examples, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0);
        // Short date: "5/17/20"
        addNegative(examples, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0);
        // Phone: "(555) 123-4567"
        addNegative(examples, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0);
        // Phone: "555-123-4567"
        addNegative(examples, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0);
        // Auth code: "7A2F9B"
        addNegative(examples, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0);
        // Transaction ID: "133337"
        addNegative(examples, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0);
        // Approval #: "Approval # 7A2F9B"
        addNegative(examples, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0);
        // Quantity: "1" or "2"
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0);
        // Year: "2026"
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0);

        // === NEW: decimal-pointed noise (the case this classifier
        // is meant to fix) ===
        // "Version 1.5.20" → the "5.20" gets extracted as 5.20
        // but isn't a price
        addNegative(examples, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1);
        // "Card Exp 12.27" → 12.27 is an expiration date, not a price
        addNegative(examples, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1);
        // "Tip Suggested 9.57" → suggested tip is informational, not a charge
        addNegative(examples, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1);
        // "Balance Due 0.00" → placeholder, not a real price
        addNegative(examples, 0, 0, 1, 0, 1, 0, 0, 0, 0, 1);
        // "Ref #: 12345" → reference number
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1);
        // "AUTH: 060112" → authorization code
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1);
        // "AID: A0000000031010" → application ID, integer
        addNegative(examples, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1);
        // "TSI: 6800" → transaction status info
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1);
        // "TVR: 8080008000" → terminal verification results
        addNegative(examples, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1);

        return examples;
    }


    private static void addPositive(List<LogisticRegression.Example> examples, double... featureValues) {
        examples.add(new LogisticRegression.Example(featureValues, 1.0));
    }


    private static void addNegative(List<LogisticRegression.Example> examples, double... featureValues) {
        examples.add(new LogisticRegression.Example(featureValues, 0.0));
    }
}
