package com.example.receipttracker.data;


import androidx.lifecycle.LiveData;

import androidx.room.Dao;

import androidx.room.Delete;

import androidx.room.Insert;

import androidx.room.Query;

import androidx.room.Update;


import java.util.List;


@Dao
public interface BankTransactionDao {

    @Insert
    long insert(BankTransaction tx);


    @Update
    void update(BankTransaction tx);


    @Delete
    void delete(BankTransaction tx);


    @Query("SELECT * FROM bank_transactions ORDER BY dateMillis DESC, id DESC")
    List<BankTransaction> getAll();


    @Query("SELECT * FROM bank_transactions ORDER BY dateMillis DESC, id DESC")
    LiveData<List<BankTransaction>> getAllLive();


    @Query("SELECT * FROM bank_transactions WHERE id = :id LIMIT 1")
    BankTransaction getById(long id);


    @Query("SELECT * FROM bank_transactions WHERE matchGroupId IS NULL ORDER BY dateMillis DESC, id DESC")
    List<BankTransaction> getUnmatched();


    @Query("SELECT * FROM bank_transactions WHERE matchGroupId IS NULL ORDER BY dateMillis DESC, id DESC")
    LiveData<List<BankTransaction>> getUnmatchedLive();


    @Query("SELECT * FROM bank_transactions WHERE matchGroupId IS NOT NULL ORDER BY dateMillis DESC, id DESC")
    List<BankTransaction> getMatched();


    @Query("SELECT * FROM bank_transactions WHERE matchGroupId IS NOT NULL ORDER BY dateMillis DESC, id DESC")
    LiveData<List<BankTransaction>> getMatchedLive();


    @Query("UPDATE bank_transactions SET matchGroupId = :groupId WHERE id = :id")
    void setMatchGroup(long id, String groupId);


    @Query("UPDATE bank_transactions SET matchGroupId = NULL WHERE id = :id")
    void clearMatchGroup(long id);


    @Query("SELECT COUNT(*) FROM bank_transactions")
    int count();


    @Query("SELECT COUNT(*) FROM bank_transactions")
    LiveData<Integer> countLive();
}
