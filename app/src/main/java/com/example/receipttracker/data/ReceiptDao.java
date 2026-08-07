package com.example.receipttracker.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ReceiptDao {

    @Insert
    long insert(Receipt receipt);

    @Update
    void update(Receipt receipt);

    @Delete
    void delete(Receipt receipt);

    @Query("SELECT * FROM receipts ORDER BY dateMillis DESC, id DESC")
    List<Receipt> getAll();

    @Query("SELECT * FROM receipts ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getAllLive();

    @Query("SELECT * FROM receipts WHERE id = :id LIMIT 1")
    Receipt getById(long id);

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NULL ORDER BY dateMillis DESC, id DESC")
    List<Receipt> getUnmatched();

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NULL ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getUnmatchedLive();

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NOT NULL ORDER BY dateMillis DESC, id DESC")
    List<Receipt> getMatched();

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NOT NULL ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getMatchedLive();

    @Query("UPDATE receipts SET matchGroupId = :groupId WHERE id = :id")
    void setMatchGroup(long id, String groupId);

    @Query("UPDATE receipts SET matchGroupId = NULL WHERE id = :id")
    void clearMatchGroup(long id);
}
