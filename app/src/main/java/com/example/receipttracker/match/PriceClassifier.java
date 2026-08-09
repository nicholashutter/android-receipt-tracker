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
 * </ul>
 *
 * <p>Output: sigmoid(weights · features + bias) ∈ [0, 1]. Numbers with
 * P(is price) &lt; {@link #PRICE_THRESHOLD} are dropped before stage 2.</p>
 */
public final class PriceClassifier {

    private PriceClassifier() {}


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


    /**
     * OCR line keywords that almost always mean "this number isn't a price"
     * even when it has a decimal point. Strong negative signal.
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


    public static int featureCount() { return FEATURE_COUNT; }


    // ---------- feature extraction ----------

    public static double[] extractFeatures(@NonNull DetectedNumber n) {
        double[] f = new double[FEATURE_COUNT];

        double v = n.value;


        // hasDecimal: 5.99 yes, 6 no.
        if (v != Math.floor(v)) {
            f[0] = 1.0;
        } else {
            f[0] = 0.0;
        }


        // valueInRange: 1.00 < v < 1000.0 — most single-item prices live here.
        // Below 1 → typically a quantity or fraction; above 1000 → usually a code or year.
        if (v > 1.0 && v < 1000.0) {
            f[1] = 1.0;
        } else {
            f[1] = 0.0;
        }


        String line;

        if (n.line == null) {
            line = "";
        } else {
            line = n.line;
        }

        String lower = line.toLowerCase();


        // hasLetters: 3+ alphabetic characters on the line. Real line items say "Bananas 1.99".
        int letterCount = 0;

        for (int i = 0; i < line.length(); i++) {
            if (Character.isLetter(line.charAt(i))) letterCount++;

            if (letterCount >= 3) break;
        }

        if (letterCount >= 3) {
            f[2] = 1.0;
        } else {
            f[2] = 0.0;
        }


        // hasCurrency: "$" appears on the line.
        if (line.indexOf('$') >= 0) {
            f[3] = 1.0;
        } else {
            f[3] = 0.0;
        }


        // hasPriceKeyword: line contains a price-component keyword.
        if (Pattern.compile("(?i).*\\b(" + PRICE_KEYWORDS + ")\\b.*").matcher(line).matches()) {
            f[4] = 1.0;
        } else {
            f[4] = 0.0;
        }


        // looksLikeDate: "n/n" or "n-n" pattern, especially with a 4-digit year.
        if (line.matches(".*\\b\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}\\b.*")) {
            f[5] = 1.0;
        } else {
            f[5] = 0.0;
        }


        // looksLikePhone: parentheses or dashed 10-digit shape.
        if (PHONE_PATTERN.matcher(line).matches()) {
            f[6] = 1.0;
        } else {
            f[6] = 0.0;
        }


        // looksLikeAuthCode: integer, value in [100, 1e7), no decimal, no price keyword.
        boolean integer = (v == Math.floor(v));

        if (integer && v >= 100 && v < 10_000_000 && f[4] == 0.0) {
            f[7] = 1.0;
        } else {
            f[7] = 0.0;
        }


        // looksLikeQuantity: integer 1-9.
        if (integer && v >= 1 && v <= 9) {
            f[8] = 1.0;
        } else {
            f[8] = 0.0;
        }


        // hasNoiseKeyword: the line contains a "this isn't a price" keyword
        // (version, exp, auth, ref, txn, mid, aid, tsi, tvr, suggested, ...).
        if (Pattern.compile("(?i).*\\b(" + NOISE_KEYWORDS + ")\\b.*").matcher(line).matches()) {
            f[9] = 1.0;
        } else {
            f[9] = 0.0;
        }


        return f;
    }


    // ---------- trained model ----------

    private static final String LOG_TAG = "PriceClf";


    private static volatile LogisticRegression.Trained model;

    private static volatile boolean trained = false;


    private static synchronized void trainIfNeeded() {
        if (trained) return;

        model = LogisticRegression.train(LOG_TAG, FEATURE_COUNT, TRAINING_DATA,
                new LogisticRegression.HyperParams(800, 0.5, 0.01));

        trained = true;

        Logger.i(LOG_TAG, "Training complete: " + TRAINING_DATA.size()
                + " examples, " + FEATURE_COUNT + " features");

        StringBuilder wlog = new StringBuilder("Learned weights:\n");

        for (int i = 0; i < FEATURE_COUNT; i++) {
            wlog.append(String.format(Locale.US, "  %-22s = %+.3f%n", FEATURE_NAMES[i], model.weights[i]));
        }

        wlog.append(String.format(Locale.US, "  %-22s = %+.3f", "bias", model.bias));

        Logger.i(LOG_TAG, wlog.toString());
    }


    /** Returns P(this number is a price) in [0, 1]. */
    public static double predictProbability(double[] features) {
        trainIfNeeded();

        return LogisticRegression.predictProbability(model, features);
    }


    /** Returns the raw logit, useful for the reasoning string. */
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


    /** Convenience: returns true if P(is price) >= PRICE_THRESHOLD. */
    public static boolean isPrice(DetectedNumber n) {
        return predictProbability(extractFeatures(n)) >= PRICE_THRESHOLD;
    }


    // ---------- training data ----------
    //
    // Each row is (feature_vector, label) where label=1.0 means "this is a
    // real price on a receipt" and 0.0 means "this is noise (date, phone,
    // auth code, quantity, etc.)". 18 examples (9 positive, 9 negative).

    private static final List<LogisticRegression.Example> TRAINING_DATA = buildTrainingData();


    private static List<LogisticRegression.Example> buildTrainingData() {
        List<LogisticRegression.Example> ex = new ArrayList<>();

        // === POSITIVE (label=1): real prices ===
        // Bare price: "$5.99"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 0,1, 0, 0, 0, 0, 0, 0}, 1.0));

        // Line item: "Bananas  1.99"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 0, 0, 0, 0, 0, 0}, 1.0));

        // Subtotal: "Subtotal  32.45"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 1, 0, 0, 0, 0, 0}, 1.0));

        // Tax: "Sales Tax 6.5%  2.11"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 1, 0, 0, 0, 0, 0}, 1.0));

        // Total: "TOTAL  $34.56"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,1, 1, 0, 0, 0, 0, 0}, 1.0));

        // Tip: "Tip  5.00"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 1, 0, 0, 0, 0, 0}, 1.0));

        // Discount: "-1.50"  (small negative-adjacent price)
        ex.add(new LogisticRegression.Example(new double[]{1,1, 0,0, 0, 0, 0, 0, 0, 0}, 1.0));

        // Line item with currency: "Bread  $4.50"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,1, 0, 0, 0, 0, 0, 0}, 1.0));

        // Big purchase: $850
        ex.add(new LogisticRegression.Example(new double[]{0,0, 1,0, 0, 0, 0, 0, 0, 0}, 1.0));

        // "Tax (included) 3.50"
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 1, 0, 0, 0, 0, 0}, 1.0));


        // === NEGATIVE (label=0): NOT prices ===
        // Date: "08/06/2026"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 1, 0, 1, 0, 0}, 0.0));

        // Short date: "5/17/20"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 1, 0, 1, 0, 0}, 0.0));

        // Phone: "(555) 123-4567"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 1, 1, 0, 0}, 0.0));

        // Phone: "555-123-4567"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 1, 1, 0, 0}, 0.0));

        // Auth code: "7A2F9B"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 1,0, 0, 0, 0, 1, 0, 0}, 0.0));

        // Transaction ID: "133337"
        ex.add(new LogisticRegression.Example(new double[]{0,1, 0,0, 0, 0, 0, 1, 0, 0}, 0.0));

        // Approval #: "Approval # 7A2F9B"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 1,0, 0, 0, 0, 1, 0, 0}, 0.0));

        // Quantity: "1" or "2"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 0, 1, 0}, 0.0));

        // Year: "2026"
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 1, 0, 0}, 0.0));


        // === NEW: decimal-pointed noise (the case this classifier is meant to fix) ===
        // "Version 1.5.20" → the "5.20" gets extracted as 5.20 but isn't a price
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 0, 0, 0, 0, 0, 1}, 0.0));

        // "Card Exp 12.27" → 12.27 is an expiration date, not a price
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 0, 0, 0, 0, 0, 1}, 0.0));

        // "Tip Suggested 9.57" → suggested tip is informational, not a charge
        ex.add(new LogisticRegression.Example(new double[]{1,1, 1,0, 0, 0, 0, 0, 0, 1}, 0.0));

        // "Balance Due 0.00" → placeholder, not a real price
        ex.add(new LogisticRegression.Example(new double[]{0,0, 1,0, 1, 0, 0, 0, 0, 1}, 0.0));

        // "Ref #: 12345" → reference number
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 1, 0, 1}, 0.0));

        // "AUTH: 060112" → authorization code
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 1, 0, 1}, 0.0));

        // "AID: A0000000031010" → application ID, integer
        ex.add(new LogisticRegression.Example(new double[]{0,0, 1,0, 0, 0, 0, 1, 0, 1}, 0.0));

        // "TSI: 6800" → transaction status info
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 1, 0, 1}, 0.0));

        // "TVR: 8080008000" → terminal verification results
        ex.add(new LogisticRegression.Example(new double[]{0,0, 0,0, 0, 0, 0, 1, 0, 1}, 0.0));

        return ex;
    }
}
