package com.example.receipttracker.match;


import com.example.receipttracker.ocr.DetectedNumber;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class PriceClassifierTest {

    @Test
    @DisplayName("extractFeatures returns the expected number of features")
    void shouldReturnExpectedFeatureCount() {
        final DetectedNumber number = new DetectedNumber(5.99, "Bananas  5.99", 0, null);

        final double[] features = PriceClassifier.extractFeatures(number);

        assertThat(features).hasSize(PriceClassifier.FEATURE_COUNT);
    }


    @Test
    @DisplayName("extractFeatures: '$5.99' on a line with a price keyword sets the positive features")
    void shouldRecogniseRealPrice() {
        final DetectedNumber number = new DetectedNumber(5.99, "TOTAL  $5.99", 3, "total");

        final double[] features = PriceClassifier.extractFeatures(number);

        assertThat(features[0]).isEqualTo(1.0); // hasDecimal
        assertThat(features[1]).isEqualTo(1.0); // valueInRange
        assertThat(features[2]).isEqualTo(1.0); // hasLetters (TOTAL)
        assertThat(features[3]).isEqualTo(1.0); // hasCurrency
        assertThat(features[4]).isEqualTo(1.0); // hasPriceKeyword
    }


    @Test
    @DisplayName("extractFeatures: a date-shaped line has looksLikeDate=1, no decimal/in-range")
    void shouldRecogniseDate() {
        final DetectedNumber number = new DetectedNumber(0.0, "Date: 08/06/2026", 0, null);

        final double[] features = PriceClassifier.extractFeatures(number);

        assertThat(features[0]).isEqualTo(0.0); // hasDecimal (0.0 is integer)
        assertThat(features[1]).isEqualTo(0.0); // valueInRange
        assertThat(features[5]).isEqualTo(1.0); // looksLikeDate
    }


    @Test
    @DisplayName("extractFeatures: a phone-shaped line has looksLikePhone=1")
    void shouldRecognisePhone() {
        final DetectedNumber number = new DetectedNumber(0.0, "(555) 123-4567", 0, null);

        final double[] features = PriceClassifier.extractFeatures(number);

        assertThat(features[6]).isEqualTo(1.0); // looksLikePhone
    }


    @Test
    @DisplayName("extractFeatures: an auth-code-shaped line has looksLikeAuthCode=1")
    void shouldRecogniseAuthCode() {
        final DetectedNumber number = new DetectedNumber(133337.0, "Auth: 133337", 0, null);

        final double[] features = PriceClassifier.extractFeatures(number);

        assertThat(features[7]).isEqualTo(1.0); // looksLikeAuthCode
    }


    @Test
    @DisplayName("extractFeatures: a quantity-shaped line (integer 1-9) has looksLikeQuantity=1")
    void shouldRecogniseQuantity() {
        final DetectedNumber number = new DetectedNumber(3.0, "3 Apples", 0, null);

        final double[] features = PriceClassifier.extractFeatures(number);

        assertThat(features[8]).isEqualTo(1.0); // looksLikeQuantity
    }


    @Test
    @DisplayName("extractFeatures: a line with a noise keyword ('AUTH') has hasNoiseKeyword=1")
    void shouldRecogniseNoiseKeyword() {
        final DetectedNumber number = new DetectedNumber(0.0, "AUTH: 060112", 0, null);

        final double[] features = PriceClassifier.extractFeatures(number);

        assertThat(features[9]).isEqualTo(1.0); // hasNoiseKeyword
    }


    @Test
    @DisplayName("predictProbability returns a value in [0, 1]")
    void shouldBoundProbability() {
        final DetectedNumber number = new DetectedNumber(5.99, "Bananas  5.99", 0, null);

        final double probability = PriceClassifier.predictProbability(PriceClassifier.extractFeatures(number));

        assertThat(probability).isBetween(0.0, 1.0);
    }


    @Test
    @DisplayName("isPrice: real prices on a real line clear the threshold")
    void shouldClassifyRealPrice() {
        final DetectedNumber number = new DetectedNumber(5.99, "Bananas  $5.99", 0, null);

        assertThat(PriceClassifier.isPrice(number)).isTrue();
    }


    @Test
    @DisplayName("isPrice: phone numbers fail the threshold")
    void shouldRejectPhoneNumber() {
        final DetectedNumber number = new DetectedNumber(5551234567.0, "(555) 123-4567", 0, null);

        assertThat(PriceClassifier.isPrice(number)).isFalse();
    }


    @Test
    @DisplayName("getWeights and getBias return a trained model")
    void shouldReturnTrainedModel() {
        final double[] weights = PriceClassifier.getWeights();
        final double bias = PriceClassifier.getBias();

        assertThat(weights).hasSize(PriceClassifier.FEATURE_COUNT);
        assertThat(Double.isNaN(bias)).isFalse();
    }
}
