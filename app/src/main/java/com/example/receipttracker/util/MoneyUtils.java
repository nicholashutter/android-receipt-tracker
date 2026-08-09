package com.example.receipttracker.util;


import java.text.NumberFormat;

import java.util.Currency;

import java.util.Date;

import java.util.Locale;


public final class MoneyUtils {

    private static final NumberFormat FORMAT = NumberFormat.getCurrencyInstance(Locale.US);


    static {
        try {
            FORMAT.setCurrency(Currency.getInstance("USD"));
        } catch (Exception ignored) { }
    }


    private MoneyUtils() {}


    public static String format(double amount) {
        return FORMAT.format(amount);
    }


    private static final java.text.SimpleDateFormat DATE_FMT =
            new java.text.SimpleDateFormat("MMM d, yyyy", Locale.US);


    public static String formatDate(long millis) {
        return DATE_FMT.format(new Date(millis));
    }
}
