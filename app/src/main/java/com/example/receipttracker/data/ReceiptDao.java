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

    /** All receipts including soft-deleted (admin / "show deleted" mode). */
    @Query("SELECT * FROM receipts ORDER BY dateMillis DESC, id DESC")
    List<Receipt> getAll();

    @Query("SELECT * FROM receipts ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getAllLive();

    /** All non-deleted receipts, newest first. This is the default view. */
    @Query("SELECT * FROM receipts WHERE deletedAt IS NULL ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getAllActiveLive();

    /** Count of active (non-deleted) receipts, for the main-screen pill. */
    @Query("SELECT COUNT(*) FROM receipts WHERE deletedAt IS NULL")
    LiveData<Integer> countActiveLive();

    @Query("SELECT * FROM receipts WHERE id = :id LIMIT 1")
    Receipt getById(long id);

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NULL AND deletedAt IS NULL "
            + "ORDER BY dateMillis DESC, id DESC")
    List<Receipt> getUnmatched();

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NULL AND deletedAt IS NULL "
            + "ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getUnmatchedLive();

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NOT NULL AND deletedAt IS NULL "
            + "ORDER BY dateMillis DESC, id DESC")
    List<Receipt> getMatched();

    @Query("SELECT * FROM receipts WHERE matchGroupId IS NOT NULL AND deletedAt IS NULL "
            + "ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getMatchedLive();

    @Query("UPDATE receipts SET matchGroupId = :groupId WHERE id = :id")
    void setMatchGroup(long id, String groupId);

    @Query("UPDATE receipts SET matchGroupId = NULL WHERE id = :id")
    void clearMatchGroup(long id);

    /** Soft-delete: set deletedAt to the current epoch ms. Receipts vanish
     *  from normal views but stay in the DB for recovery. */
    @Query("UPDATE receipts SET deletedAt = :deletedAt WHERE id = :id")
    void softDelete(long id, long deletedAt);

    @Query("UPDATE receipts SET deletedAt = NULL WHERE id = :id")
    void restore(long id);

    /** Bulk soft-delete: hides everything from the user. */
    @Query("UPDATE receipts SET deletedAt = :deletedAt WHERE deletedAt IS NULL")
    int softDeleteAll(long deletedAt);

    @Query("UPDATE receipts SET deletedAt = NULL WHERE deletedAt IS NOT NULL")
    int restoreAll();

    @Query("SELECT * FROM receipts WHERE budgetId = :budgetId AND deletedAt IS NULL "
            + "ORDER BY dateMillis DESC, id DESC")
    LiveData<List<Receipt>> getByBudgetLive(long budgetId);

    @Query("UPDATE receipts SET budgetId = :budgetId WHERE id = :id")
    void setBudget(long id, Long budgetId);

    /** Unlink a budget from any receipts that were in it. Used when a
     *  budget is soft-deleted so we don't leave dangling FKs. */
    @Query("UPDATE receipts SET budgetId = NULL WHERE budgetId = :budgetId")
    void clearBudgetOnReceipts(long budgetId);
}
