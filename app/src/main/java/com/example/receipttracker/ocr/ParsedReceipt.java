package com.example.receipttracker.ocr;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


/**
 * One parsed receipt, populated by {@link ReceiptParser#parse(String)}.
 *
 * <p>Fields are best-effort; any of them can be null/0 if the OCR
 * didn't find anything plausible. {@code merchantPrediction} carries
 * the second-stage classifier's best guess + confidence; it is null
 * if the parser didn't see any plausible merchant text, or if the
 * classifier didn't load (assets missing, etc.).</p>
 *
 * <p>Immutable: every variant is produced by {@link #with} on the
 * static {@link #EMPTY} instance; this keeps the parser code free of
 * in-place mutation and ensures every receipt value flows through one
 * factory.</p>
 */
public final class ParsedReceipt {

    public static final ParsedReceipt EMPTY = new ParsedReceipt(null, null, null, null, null);


    @Nullable public final String merchant;
    @Nullable public final Long dateMillis;
    @Nullable public final Double amount;
    @Nullable public final String rawText;
    @Nullable public final MerchantClassifier.Prediction merchantPrediction;


    public ParsedReceipt(
            @Nullable final String merchant,
            @Nullable final Long dateMillis,
            @Nullable final Double amount,
            @Nullable final String rawText,
            @Nullable final MerchantClassifier.Prediction merchantPrediction) {
        this.merchant = merchant;
        this.dateMillis = dateMillis;
        this.amount = amount;
        this.rawText = rawText;
        this.merchantPrediction = merchantPrediction;
    }


    public ParsedReceipt withMerchant(@Nullable final String newMerchant) {
        if (newMerchant == null ? this.merchant == null : newMerchant.equals(this.merchant)) {
            return this;
        }
        return new ParsedReceipt(newMerchant, dateMillis, amount, rawText, merchantPrediction);
    }


    public ParsedReceipt withDateMillis(@Nullable final Long newDateMillis) {
        if (newDateMillis == null ? this.dateMillis == null : newDateMillis.equals(this.dateMillis)) {
            return this;
        }
        return new ParsedReceipt(merchant, newDateMillis, amount, rawText, merchantPrediction);
    }


    public ParsedReceipt withAmount(@Nullable final Double newAmount) {
        if (newAmount == null ? this.amount == null : newAmount.equals(this.amount)) {
            return this;
        }
        return new ParsedReceipt(merchant, dateMillis, newAmount, rawText, merchantPrediction);
    }


    public ParsedReceipt withRawText(@Nullable final String newRawText) {
        if (newRawText == null ? this.rawText == null : newRawText.equals(this.rawText)) {
            return this;
        }
        return new ParsedReceipt(merchant, dateMillis, amount, newRawText, merchantPrediction);
    }


    public ParsedReceipt withMerchantPrediction(@Nullable final MerchantClassifier.Prediction newPrediction) {
        if (newPrediction == this.merchantPrediction) {
            return this;
        }
        return new ParsedReceipt(merchant, dateMillis, amount, rawText, newPrediction);
    }


    @NonNull
    @Override
    public String toString() {
        return "ParsedReceipt{merchant='" + merchant + "', dateMillis=" + dateMillis
                + ", amount=" + amount + "}";
    }
}
