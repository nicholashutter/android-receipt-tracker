package com.example.receipttracker.match;


import com.example.receipttracker.ocr.DetectedNumber;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class LinearLearnerTest {

    private static List<DetectedNumber> asList(DetectedNumber... numbers) {
        return Arrays.asList(numbers);
    }


    @Test
    @DisplayName("extractFeatures returns 11 features")
    void shouldReturnElevenFeatures() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 0, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), null, null, null, 1);

        assertThat(features).hasSize(LinearLearner.FEATURE_COUNT);
        assertThat(LinearLearner.FEATURE_COUNT).isEqualTo(11);
    }


    @Test
    @DisplayName("extractFeatures: hasTotalKeyword=1 for keyword='total'")
    void shouldSetHasTotalKeyword() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 0, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), null, null, null, 1);

        assertThat(features[0]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("extractFeatures: hasComponentKeyword=1 for keyword='subtotal'")
    void shouldSetHasComponentKeyword() {
        final DetectedNumber number = new DetectedNumber(45.00, "Subtotal  45.00", 0, "subtotal");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), null, null, null, 1);

        assertThat(features[1]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("extractFeatures: isLargest=1 when this number is the largest in the list")
    void shouldSetIsLargest() {
        final DetectedNumber small = new DetectedNumber(5.99, "small", 0, null);
        final DetectedNumber large = new DetectedNumber(99.00, "big", 0, null);

        final double[] features = LinearLearner.extractFeatures(
                large, asList(small, large), null, null, null, 1);

        assertThat(features[2]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("extractFeatures: isLargest=0 when another number is larger")
    void shouldNotSetIsLargestWhenSmaller() {
        final DetectedNumber small = new DetectedNumber(5.99, "small", 0, null);
        final DetectedNumber large = new DetectedNumber(99.00, "big", 0, null);

        final double[] features = LinearLearner.extractFeatures(
                small, asList(small, large), null, null, null, 1);

        assertThat(features[2]).isEqualTo(0.0);
    }


    @Test
    @DisplayName("extractFeatures: lineInBottomHalf=1 when lineIndex >= totalLines/2")
    void shouldSetLineInBottomHalf() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL", 8, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), null, null, null, 10);

        assertThat(features[3]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("extractFeatures: hasDecimal=1 for non-integer values")
    void shouldSetHasDecimal() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 0, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), null, null, null, 1);

        assertThat(features[4]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("extractFeatures: highlightScore is clamped to [0, 1]")
    void shouldClampHighlightScore() {
        final DetectedNumber number = new DetectedNumber(1.0, "x", 0, null, 1.5f, 0f, null);

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), null, null, null, 1);

        assertThat(features[9]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("extractFeatures: closeToSubPlusTax=1 when within $1 of subtotal+tax+tip")
    void shouldSetCloseToSubPlusTax() {
        final DetectedNumber number = new DetectedNumber(48.00, "TOTAL", 0, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), 45.0, 2.5, 0.5, 1);

        assertThat(features[6]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("extractFeatures: closeToSubPlusTax=0 when far from subtotal+tax+tip")
    void shouldNotSetCloseToSubPlusTax() {
        final DetectedNumber number = new DetectedNumber(1.00, "small", 0, null);

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), 45.0, 2.5, 0.5, 1);

        assertThat(features[6]).isEqualTo(0.0);
    }


    @Test
    @DisplayName("extractFeatures: empty allDetected list leaves isLargest=1")
    void shouldSetIsLargestForSoleCandidate() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 0, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, Collections.emptyList(), null, null, null, 1);

        assertThat(features[2]).isEqualTo(1.0);
    }


    @Test
    @DisplayName("predictProbability returns a value in [0, 1] for a typical total")
    void shouldReturnBoundedProbability() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 8, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), 45.0, 2.5, 0.5, 10);

        final double probability = LinearLearner.predictProbability(features);

        assertThat(probability).isBetween(0.0, 1.0);
    }


    @Test
    @DisplayName("getWeights returns 11 trained weights")
    void shouldReturnTrainedWeights() {
        final double[] weights = LinearLearner.getWeights();

        assertThat(weights).hasSize(11);
    }


    @Test
    @DisplayName("explain returns a non-empty per-feature breakdown")
    void shouldProduceNonEmptyExplanation() {
        final DetectedNumber number = new DetectedNumber(47.83, "TOTAL  47.83", 8, "total");

        final double[] features = LinearLearner.extractFeatures(
                number, asList(number), 45.0, 2.5, 0.5, 10);

        final String explanation = LinearLearner.explain(features);

        assertThat(explanation).isNotBlank();
        assertThat(explanation).contains("hasTotalKeyword");
    }
}
