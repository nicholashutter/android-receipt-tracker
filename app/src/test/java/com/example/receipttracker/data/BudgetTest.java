package com.example.receipttracker.data;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class BudgetTest {

    @Test
    @DisplayName("full constructor preserves all fields")
    void shouldPreserveAllFields() {
        final Budget budget = new Budget(
                5L,
                "Groceries",
                200.0,
                1_704_067_200_000L,
                true,
                false);

        assertThat(budget.id).isEqualTo(5L);
        assertThat(budget.name).isEqualTo("Groceries");
        assertThat(budget.maxAmount).isEqualTo(200.0);
        assertThat(budget.createdAt).isEqualTo(1_704_067_200_000L);
        assertThat(budget.isActive).isTrue();
        assertThat(budget.isDeleted).isFalse();
    }


    @Test
    @DisplayName("two-arg convenience constructor sets defaults")
    void shouldSetDefaultsForConvenienceConstructor() {
        final long before = System.currentTimeMillis();

        final Budget budget = new Budget("Dining", 150.0);

        final long after = System.currentTimeMillis();

        assertThat(budget.id).isEqualTo(0L);
        assertThat(budget.name).isEqualTo("Dining");
        assertThat(budget.maxAmount).isEqualTo(150.0);
        assertThat(budget.createdAt).isBetween(before, after);
        assertThat(budget.isActive).isFalse();
        assertThat(budget.isDeleted).isFalse();
    }


    @Test
    @DisplayName("withName returns a new instance with the new name")
    void shouldReplaceName() {
        final Budget original = new Budget(1L, "Old", 100.0, 0L, false, false);

        final Budget updated = original.withName("New");

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.name).isEqualTo("New");
        assertThat(original.name).isEqualTo("Old");
    }


    @Test
    @DisplayName("withName with the same value returns the same instance")
    void shouldReturnSameInstanceWhenNameUnchanged() {
        final Budget original = new Budget(1L, "Same", 100.0, 0L, false, false);

        final Budget updated = original.withName("Same");

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withMaxAmount replaces only the max amount")
    void shouldReplaceMaxAmount() {
        final Budget original = new Budget(1L, "Name", 100.0, 0L, true, false);

        final Budget updated = original.withMaxAmount(250.0);

        assertThat(updated.maxAmount).isEqualTo(250.0);
        assertThat(updated.name).isEqualTo("Name");
        assertThat(updated.isActive).isTrue();
    }


    @Test
    @DisplayName("withMaxAmount with the same value returns the same instance")
    void shouldReturnSameInstanceWhenMaxAmountUnchanged() {
        final Budget original = new Budget(1L, "Name", 100.0, 0L, true, false);

        final Budget updated = original.withMaxAmount(100.0);

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withActive flips the active flag")
    void shouldReplaceActive() {
        final Budget original = new Budget(1L, "Name", 100.0, 0L, false, false);

        final Budget updated = original.withActive(true);

        assertThat(updated.isActive).isTrue();
        assertThat(original.isActive).isFalse();
    }


    @Test
    @DisplayName("withActive with the same value returns the same instance")
    void shouldReturnSameInstanceWhenActiveUnchanged() {
        final Budget original = new Budget(1L, "Name", 100.0, 0L, true, false);

        final Budget updated = original.withActive(true);

        assertThat(updated).isSameAs(original);
    }


    @Test
    @DisplayName("withDeleted flips the deleted flag")
    void shouldReplaceDeleted() {
        final Budget original = new Budget(1L, "Name", 100.0, 0L, false, false);

        final Budget updated = original.withDeleted(true);

        assertThat(updated.isDeleted).isTrue();
        assertThat(original.isDeleted).isFalse();
    }


    @Test
    @DisplayName("withDeleted with the same value returns the same instance")
    void shouldReturnSameInstanceWhenDeletedUnchanged() {
        final Budget original = new Budget(1L, "Name", 100.0, 0L, false, true);

        final Budget updated = original.withDeleted(true);

        assertThat(updated).isSameAs(original);
    }
}
