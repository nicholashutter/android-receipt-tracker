package com.example.receipttracker.util;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;


class MoneyUtilsTest {

    @BeforeAll
    static void useUtcTimezone() {
        // MoneyUtils.formatDate uses the default TimeZone. Pin it to UTC
        // so tests are deterministic across machines regardless of the
        // host's local zone (epoch 0 is Jan 1 1970 in UTC, but Dec 31
        // 1969 in EST).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }


    @Test
    @DisplayName("format(1234.5) renders a USD-style currency string")
    void shouldRenderUsdCurrencyString() {
        final String formatted = MoneyUtils.format(1234.5);

        assertThat(formatted).contains("$");
        assertThat(formatted).contains("1,234");
        assertThat(formatted).contains("50");
    }


    @Test
    @DisplayName("format(0) renders as $0.00")
    void shouldRenderZeroAsZeroDollars() {
        final String formatted = MoneyUtils.format(0.0);

        assertThat(formatted).contains("0.00");
    }


    @Test
    @DisplayName("format(0.99) renders the cents")
    void shouldRenderCents() {
        final String formatted = MoneyUtils.format(0.99);

        assertThat(formatted).contains("0.99");
    }


    @Test
    @DisplayName("format(negative) renders the negative sign")
    void shouldRenderNegativeAmount() {
        final String formatted = MoneyUtils.format(-12.34);

        assertThat(formatted).contains("12.34");
    }


    @Test
    @DisplayName("formatDate(0) renders Jan 1, 1970 in UTC")
    void shouldFormatEpochZero() {
        final String formatted = MoneyUtils.formatDate(0L);

        assertThat(formatted).isEqualTo("Jan 1, 1970");
    }


    @Test
    @DisplayName("formatDate(2024-01-15T08:00:00Z) renders Jan 15, 2024")
    void shouldFormatArbitraryDate() {
        final long millis = 1_705_276_800_000L;

        final String formatted = MoneyUtils.formatDate(millis);

        assertThat(formatted).matches("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{1,2}, 2024");
    }
}
