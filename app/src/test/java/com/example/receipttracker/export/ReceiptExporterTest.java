package com.example.receipttracker.export;


import com.example.receipttracker.data.Receipt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ReceiptExporterTest {

    private static Receipt sample(long id, long dateMillis, double amount, String photoPath) {
        return new Receipt(
                id,
                "Whole Foods",
                dateMillis,
                amount,
                photoPath,
                "raw ocr text",
                "lunch",
                1_704_067_200_001L,
                null,
                null,
                null);
    }


    @Test
    @DisplayName("baseNameFor formats as receipt_NNNNNN_yyyyMMdd")
    void shouldFormatBaseName() {
        // 2024-01-15 00:00:00 UTC
        final long dateMillis = 1_705_276_800_000L;

        final String baseName = ReceiptExporter.baseNameFor(sample(42, dateMillis, 47.83, "/tmp/r.jpg"));

        assertThat(baseName).isEqualTo("receipt_000042_20240115");
    }


    @Test
    @DisplayName("toJson with a photoPath includes the 'image' key with a .jpg filename")
    void shouldIncludeImageKeyWhenPhotoPresent() throws Exception {
        final long dateMillis = 1_705_276_800_000L;

        final org.json.JSONObject json = ReceiptExporter.toJson(
                sample(42, dateMillis, 47.83, "/tmp/r.jpg"));

        assertThat(json.has("image")).isTrue();
        assertThat(json.getString("image")).isEqualTo("receipt_000042_20240115.jpg");
    }


    @Test
    @DisplayName("toJson without a photoPath REMOVES the 'image' key (org.json put-on-null semantics)")
    void shouldRemoveImageKeyWhenNoPhoto() throws Exception {
        final long dateMillis = 1_705_276_800_000L;

        final org.json.JSONObject json = ReceiptExporter.toJson(
                sample(42, dateMillis, 47.83, null));

        // org.json:json 20231013's JSONObject.put(String, Object) calls
        // this.remove(key) when the value is null, so the key is removed
        // from the underlying map. (The source code comment in
        // ReceiptExporter.toJson is correct on this point.)
        assertThat(json.has("image")).isFalse();
        // isNull() returns true for missing keys too, because
        // JSONObject.Null.equals(null) is true.
        assertThat(json.isNull("image")).isTrue();
    }


    @Test
    @DisplayName("toJson includes the merchant, amount, and date")
    void shouldIncludeCoreFields() throws Exception {
        final long dateMillis = 1_705_276_800_000L;

        final org.json.JSONObject json = ReceiptExporter.toJson(
                sample(42, dateMillis, 47.83, "/tmp/r.jpg"));

        assertThat(json.getString("merchant")).isEqualTo("Whole Foods");
        assertThat(json.getDouble("amount")).isEqualTo(47.83);
        assertThat(json.getLong("dateMillis")).isEqualTo(dateMillis);
    }


    @Test
    @DisplayName("toJson includes the formatted amount and date as human-readable strings")
    void shouldIncludeFormattedFields() throws Exception {
        final long dateMillis = 1_705_276_800_000L;

        final org.json.JSONObject json = ReceiptExporter.toJson(
                sample(42, dateMillis, 47.83, "/tmp/r.jpg"));

        assertThat(json.getString("amountFormatted")).contains("$");
        assertThat(json.getString("amountFormatted")).contains("47.83");
        assertThat(json.getString("date")).matches("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{1,2}, 2024");
    }


    @Test
    @DisplayName("toJson includes rawText and notes")
    void shouldIncludeRawTextAndNotes() throws Exception {
        final long dateMillis = 1_705_276_800_000L;

        final org.json.JSONObject json = ReceiptExporter.toJson(
                sample(42, dateMillis, 47.83, "/tmp/r.jpg"));

        assertThat(json.getString("rawText")).isEqualTo("raw ocr text");
        assertThat(json.getString("notes")).isEqualTo("lunch");
    }


    @Test
    @DisplayName("toJson carries the id and createdAt")
    void shouldIncludeIdAndCreatedAt() throws Exception {
        final long dateMillis = 1_705_276_800_000L;

        final org.json.JSONObject json = ReceiptExporter.toJson(
                sample(42, dateMillis, 47.83, "/tmp/r.jpg"));

        assertThat(json.getLong("id")).isEqualTo(42L);
        assertThat(json.getLong("createdAt")).isEqualTo(1_704_067_200_001L);
    }


    @Test
    @DisplayName("toJson with a null merchant REMOVES the merchant key (org.json put-on-null semantics)")
    void shouldRemoveMerchantKeyWhenAbsent() throws Exception {
        final long dateMillis = 1_705_276_800_000L;

        final Receipt noMerchant = new Receipt(
                1L,
                null,
                dateMillis,
                10.0,
                "/tmp/r.jpg",
                "raw",
                "note",
                0L,
                null,
                null,
                null);

        final org.json.JSONObject json = ReceiptExporter.toJson(noMerchant);

        // org.json's put(key, null) calls this.remove(key), so the
        // merchant key is removed from the underlying map.
        assertThat(json.has("merchant")).isFalse();
        // opt() returns null both when the key is missing and when the
        // value is JSONObject.NULL.
        assertThat(json.opt("merchant")).isNull();
    }
}
