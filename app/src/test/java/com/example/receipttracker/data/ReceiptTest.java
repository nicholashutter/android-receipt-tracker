package com.example.receipttracker.data;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ReceiptTest {

    private static Receipt sample() {
        return new Receipt(
                42L,
                "Whole Foods",
                1_704_067_200_000L,
                47.83,
                "/tmp/receipt.jpg",
                "raw text",
                "lunch",
                1_704_067_200_001L,
                "group-1",
                7L,
                null);
    }


    @Test
    @DisplayName("constructor preserves all fields")
    void shouldPreserveAllFields() {
        final Receipt receipt = sample();

        assertThat(receipt.id).isEqualTo(42L);
        assertThat(receipt.merchant).isEqualTo("Whole Foods");
        assertThat(receipt.dateMillis).isEqualTo(1_704_067_200_000L);
        assertThat(receipt.amount).isEqualTo(47.83);
        assertThat(receipt.photoPath).isEqualTo("/tmp/receipt.jpg");
        assertThat(receipt.rawText).isEqualTo("raw text");
        assertThat(receipt.notes).isEqualTo("lunch");
        assertThat(receipt.createdAt).isEqualTo(1_704_067_200_001L);
        assertThat(receipt.matchGroupId).isEqualTo("group-1");
        assertThat(receipt.budgetId).isEqualTo(7L);
        assertThat(receipt.deletedAt).isNull();
    }


    @Test
    @DisplayName("withMerchant with a new value returns a new instance")
    void shouldReturnNewInstanceOnMerchantChange() {
        final Receipt original = sample();

        final Receipt updated = original.withMerchant("Trader Joe's");

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.merchant).isEqualTo("Trader Joe's");
        assertThat(original.merchant).isEqualTo("Whole Foods");
    }


    @Test
    @DisplayName("withMerchant with the same value returns the same instance (no-op)")
    void shouldReturnSameInstanceWhenMerchantUnchanged() {
        final Receipt original = sample();

        final Receipt updated = original.withMerchant("Whole Foods");

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withMerchant preserves all other fields")
    void shouldPreserveOtherFieldsOnMerchantChange() {
        final Receipt original = sample();

        final Receipt updated = original.withMerchant("Costco");

        assertThat(updated.id).isEqualTo(original.id);
        assertThat(updated.amount).isEqualTo(original.amount);
        assertThat(updated.budgetId).isEqualTo(original.budgetId);
        assertThat(updated.matchGroupId).isEqualTo(original.matchGroupId);
    }


    @Test
    @DisplayName("withDateMillis replaces only the date")
    void shouldReplaceDateMillis() {
        final Receipt original = sample();

        final long newDate = 1_705_276_800_000L;

        final Receipt updated = original.withDateMillis(newDate);

        assertThat(updated.dateMillis).isEqualTo(newDate);
        assertThat(updated.amount).isEqualTo(original.amount);
    }


    @Test
    @DisplayName("withDateMillis with the same value returns the same instance")
    void shouldReturnSameInstanceWhenDateUnchanged() {
        final Receipt original = sample();

        final Receipt updated = original.withDateMillis(original.dateMillis);

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withAmount replaces only the amount")
    void shouldReplaceAmount() {
        final Receipt original = sample();

        final Receipt updated = original.withAmount(99.99);

        assertThat(updated.amount).isEqualTo(99.99);
        assertThat(updated.merchant).isEqualTo(original.merchant);
    }


    @Test
    @DisplayName("withAmount with the same value returns the same instance")
    void shouldReturnSameInstanceWhenAmountUnchanged() {
        final Receipt original = sample();

        final Receipt updated = original.withAmount(original.amount);

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withBudgetId replaces only the budgetId")
    void shouldReplaceBudgetId() {
        final Receipt original = sample();

        final Receipt updated = original.withBudgetId(99L);

        assertThat(updated.budgetId).isEqualTo(99L);
        assertThat(updated.matchGroupId).isEqualTo(original.matchGroupId);
    }


    @Test
    @DisplayName("withBudgetId(null) clears the link")
    void shouldClearBudgetId() {
        final Receipt original = sample();

        final Receipt updated = original.withBudgetId(null);

        assertThat(updated.budgetId).isNull();
        assertThat(original.budgetId).isEqualTo(7L);
    }


    @Test
    @DisplayName("withBudgetId with the same value returns the same instance")
    void shouldReturnSameInstanceWhenBudgetIdUnchanged() {
        final Receipt original = sample();

        final Receipt updated = original.withBudgetId(original.budgetId);

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withMatchGroupId replaces only the matchGroupId")
    void shouldReplaceMatchGroupId() {
        final Receipt original = sample();

        final Receipt updated = original.withMatchGroupId("group-2");

        assertThat(updated.matchGroupId).isEqualTo("group-2");
        assertThat(updated.budgetId).isEqualTo(original.budgetId);
    }


    @Test
    @DisplayName("withNotes replaces only the notes")
    void shouldReplaceNotes() {
        final Receipt original = sample();

        final Receipt updated = original.withNotes("dinner");

        assertThat(updated.notes).isEqualTo("dinner");
        assertThat(original.notes).isEqualTo("lunch");
    }


    @Test
    @DisplayName("withPhotoPath replaces only the photoPath")
    void shouldReplacePhotoPath() {
        final Receipt original = sample();

        final Receipt updated = original.withPhotoPath("/tmp/other.jpg");

        assertThat(updated.photoPath).isEqualTo("/tmp/other.jpg");
    }


    @Test
    @DisplayName("withRawText replaces only the rawText")
    void shouldReplaceRawText() {
        final Receipt original = sample();

        final Receipt updated = original.withRawText("new text");

        assertThat(updated.rawText).isEqualTo("new text");
    }


    @Test
    @DisplayName("withDeletedAt sets the soft-delete tombstone")
    void shouldSetDeletedAt() {
        final Receipt original = sample();

        final long deletedAt = 1_705_276_800_000L;

        final Receipt updated = original.withDeletedAt(deletedAt);

        assertThat(updated.deletedAt).isEqualTo(deletedAt);
        assertThat(original.deletedAt).isNull();
    }


    @Test
    @DisplayName("withCreatedAt replaces only the createdAt")
    void shouldReplaceCreatedAt() {
        final Receipt original = sample();

        final long newCreatedAt = 1_706_000_000_000L;

        final Receipt updated = original.withCreatedAt(newCreatedAt);

        assertThat(updated.createdAt).isEqualTo(newCreatedAt);
        assertThat(original.createdAt).isEqualTo(1_704_067_200_001L);
    }


    @Test
    @DisplayName("constructor accepts nulls for optional fields")
    void shouldAcceptNulls() {
        final Receipt receipt = new Receipt(
                1L,
                null,
                0L,
                0.0,
                null,
                null,
                null,
                0L,
                null,
                null,
                null);

        assertThat(receipt.merchant).isNull();
        assertThat(receipt.photoPath).isNull();
        assertThat(receipt.rawText).isNull();
        assertThat(receipt.notes).isNull();
        assertThat(receipt.matchGroupId).isNull();
        assertThat(receipt.budgetId).isNull();
        assertThat(receipt.deletedAt).isNull();
    }
}
