package com.example.receipttracker.match;

import androidx.annotation.NonNull;

import com.example.receipttracker.log.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tiny self-contained logistic-regression trainer. Used by both
 * {@link PriceClassifier} (stage 1: is this number a price?) and
 * {@link LinearLearner} (stage 2: is this price the total?).
 *
 * <p>Online gradient descent on a small in-memory dataset, with L2
 * regularisation and a stable sigmoid. Deterministic: a seeded
 * cycle-shift on the training list per epoch gives reproducible
 * weight convergence for the same data and hyperparameters.</p>
 */
public final class LogisticRegression {

    private LogisticRegression() {}

    public static final class Example {
        public final double[] features;
        public final double   label;
        public Example(@NonNull double[] features, double label) {
            this.features = features;
            this.label = label;
        }
    }

    public static final class Trained {
        public final double[] weights;
        public final double   bias;
        public Trained(double[] weights, double bias) {
            this.weights = weights;
            this.bias = bias;
        }
    }

    public static final class HyperParams {
        public final int    epochs;
        public final double learningRate;
        public final double l2Lambda;
        public HyperParams(int epochs, double learningRate, double l2Lambda) {
            this.epochs = epochs;
            this.learningRate = learningRate;
            this.l2Lambda = l2Lambda;
        }
        public static HyperParams defaults() { return new HyperParams(800, 0.5, 0.01); }
    }

    /**
     * Trains a binary classifier. The label is expected to be 0.0 or 1.0.
     * The returned {@link Trained} carries the learned weights (same length
     * as the feature vectors) and bias.
     */
    public static Trained train(@NonNull String tag,
                                 int featureCount,
                                 @NonNull List<Example> data,
                                 @NonNull HyperParams hp) {
        if (data.isEmpty()) throw new IllegalArgumentException("Empty training data");
        double[] w = new double[featureCount];
        double b = 0.0;
        for (int epoch = 0; epoch < hp.epochs; epoch++) {
            double totalLoss = 0.0;
            Collections.rotate(data, epoch % data.size());
            for (Example ex : data) {
                double z = b;
                for (int i = 0; i < featureCount; i++) z += w[i] * ex.features[i];
                double p = sigmoid(z);
                double err = ex.label - p;
                for (int i = 0; i < featureCount; i++) {
                    w[i] += hp.learningRate * (err * ex.features[i] - hp.l2Lambda * w[i]);
                }
                b += hp.learningRate * err;
                double safeP = Math.max(1e-9, Math.min(1.0 - 1e-9, p));
                totalLoss += -(ex.label * Math.log(safeP) + (1.0 - ex.label) * Math.log(1.0 - safeP));
            }
            if (epoch % 100 == 0 || epoch == hp.epochs - 1) {
                Logger.d(tag, String.format(Locale.US,
                        "epoch=%d  loss=%.4f", epoch, totalLoss / data.size()));
            }
        }
        return new Trained(w, b);
    }

    /** Logit (raw score) — sum of weight*feature + bias. */
    public static double predictLogit(@NonNull Trained model, @NonNull double[] features) {
        double z = model.bias;
        int n = Math.min(model.weights.length, features.length);
        for (int i = 0; i < n; i++) z += model.weights[i] * features[i];
        return z;
    }

    /** Probability in [0, 1] — sigmoid of the logit. */
    public static double predictProbability(@NonNull Trained model, @NonNull double[] features) {
        return sigmoid(predictLogit(model, features));
    }

    /**
     * Pretty-prints the per-feature contribution of a single prediction,
     * so the UI/log can show "feature X added +1.42, feature Y subtracted
     * -0.87, total logit = +0.55, P = 0.63".
     */
    public static String explain(@NonNull String[] featureNames,
                                 @NonNull Trained model,
                                 @NonNull double[] features) {
        StringBuilder sb = new StringBuilder();
        double total = model.bias;
        sb.append(String.format(Locale.US, "  %-22s contrib=%+.3f  (bias)%n", "(bias)", model.bias));
        for (int i = 0; i < model.weights.length; i++) {
            double c = model.weights[i] * features[i];
            total += c;
            sb.append(String.format(Locale.US, "  %-22s = %d  w=%+.2f  contrib=%+.3f%n",
                    featureNames[i], (int) features[i], model.weights[i], c));
        }
        sb.append(String.format(Locale.US, "  %-22s = %.3f  →  P=%.3f",
                "logit / sigmoid", total, sigmoid(total)));
        return sb.toString();
    }

    /** Numerically stable sigmoid. */
    public static double sigmoid(double z) {
        if (z >= 0) {
            double e = Math.exp(-z);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(z);
        return e / (1.0 + e);
    }
}
