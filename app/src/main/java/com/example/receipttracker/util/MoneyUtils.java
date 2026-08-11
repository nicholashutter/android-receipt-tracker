package com.example.receipttracker.util;


import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;


/**
 * Tiny utility for the two formatting tasks the UI needs everywhere:
 * currency-style dollar amounts and human-readable dates.
 */
public final class MoneyUtils {

    private static final String DATE_PATTERN = "MMM d, yyyy";
    private static final String CURRENCY_CODE = "USD";

    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(DATE_PATTERN, Locale.US);

    static {
        try {
            CURRENCY_FORMAT.setCurrency(Currency.getInstance(CURRENCY_CODE));
        } catch (Exception currencyInitException) {
            // Locale already implies USD for en_US; only fires if the JVM
            // doesn't have ISO-4217 data, which basically never happens.
        }
    }


    private MoneyUtils() {}


    public static String format(double amount) {
        return CURRENCY_FORMAT.format(amount);
    }


    public static String formatDate(long epochMillis) {
        return DATE_FORMAT.format(new Date(epochMillis));
    }
}
