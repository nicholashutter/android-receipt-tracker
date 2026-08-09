package com.example.receipttracker.ocr;


import androidx.annotation.Nullable;


/**
 * One parsed receipt, populated by {@link ReceiptParser#parse(String)}.
 *
 * <p>Fields are best-effort; any of them can be null/0 if the OCR
 * didn't find anything plausible. {@code merchantPrediction} carries
 * the second-stage classifier's best guess + confidence; it is null
 * if the parser didn't see any plausible merchant text, or if the
 * classifier didn't load (assets missing, etc.).</p>
 */
public final class ParsedReceipt {
    @Nullable public String merchant;

    @Nullable public Long dateMillis;

    @Nullable public Double amount;

    @Nullable public String rawText;

    @Nullable public MerchantClassifier.Prediction merchantPrediction;
}
