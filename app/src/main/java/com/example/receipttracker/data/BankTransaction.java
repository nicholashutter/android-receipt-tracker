package com.example.receipttracker.data;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;


/**
 * A bank transaction entered manually from the user's statement.
 * Amount is always positive; the "this is a refund" semantics are out of scope for now.
 *
 * <p>Immutable: every mutation goes through a {@code with*} method that returns
 * a new instance with the requested field replaced. The {@code id} field is the
 * only one we do not provide a {@code with*} for; it is the primary key and
 * never changes once assigned by Room.</p>
 */
@Entity(tableName = "bank_transactions")
public final class BankTransaction {

    @PrimaryKey(autoGenerate = true)
    public final long id;


    @NonNull
    public final String description;


    public final long dateMillis;


    public final double amount;


    @Nullable
    public final String account;


    public final long createdAt;


    /**
     * Shared match-group id, matching the value on the linked Receipt row. Null = unmatched.
     */
    @Nullable
    public final String matchGroupId;


    public BankTransaction(
            final long id,
            @NonNull final String description,
            final long dateMillis,
            final double amount,
            @Nullable final String account,
            final long createdAt,
            @Nullable final String matchGroupId) {
        this.id = id;
        this.description = description;
        this.dateMillis = dateMillis;
        this.amount = amount;
        this.account = account;
        this.createdAt = createdAt;
        this.matchGroupId = matchGroupId;
    }


    public BankTransaction withDescription(@NonNull final String newDescription) {
        if (newDescription.equals(this.description)) {
            return this;
        }
        return new BankTransaction(id, newDescription, dateMillis, amount, account, createdAt, matchGroupId);
    }


    public BankTransaction withDateMillis(final long newDateMillis) {
        if (newDateMillis == this.dateMillis) {
            return this;
        }
        return new BankTransaction(id, description, newDateMillis, amount, account, createdAt, matchGroupId);
    }


    public BankTransaction withAmount(final double newAmount) {
        if (newAmount == this.amount) {
            return this;
        }
        return new BankTransaction(id, description, dateMillis, newAmount, account, createdAt, matchGroupId);
    }


    public BankTransaction withAccount(@Nullable final String newAccount) {
        if (newAccount == null ? this.account == null : newAccount.equals(this.account)) {
            return this;
        }
        return new BankTransaction(id, description, dateMillis, amount, newAccount, createdAt, matchGroupId);
    }


    public BankTransaction withMatchGroupId(@Nullable final String newMatchGroupId) {
        if (newMatchGroupId == null ? this.matchGroupId == null
                : newMatchGroupId.equals(this.matchGroupId)) {
            return this;
        }
        return new BankTransaction(id, description, dateMillis, amount, account, createdAt, newMatchGroupId);
    }


    public BankTransaction withCreatedAt(final long newCreatedAt) {
        if (newCreatedAt == this.createdAt) {
            return this;
        }
        return new BankTransaction(id, description, dateMillis, amount, account, newCreatedAt, matchGroupId);
    }


    @NonNull
    @Override
    public String toString() {
        return "BankTransaction{id=" + id + ", description='" + description + "', amount="
                + amount + ", matchGroupId=" + matchGroupId + "}";
    }
}
