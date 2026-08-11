package com.example.receipttracker.data;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class BankTransactionTest {

    private static BankTransaction sample() {
        return new BankTransaction(
                11L,
                "WHOLEFDS NYC",
                1_704_067_200_000L,
                47.83,
                "Checking",
                1_704_067_200_001L,
                "group-1");
    }


    @Test
    @DisplayName("constructor preserves all fields")
    void shouldPreserveAllFields() {
        final BankTransaction tx = sample();

        assertThat(tx.id).isEqualTo(11L);
        assertThat(tx.description).isEqualTo("WHOLEFDS NYC");
        assertThat(tx.dateMillis).isEqualTo(1_704_067_200_000L);
        assertThat(tx.amount).isEqualTo(47.83);
        assertThat(tx.account).isEqualTo("Checking");
        assertThat(tx.createdAt).isEqualTo(1_704_067_200_001L);
        assertThat(tx.matchGroupId).isEqualTo("group-1");
    }


    @Test
    @DisplayName("withDescription replaces only the description")
    void shouldReplaceDescription() {
        final BankTransaction original = sample();

        final BankTransaction updated = original.withDescription("WHOLEFDS BOSTON");

        assertThat(updated.description).isEqualTo("WHOLEFDS BOSTON");
        assertThat(original.description).isEqualTo("WHOLEFDS NYC");
        assertThat(updated.amount).isEqualTo(original.amount);
    }


    @Test
    @DisplayName("withDescription with the same value returns the same instance")
    void shouldReturnSameInstanceWhenDescriptionUnchanged() {
        final BankTransaction original = sample();

        final BankTransaction updated = original.withDescription(original.description);

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withDateMillis replaces only the date")
    void shouldReplaceDateMillis() {
        final BankTransaction original = sample();

        final long newDate = 1_705_276_800_000L;

        final BankTransaction updated = original.withDateMillis(newDate);

        assertThat(updated.dateMillis).isEqualTo(newDate);
        assertThat(original.dateMillis).isEqualTo(1_704_067_200_000L);
    }


    @Test
    @DisplayName("withAmount replaces only the amount")
    void shouldReplaceAmount() {
        final BankTransaction original = sample();

        final BankTransaction updated = original.withAmount(99.50);

        assertThat(updated.amount).isEqualTo(99.50);
        assertThat(original.amount).isEqualTo(47.83);
    }


    @Test
    @DisplayName("withAccount replaces only the account")
    void shouldReplaceAccount() {
        final BankTransaction original = sample();

        final BankTransaction updated = original.withAccount("Savings");

        assertThat(updated.account).isEqualTo("Savings");
        assertThat(original.account).isEqualTo("Checking");
    }


    @Test
    @DisplayName("withAccount(null) clears the account")
    void shouldClearAccount() {
        final BankTransaction original = sample();

        final BankTransaction updated = original.withAccount(null);

        assertThat(updated.account).isNull();
        assertThat(original.account).isEqualTo("Checking");
    }


    @Test
    @DisplayName("withMatchGroupId replaces only the matchGroupId")
    void shouldReplaceMatchGroupId() {
        final BankTransaction original = sample();

        final BankTransaction updated = original.withMatchGroupId("group-2");

        assertThat(updated.matchGroupId).isEqualTo("group-2");
        assertThat(original.matchGroupId).isEqualTo("group-1");
    }


    @Test
    @DisplayName("withMatchGroupId(null) unlinks the transaction")
    void shouldUnlinkTransaction() {
        final BankTransaction original = sample();

        final BankTransaction updated = original.withMatchGroupId(null);

        assertThat(updated.matchGroupId).isNull();
    }


    @Test
    @DisplayName("withCreatedAt replaces only the createdAt")
    void shouldReplaceCreatedAt() {
        final BankTransaction original = sample();

        final long newCreatedAt = 1_706_000_000_000L;

        final BankTransaction updated = original.withCreatedAt(newCreatedAt);

        assertThat(updated.createdAt).isEqualTo(newCreatedAt);
    }
}
