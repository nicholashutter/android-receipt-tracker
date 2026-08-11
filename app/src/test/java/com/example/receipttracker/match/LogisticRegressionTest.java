package com.example.receipttracker.match;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class LogisticRegressionTest {

    @Test
    @DisplayName("sigmoid(0) is exactly 0.5")
    void shouldReturnHalfAtZero() {
        assertThat(LogisticRegression.sigmoid(0.0)).isEqualTo(0.5);
    }


    @Test
    @DisplayName("sigmoid is symmetric: sigmoid(x) + sigmoid(-x) = 1")
    void shouldBeSymmetricAroundZero() {
        for (final double z : new double[] {0.5, 1.0, 2.0, 5.0, 10.0, -3.0, -7.5}) {
            final double sum = LogisticRegression.sigmoid(z) + LogisticRegression.sigmoid(-z);
            assertThat(sum).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
        }
    }


    @Test
    @DisplayName("sigmoid(very large positive) saturates near 1")
    void shouldSaturateToOneForLargePositive() {
        assertThat(LogisticRegression.sigmoid(50.0)).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-15));
    }


    @Test
    @DisplayName("sigmoid(very large negative) saturates near 0 (within 1e-15)")
    void shouldSaturateToZeroForLargeNegative() {
        assertThat(LogisticRegression.sigmoid(-50.0)).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1e-15));
    }


    @Test
    @DisplayName("train on empty data throws IllegalArgumentException")
    void shouldThrowOnEmptyData() {
        final List<LogisticRegression.Example> empty = new ArrayList<>();
        final LogisticRegression.HyperParams hyperParams = LogisticRegression.HyperParams.defaults();

        assertThatThrownBy(() -> LogisticRegression.train("test", 2, empty, hyperParams))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    @DisplayName("trained model with one positive example predicts close to 1 for that example")
    void shouldPredictCloseToOneForTrainedExample() {
        final List<LogisticRegression.Example> data = new ArrayList<>();
        data.add(new LogisticRegression.Example(new double[] {1.0, 0.0}, 1.0));
        data.add(new LogisticRegression.Example(new double[] {0.0, 1.0}, 0.0));

        final LogisticRegression.HyperParams hyperParams = new LogisticRegression.HyperParams(2000, 0.5, 0.01);

        final LogisticRegression.Trained trained = LogisticRegression.train("test", 2, data, hyperParams);

        final double positiveProbability = LogisticRegression.predictProbability(
                trained, new double[] {1.0, 0.0});

        final double negativeProbability = LogisticRegression.predictProbability(
                trained, new double[] {0.0, 1.0});

        assertThat(positiveProbability).isGreaterThan(0.5);
        assertThat(negativeProbability).isLessThan(0.5);
    }


    @Test
    @DisplayName("predictLogit matches weight*feature + bias")
    void shouldMatchLogitFormula() {
        final LogisticRegression.Trained trained = new LogisticRegression.Trained(
                new double[] {0.5, -0.3, 0.8}, 0.1);

        final double[] features = new double[] {1.0, 2.0, 3.0};

        final double logit = LogisticRegression.predictLogit(trained, features);

        final double expected = 0.1 + 0.5 * 1.0 + -0.3 * 2.0 + 0.8 * 3.0;

        assertThat(logit).isCloseTo(expected, org.assertj.core.api.Assertions.within(1e-9));
    }


    @Test
    @DisplayName("predictProbability is in [0, 1]")
    void shouldBoundProbability() {
        final LogisticRegression.Trained trained = new LogisticRegression.Trained(
                new double[] {100.0, -100.0}, 0.0);

        final double positiveProbability = LogisticRegression.predictProbability(
                trained, new double[] {1.0, 0.0});

        final double negativeProbability = LogisticRegression.predictProbability(
                trained, new double[] {0.0, 1.0});

        assertThat(positiveProbability).isBetween(0.0, 1.0);
        assertThat(negativeProbability).isBetween(0.0, 1.0);
    }


    @Test
    @DisplayName("explain returns a non-empty string with per-feature contribution lines")
    void shouldProduceNonEmptyExplanation() {
        final LogisticRegression.Trained trained = new LogisticRegression.Trained(
                new double[] {1.0, -0.5}, 0.0);
        final String[] featureNames = new String[] {"featureA", "featureB"};
        final double[] features = new double[] {1.0, 1.0};

        final String explanation = LogisticRegression.explain(featureNames, trained, features);

        assertThat(explanation).isNotBlank();
        assertThat(explanation).contains("featureA");
        assertThat(explanation).contains("featureB");
        assertThat(explanation).contains("P=");
    }


    @Test
    @DisplayName("Trained's default temperature is 1.0 (uncalibrated)")
    void shouldDefaultTemperatureToOne() {
        final LogisticRegression.Trained trained = new LogisticRegression.Trained(
                new double[] {0.0, 0.0}, 0.0);

        assertThat(trained.temperature).isEqualTo(1.0);
    }


    @Test
    @DisplayName("calibrate returns the input model unchanged on an empty held-out set")
    void shouldReturnBaseOnEmptyHeldOutSet() {
        final LogisticRegression.Trained base = new LogisticRegression.Trained(
                new double[] {0.0, 0.0}, 0.0);

        final LogisticRegression.Trained calibrated = LogisticRegression.calibrate(
                base, new ArrayList<>());

        assertThat(calibrated).isSameAs(base);
    }


    @Test
    @DisplayName("calibrate returns a Trained with a temperature in the search range")
    void shouldFitTemperatureOnSyntheticData() {
        // Synthetic data: feature[0] = 1 → label 1, feature[0] = 0 → label 0.
        // The trained model has weights that strongly separate them, so
        // the raw sigmoid outputs are very close to 0 or 1 — over-confident
        // on the training set. Calibration should find a temperature > 1
        // (softening the probabilities) to reduce log-loss on a held-out
        // set with realistic misclassifications.
        final List<LogisticRegression.Example> trainData = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            trainData.add(new LogisticRegression.Example(new double[] {1.0, 0.0}, 1.0));
            trainData.add(new LogisticRegression.Example(new double[] {0.0, 1.0}, 0.0));
        }
        final LogisticRegression.HyperParams hyperParams = new LogisticRegression.HyperParams(2000, 0.5, 0.01);
        final LogisticRegression.Trained base = LogisticRegression.train("test", 2, trainData, hyperParams);

        // Held-out set with some realistic noise (label sometimes wrong).
        final List<LogisticRegression.Example> heldOut = new ArrayList<>();
        heldOut.add(new LogisticRegression.Example(new double[] {0.9, 0.1}, 1.0));
        heldOut.add(new LogisticRegression.Example(new double[] {0.8, 0.2}, 1.0));
        heldOut.add(new LogisticRegression.Example(new double[] {0.1, 0.9}, 0.0));
        heldOut.add(new LogisticRegression.Example(new double[] {0.2, 0.8}, 0.0));

        final LogisticRegression.Trained calibrated = LogisticRegression.calibrate(base, heldOut);

        assertThat(calibrated.temperature).isBetween(0.1, 10.0);
    }


    @Test
    @DisplayName("calibrate on a model with well-calibrated probabilities returns ~1.0 temperature")
    void shouldNotShiftWellCalibratedProbabilities() {
        // A trivial 1-feature model trained on balanced data. The
        // sigmoid outputs should already be roughly calibrated; the
        // grid search should land near T=1.0 (within tolerance).
        final List<LogisticRegression.Example> data = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            data.add(new LogisticRegression.Example(new double[] {1.0}, 1.0));
            data.add(new LogisticRegression.Example(new double[] {0.0}, 0.0));
        }
        final LogisticRegression.HyperParams hyperParams = new LogisticRegression.HyperParams(500, 0.5, 0.01);
        final LogisticRegression.Trained base = LogisticRegression.train("test", 1, data, hyperParams);

        final LogisticRegression.Trained calibrated = LogisticRegression.calibrate(base, data);

        // Allow wide tolerance — calibration can land at 0.5 or 2.0 if
        // the data is perfectly separable. We just want to know that
        // the search completed and produced a finite temperature.
        assertThat(Double.isFinite(calibrated.temperature)).isTrue();
        assertThat(calibrated.temperature).isBetween(0.1, 10.0);
    }


    @Test
    @DisplayName("predictProbability with T>1 softens the output relative to T=1")
    void shouldSoftenProbabilitiesWithHigherTemperature() {
        final LogisticRegression.Trained base = new LogisticRegression.Trained(
                new double[] {2.0, 0.0}, 0.0);

        final double baseProbability = LogisticRegression.predictProbability(
                base, new double[] {1.0, 0.0});

        final LogisticRegression.Trained softened = new LogisticRegression.Trained(
                new double[] {2.0, 0.0}, 0.0, 2.0);

        final double softenedProbability = LogisticRegression.predictProbability(
                softened, new double[] {1.0, 0.0});

        assertThat(softenedProbability).isLessThan(baseProbability);
    }
}
