package com.example.receipttracker.ocr;

import androidx.annotation.Nullable;

/**
 * The best-effort structured form of a scanned receipt. All fields are nullable
 * because the parser will only fill in what it's reasonably confident about;
 * the user is expected to fix anything missing on the edit screen.
 */
public class ParsedReceipt {

    @Nullable public String merchant;
    @Nullable public Long dateMillis;
    @Nullable public Double amount;

    @Override
    public String toString() {
        return "ParsedReceipt{merchant=" + merchant
                + ", dateMillis=" + dateMillis
                + ", amount=" + amount + "}";
    }
}
