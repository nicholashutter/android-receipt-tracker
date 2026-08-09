package com.example.receipttracker.data;


import androidx.annotation.Nullable;

import androidx.room.Entity;

import androidx.room.PrimaryKey;


/**
 * A bank transaction entered manually from the user's statement.
 * Amount is always positive; the "this is a refund" semantics are out of scope for now.
 */
@Entity(tableName = "bank_transactions")
public class BankTransaction {

    @PrimaryKey(autoGenerate = true)
    public long id;


    public String description;


    public long dateMillis;


    public double amount;


    @Nullable
    public String account;


    public long createdAt;


    /**
     * Shared match-group id, matching the value on the linked Receipt row. Null = unmatched.
     */
    @Nullable
    public String matchGroupId;
}
