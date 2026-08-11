package com.example.receipttracker.ocr;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ParsedReceiptTest {

    @Test
    @DisplayName("EMPTY constant has all fields null")
    void shouldHaveAllFieldsNullInEmpty() {
        assertThat(ParsedReceipt.EMPTY.merchant).isNull();
        assertThat(ParsedReceipt.EMPTY.dateMillis).isNull();
        assertThat(ParsedReceipt.EMPTY.amount).isNull();
        assertThat(ParsedReceipt.EMPTY.rawText).isNull();
        assertThat(ParsedReceipt.EMPTY.merchantPrediction).isNull();
    }


    @Test
    @DisplayName("withMerchant sets the merchant")
    void shouldSetMerchant() {
        final ParsedReceipt updated = ParsedReceipt.EMPTY.withMerchant("Whole Foods");

        assertThat(updated.merchant).isEqualTo("Whole Foods");
        assertThat(ParsedReceipt.EMPTY.merchant).isNull();
    }


    @Test
    @DisplayName("withMerchant with the same value returns the same instance (no-op)")
    void shouldReturnSameInstanceWhenMerchantUnchanged() {
        final ParsedReceipt original = ParsedReceipt.EMPTY.withMerchant("Costco");

        final ParsedReceipt updated = original.withMerchant("Costco");

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withDateMillis sets the date")
    void shouldSetDateMillis() {
        final long date = 1_704_067_200_000L;

        final ParsedReceipt updated = ParsedReceipt.EMPTY.withDateMillis(date);

        assertThat(updated.dateMillis).isEqualTo(date);
    }


    @Test
    @DisplayName("withAmount sets the amount")
    void shouldSetAmount() {
        final ParsedReceipt updated = ParsedReceipt.EMPTY.withAmount(47.83);

        assertThat(updated.amount).isEqualTo(47.83);
    }


    @Test
    @DisplayName("withRawText sets the raw text")
    void shouldSetRawText() {
        final ParsedReceipt updated = ParsedReceipt.EMPTY.withRawText("OCR text");

        assertThat(updated.rawText).isEqualTo("OCR text");
    }


    @Test
    @DisplayName("withMerchantPrediction sets the prediction")
    void shouldSetMerchantPrediction() {
        final MerchantClassifier.Prediction prediction = new MerchantClassifier.Prediction(
                "Whole Foods Market", "Grocery", 0.85, "WHOLE FOODS");

        final ParsedReceipt updated = ParsedReceipt.EMPTY.withMerchantPrediction(prediction);

        assertThat(updated.merchantPrediction).isSameAs(prediction);
    }


    @Test
    @DisplayName("chained with* calls build up a fully populated ParsedReceipt")
    void shouldBuildUpViaChainedWithCalls() {
        final MerchantClassifier.Prediction prediction = new MerchantClassifier.Prediction(
                "Whole Foods Market", "Grocery", 0.85, "WHOLE FOODS");

        final long date = 1_704_067_200_000L;

        final ParsedReceipt full = ParsedReceipt.EMPTY
                .withMerchant("Whole Foods Market")
                .withMerchantPrediction(prediction)
                .withDateMillis(date)
                .withAmount(47.83)
                .withRawText("WHOLE FOODS\nTOTAL  47.83");

        assertThat(full.merchant).isEqualTo("Whole Foods Market");
        assertThat(full.merchantPrediction).isSameAs(prediction);
        assertThat(full.dateMillis).isEqualTo(date);
        assertThat(full.amount).isEqualTo(47.83);
        assertThat(full.rawText).contains("TOTAL");
    }


    @Test
    @DisplayName("toString is human-readable")
    void shouldRenderReadableToString() {
        final ParsedReceipt receipt = ParsedReceipt.EMPTY
                .withMerchant("Costco")
                .withAmount(100.0);

        final String text = receipt.toString();

        assertThat(text).contains("Costco");
        assertThat(text).contains("100.0");
    }
}
