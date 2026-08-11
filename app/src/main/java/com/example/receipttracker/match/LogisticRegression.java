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

    /** Minimum probability for log stability. */
    private static final double PROBABILITY_EPSILON = 1e-9;

    /** Default hyperparameters for the user-trained classifiers. */
    private static final int DEFAULT_EPOCHS = 800;
    private static final double DEFAULT_LEARNING_RATE = 0.5;
    private static final double DEFAULT_L2_LAMBDA = 0.01;

    /** Log every Nth epoch so the training log isn't spammed. */
    private static final int LOG_INTERVAL_EPOCHS = 100;


    private LogisticRegression() {}


    public static final class Example {
        public final double[] features;
        public final double label;

        public Example(@NonNull double[] features, double label) {
            this.features = features;
            this.label = label;
        }
    }


    public static final class Trained {
        public final double[] weights;
        public final double bias;

        /**
         * Temperature-scaling parameter. {@code sigmoid(logit / T)} —
         * T=1 is uncalibrated (raw logistic), T>1 softens, T<1 sharpens.
         * Fitted offline via {@link #calibrate} on a held-out set; the
         * default of 1.0 preserves the original uncalibrated behavior.
         */
        public final double temperature;


        public Trained(double[] weights, double bias) {
            this(weights, bias, 1.0);
        }


        public Trained(double[] weights, double bias, double temperature) {
            this.weights = weights;
            this.bias = bias;
            this.temperature = temperature;
        }
    }


    public static final class HyperParams {
        public final int epochs;
        public final double learningRate;
        public final double l2Lambda;

        public HyperParams(int epochs, double learningRate, double l2Lambda) {
            this.epochs = epochs;
            this.learningRate = learningRate;
            this.l2Lambda = l2Lambda;
        }


        public static HyperParams defaults() {
            return new HyperParams(DEFAULT_EPOCHS, DEFAULT_LEARNING_RATE, DEFAULT_L2_LAMBDA);
        }
    }


    /**
     * Trains a binary classifier. The label is expected to be 0.0 or
     * 1.0. The returned {@link Trained} carries the learned weights
     * (same length as the feature vectors) and bias.
     */
    public static Trained train(@NonNull String logTag,
                                int featureCount,
                                @NonNull List<Example> trainingData,
                                @NonNull HyperParams hyperParams) {
        if (trainingData.isEmpty()) {
            throw new IllegalArgumentException("Empty training data");
        }

        final double[] weights = new double[featureCount];
        final double[] bias = {0.0};

        for (int epoch = 0; epoch < hyperParams.epochs; epoch++) {
            final double epochLoss = trainOneEpoch(weights, bias, trainingData, featureCount, hyperParams);
            if (epoch % LOG_INTERVAL_EPOCHS == 0 || epoch == hyperParams.epochs - 1) {
                final double averageLoss = epochLoss / trainingData.size();
                Logger.d(logTag, String.format(Locale.US,
                        "epoch=%d  loss=%.4f", epoch, averageLoss));
            }
        }
        return new Trained(weights, bias[0]);
    }


    /** One pass over {@code trainingData}, mutating {@code weights} and {@code bias[0]}. */
    private static double trainOneEpoch(double[] weights, double[] bias,
                                        List<Example> trainingData, int featureCount,
                                        HyperParams hyperParams) {
        double totalLoss = 0.0;
        // Cycle-shift per epoch gives reproducible weight convergence
        // for the same data and hyperparameters.
        Collections.rotate(trainingData, hyperParams.epochs % trainingData.size());

        for (final Example example : trainingData) {
            final double logit = computeLogit(weights, bias[0], example.features, featureCount);
            final double predicted = sigmoid(logit);
            final double error = example.label - predicted;
            updateWeights(weights, example.features, error, featureCount, hyperParams);
            bias[0] += hyperParams.learningRate * error;
            totalLoss += crossEntropyLoss(example.label, predicted);
        }
        return totalLoss;
    }


    private static double computeLogit(double[] weights, double bias, double[] features, int featureCount) {
        double z = bias;
        for (int i = 0; i < featureCount; i++) {
            z += weights[i] * features[i];
        }
        return z;
    }


    private static void updateWeights(double[] weights, double[] features, double error,
                                      int featureCount, HyperParams hyperParams) {
        for (int i = 0; i < featureCount; i++) {
            final double gradient = error * features[i] - hyperParams.l2Lambda * weights[i];
            weights[i] += hyperParams.learningRate * gradient;
        }
    }


    private static double crossEntropyLoss(double label, double probability) {
        final double safeProbability = Math.max(PROBABILITY_EPSILON,
                Math.min(1.0 - PROBABILITY_EPSILON, probability));
        return -(label * Math.log(safeProbability) + (1.0 - label) * Math.log(1.0 - safeProbability));
    }


    /** Logit (raw score) — sum of weight*feature + bias. */
    public static double predictLogit(@NonNull Trained trainedModel, @NonNull double[] features) {
        final int featureCount = Math.min(trainedModel.weights.length, features.length);
        double z = trainedModel.bias;
        for (int i = 0; i < featureCount; i++) {
            z += trainedModel.weights[i] * features[i];
        }
        return z;
    }


    /** Probability in [0, 1] — sigmoid of the (temperature-scaled) logit. */
    public static double predictProbability(@NonNull Trained trainedModel, @NonNull double[] features) {
        final double rawLogit = predictLogit(trainedModel, features);
        // Temperature scaling: sigmoid(logit / T). T=1 = uncalibrated.
        final double temperature = trainedModel.temperature;
        final double scaledLogit;
        if (temperature == 1.0) {
            scaledLogit = rawLogit;
        } else {
            scaledLogit = rawLogit / temperature;
        }

        return sigmoid(scaledLogit);
    }


    /**
     * Fits a single temperature-scaling parameter on a held-out set
     * using grid search. Returns a new {@link Trained} with the
     * temperature baked in.
     *
     * <p>Why this exists: sigmoid outputs aren't probabilities. "70%
     * confidence" doesn't mean "right 70% of the time." A single
     * temperature parameter corrects for this without changing the
     * ranking of predictions — only the absolute scale.</p>
     *
     * <p>Pass a held-out set of (features, label) pairs; the routine
     * finds the T in [T_MIN, T_MAX] that minimises average log-loss.
     * Returns the input model unchanged if the held-out set is empty
     * (no calibration possible without data).</p>
     */
    public static Trained calibrate(@NonNull Trained baseModel, @NonNull List<Example> heldOut) {
        if (heldOut.isEmpty()) {
            return baseModel;
        }

        final double tMin = 0.1;
        final double tMax = 10.0;
        final double tStep = 0.05;

        double bestTemperature = 1.0;
        double bestLoss = averageLogLoss(baseModel, heldOut, bestTemperature);

        for (double temperature = tMin; temperature <= tMax; temperature += tStep) {
            final double loss = averageLogLoss(baseModel, heldOut, temperature);
            if (loss < bestLoss) {
                bestLoss = loss;
                bestTemperature = temperature;
            }
        }

        return new Trained(baseModel.weights, baseModel.bias, bestTemperature);
    }


    private static double averageLogLoss(@NonNull Trained model, @NonNull List<Example> examples, double temperature) {
        if (examples.isEmpty()) {
            return 0.0;
        }

        double totalLoss = 0.0;

        for (final Example example : examples) {
            final double rawLogit = predictLogit(model, example.features);
            final double scaledLogit = rawLogit / temperature;
            final double probability = sigmoid(scaledLogit);
            totalLoss += crossEntropyLoss(example.label, probability);
        }

        return totalLoss / examples.size();
    }


    /**
     * Pretty-prints the per-feature contribution of a single prediction,
     * so the UI/log can show "feature X added +1.42, feature Y subtracted
     * -0.87, total logit = +0.55, P = 0.63".
     */
    public static String explain(@NonNull String[] featureNames,
                                 @NonNull Trained trainedModel,
                                 @NonNull double[] features) {
        final StringBuilder explanationBuilder = new StringBuilder();
        final double total = buildExplanationLines(explanationBuilder, featureNames, trainedModel, features);

        final String tailLine = String.format(Locale.US, "  %-22s = %.3f  →  P=%.3f",
                "logit / sigmoid", total, sigmoid(total));
        explanationBuilder.append(tailLine);
        return explanationBuilder.toString();
    }


    private static double buildExplanationLines(
            StringBuilder builder, String[] featureNames,
            Trained trainedModel, double[] features) {
        double runningTotal = trainedModel.bias;
        final String biasLine = String.format(Locale.US, "  %-22s contrib=%+.3f  (bias)%n",
                "(bias)", trainedModel.bias);
        builder.append(biasLine);

        for (int i = 0; i < trainedModel.weights.length; i++) {
            final double contribution = trainedModel.weights[i] * features[i];
            runningTotal += contribution;
            final String featureLine = String.format(Locale.US,
                    "  %-22s = %d  w=%+.2f  contrib=%+.3f%n",
                    featureNames[i], (int) features[i], trainedModel.weights[i], contribution);
            builder.append(featureLine);
        }
        return runningTotal;
    }


    /** Numerically stable sigmoid. */
    public static double sigmoid(double z) {
        if (z >= 0) {
            final double expNegZ = Math.exp(-z);
            return 1.0 / (1.0 + expNegZ);
        }
        final double expZ = Math.exp(z);
        return expZ / (1.0 + expZ);
    }
}
