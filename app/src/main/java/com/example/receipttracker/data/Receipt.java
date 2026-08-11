package com.example.receipttracker.data;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;


/**
 * A scanned receipt. All fields are nullable except id, amount and date because the OCR pass
 * is best-effort - we keep what we got and let the user fix the rest on the edit screen.
 *
 * <p>Immutable: every mutation goes through a {@code with*} method that returns a new
 * instance with the requested field replaced. The {@code id} field is the only one we
 * do not provide a {@code with*} for; it is the primary key and never changes once
 * assigned by Room.</p>
 */
@Entity(
        tableName = "receipts",
        indices = {
                // budgetId is queried for "all receipts in budget X" and the
                // sum-of-amounts query; index avoids full scans.
                @Index(value = "budgetId"),
                // deletedAt is filtered in most queries (deletedAt IS NULL).
                @Index(value = "deletedAt")
        }
)
public final class Receipt {

    @PrimaryKey(autoGenerate = true)
    public final long id;


    @Nullable
    public final String merchant;


    /** Epoch millis at midnight local time of the receipt date. */
    public final long dateMillis;


    /** Receipt total in major units (e.g. dollars). Always positive. */
    public final double amount;


    /** Absolute path to the JPEG of the original receipt, or null if not saved. */
    @Nullable
    public final String photoPath;


    /** Raw OCR text so the user can sanity-check what was read. */
    @Nullable
    public final String rawText;


    @Nullable
    public final String notes;


    public final long createdAt;


    /**
     * Shared match-group id. When non-null, points to the same UUID on the matched
     * BankTransaction row. Null means unmatched.
     */
    @Nullable
    public final String matchGroupId;


    /**
     * Optional FK into {@link Budget#id}. When a receipt's total is verified
     * with an active budget in place, this gets set automatically. Null
     * means the receipt is not in any budget.
     */
    @Nullable
    public final Long budgetId;


    /**
     * Soft-delete tombstone. Null = active. Non-null = the user "cleared" this
     * receipt (it stays in the DB but is hidden from normal views and excluded
     * from budget sums). Use {@code Show deleted} in the receipts list to
     * recover.
     */
    @Nullable
    public final Long deletedAt;


    public Receipt(
            final long id,
            @Nullable final String merchant,
            final long dateMillis,
            final double amount,
            @Nullable final String photoPath,
            @Nullable final String rawText,
            @Nullable final String notes,
            final long createdAt,
            @Nullable final String matchGroupId,
            @Nullable final Long budgetId,
            @Nullable final Long deletedAt) {
        this.id = id;
        this.merchant = merchant;
        this.dateMillis = dateMillis;
        this.amount = amount;
        this.photoPath = photoPath;
        this.rawText = rawText;
        this.notes = notes;
        this.createdAt = createdAt;
        this.matchGroupId = matchGroupId;
        this.budgetId = budgetId;
        this.deletedAt = deletedAt;
    }


    public Receipt withMerchant(@Nullable final String newMerchant) {
        if (newMerchant == null ? this.merchant == null : newMerchant.equals(this.merchant)) {
            return this;
        }
        return new Receipt(id, newMerchant, dateMillis, amount, photoPath, rawText, notes,
                createdAt, matchGroupId, budgetId, deletedAt);
    }


    public Receipt withDateMillis(final long newDateMillis) {
        if (newDateMillis == this.dateMillis) {
            return this;
        }
        return new Receipt(id, merchant, newDateMillis, amount, photoPath, rawText, notes,
                createdAt, matchGroupId, budgetId, deletedAt);
    }


    public Receipt withAmount(final double newAmount) {
        if (newAmount == this.amount) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, newAmount, photoPath, rawText, notes,
                createdAt, matchGroupId, budgetId, deletedAt);
    }


    public Receipt withPhotoPath(@Nullable final String newPhotoPath) {
        if (newPhotoPath == null ? this.photoPath == null : newPhotoPath.equals(this.photoPath)) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, amount, newPhotoPath, rawText, notes,
                createdAt, matchGroupId, budgetId, deletedAt);
    }


    public Receipt withRawText(@Nullable final String newRawText) {
        if (newRawText == null ? this.rawText == null : newRawText.equals(this.rawText)) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, amount, photoPath, newRawText, notes,
                createdAt, matchGroupId, budgetId, deletedAt);
    }


    public Receipt withNotes(@Nullable final String newNotes) {
        if (newNotes == null ? this.notes == null : newNotes.equals(this.notes)) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, amount, photoPath, rawText, newNotes,
                createdAt, matchGroupId, budgetId, deletedAt);
    }


    public Receipt withCreatedAt(final long newCreatedAt) {
        if (newCreatedAt == this.createdAt) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, amount, photoPath, rawText, notes,
                newCreatedAt, matchGroupId, budgetId, deletedAt);
    }


    public Receipt withMatchGroupId(@Nullable final String newMatchGroupId) {
        if (newMatchGroupId == null ? this.matchGroupId == null
                : newMatchGroupId.equals(this.matchGroupId)) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, amount, photoPath, rawText, notes,
                createdAt, newMatchGroupId, budgetId, deletedAt);
    }


    public Receipt withBudgetId(@Nullable final Long newBudgetId) {
        if (newBudgetId == null ? this.budgetId == null : newBudgetId.equals(this.budgetId)) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, amount, photoPath, rawText, notes,
                createdAt, matchGroupId, newBudgetId, deletedAt);
    }


    public Receipt withDeletedAt(@Nullable final Long newDeletedAt) {
        if (newDeletedAt == null ? this.deletedAt == null : newDeletedAt.equals(this.deletedAt)) {
            return this;
        }
        return new Receipt(id, merchant, dateMillis, amount, photoPath, rawText, notes,
                createdAt, matchGroupId, budgetId, newDeletedAt);
    }


    @NonNull
    @Override
    public String toString() {
        return "Receipt{id=" + id + ", merchant='" + merchant + "', amount=" + amount
                + ", dateMillis=" + dateMillis + ", budgetId=" + budgetId
                + ", deletedAt=" + deletedAt + "}";
    }
}
