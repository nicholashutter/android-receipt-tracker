package com.example.receipttracker.ocr;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class MerchantClassifierTest {

    private static final List<MerchantClassifier.Entry> TEST_ENTRIES = Arrays.asList(
            new MerchantClassifier.Entry("Whole Foods Market", "Grocery", 1.0,
                    Arrays.asList("whole foods", "whole foods market", "wfm", "who fds")),
            new MerchantClassifier.Entry("Trader Joe's", "Grocery", 0.8,
                    Arrays.asList("trader joe's", "trader joes", "tj")),
            new MerchantClassifier.Entry("Costco Wholesale", "Wholesale", 0.9,
                    Arrays.asList("costco", "costco wholesale"))
    );


    @BeforeEach
    void setUp() {
        MerchantClassifier.setEntriesForTest(TEST_ENTRIES);
    }


    @AfterEach
    void tearDown() {
        MerchantClassifier.setEntriesForTest(Collections.emptyList());
    }


    @Test
    @DisplayName("predict on a substring match returns the matching merchant")
    void shouldMatchBySubstring() {
        final MerchantClassifier.Prediction prediction = MerchantClassifier.predict("WHOLE FOODS MARKET");

        assertThat(prediction).isNotNull();
        assertThat(prediction.name).isEqualTo("Whole Foods Market");
    }


    @Test
    @DisplayName("predict matches case-insensitively")
    void shouldMatchCaseInsensitively() {
        final MerchantClassifier.Prediction prediction = MerchantClassifier.predict("whole foods");

        assertThat(prediction).isNotNull();
        assertThat(prediction.name).isEqualTo("Whole Foods Market");
    }


    @Test
    @DisplayName("predict matches against aliases")
    void shouldMatchByAlias() {
        final MerchantClassifier.Prediction prediction = MerchantClassifier.predict("WFM DOWNTOWN");

        assertThat(prediction).isNotNull();
        assertThat(prediction.name).isEqualTo("Whole Foods Market");
    }


    @Test
    @DisplayName("predict returns null on null or empty input")
    void shouldReturnNullOnEmptyInput() {
        assertThat(MerchantClassifier.predict(null)).isNull();
        assertThat(MerchantClassifier.predict("")).isNull();
        assertThat(MerchantClassifier.predict("   ")).isNull();
    }


    @Test
    @DisplayName("predict returns null when no entries are loaded")
    void shouldReturnNullWhenNoEntries() {
        MerchantClassifier.setEntriesForTest(Collections.emptyList());

        final MerchantClassifier.Prediction prediction = MerchantClassifier.predict("Whole Foods");

        assertThat(prediction).isNull();
    }


    @Test
    @DisplayName("predict returns null when no merchant matches the input")
    void shouldReturnNullWhenNoMatch() {
        final MerchantClassifier.Prediction prediction = MerchantClassifier.predict("Random Unrelated Store");

        assertThat(prediction).isNull();
    }


    @Test
    @DisplayName("Prediction carries the source (raw OCR) string back to the caller")
    void shouldCarrySource() {
        final MerchantClassifier.Prediction prediction = MerchantClassifier.predict("WHOLE FOODS");

        assertThat(prediction.source).isEqualTo("WHOLE FOODS");
    }


    @Test
    @DisplayName("topN returns up to N predictions sorted by score desc")
    void shouldReturnTopNPredictions() {
        final List<MerchantClassifier.Prediction> top = MerchantClassifier.topN("COSTCO WHOLE FOODS", 2);

        assertThat(top).hasSize(2);
    }


    @Test
    @DisplayName("topN with N=1 returns only the best prediction")
    void shouldReturnOneForTopOne() {
        final List<MerchantClassifier.Prediction> top = MerchantClassifier.topN("WHOLE FOODS", 1);

        assertThat(top).hasSize(1);
        assertThat(top.get(0).name).isEqualTo("Whole Foods Market");
    }


    @Test
    @DisplayName("topN with N larger than the entry count returns all matches")
    void shouldReturnAllMatchesWhenNIsLarger() {
        final List<MerchantClassifier.Prediction> top = MerchantClassifier.topN("WFM TJS COSTCO", 100);

        assertThat(top).hasSize(3);
    }


    @Test
    @DisplayName("topN with empty input returns an empty list")
    void shouldReturnEmptyOnEmptyInput() {
        final List<MerchantClassifier.Prediction> top = MerchantClassifier.topN("", 3);

        assertThat(top).isEmpty();
    }
}
