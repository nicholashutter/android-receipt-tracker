package com.example.receipttracker.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Regression guard for the NumberCategory enum. The auto-pick filter
 * references these symbols by name (TOTAL, SUBTOTAL, LINE_ITEM, ...) so
 * renaming or removing one will silently change pickCircledCandidate's
 * behaviour. This test pins the canonical set.
 */
class NumberCategoryTest {

    @Test
    @DisplayName("NumberCategory has the 13 canonical categories")
    void shouldExposeCanonicalCategories() {
        // The 13 categories kept since the v1.2.0 classifier rewrite.
        // Adding a new one is fine; removing or renaming one will break
        // the auto-pick filter and the edit-screen Re-pick picker.
        assertThat(NumberCategory.values())
                .containsExactly(
                        NumberCategory.TOTAL,
                        NumberCategory.SUBTOTAL,
                        NumberCategory.LINE_ITEM,
                        NumberCategory.TAX,
                        NumberCategory.TIP,
                        NumberCategory.DISCOUNT,
                        NumberCategory.PERCENTAGE,
                        NumberCategory.DATE,
                        NumberCategory.PHONE,
                        NumberCategory.AUTH_CODE,
                        NumberCategory.QUANTITY,
                        NumberCategory.YEAR,
                        NumberCategory.OTHER);
    }
}
