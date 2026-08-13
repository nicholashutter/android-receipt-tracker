package com.example.receipttracker.data;


import android.content.Context;


import androidx.annotation.NonNull;

import androidx.room.Database;

import androidx.room.Room;

import androidx.room.RoomDatabase;

import androidx.room.migration.Migration;

import androidx.sqlite.db.SupportSQLiteDatabase;


/**
 * The one and only {@link RoomDatabase} for the app. Holds the three
 * entities (Receipt, BankTransaction, Budget) and the single v1->v2
 * migration that introduced the budgets table plus the matching
 * nullable columns on receipts.
 */
@Database(
        entities = {Receipt.class, BankTransaction.class, Budget.class},
        // v3: refactor (e309acc) re-ordered the entity annotations and added
        // explicit @Index declarations on receipts; the schema identity hash
        // changed even though the on-disk columns are identical. The user
        // approved fallbackToDestructiveMigration below for pre-alpha, so
        // upgrading just wipes the local DB. Field manuals to recreate any
        // receipts you need before reinstalling.
        // v4: parent/child budget hierarchy. Adds nullable parentId column
        // and a parentId index on the budgets table. NULL parentId = top-
        // level parent budget; non-null = sub-budget / leaf. All existing
        // v3 budgets become top-level parents (NULL parentId).
        version = 4,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "receipt_tracker.db";

    private static volatile AppDatabase instance;


    public abstract ReceiptDao receiptDao();

    public abstract BankTransactionDao bankTransactionDao();

    public abstract BudgetDao budgetDao();


    /**
     * v1 -> v2: add the {@code budgets} table, plus two nullable columns
     * on {@code receipts} ({@code budgetId}, {@code deletedAt}) and the
     * matching indices. All new bits are nullable / have defaults, so
     * the migration is purely additive and safe on existing data.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            final String createBudgets = "CREATE TABLE IF NOT EXISTS `budgets` ("
                    + "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, "
                    + "`name` TEXT, "
                    + "`maxAmount` REAL NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "`isActive` INTEGER NOT NULL, "
                    + "`isDeleted` INTEGER NOT NULL)";
            database.execSQL(createBudgets);

            final String createBudgetsIndex = "CREATE INDEX IF NOT EXISTS `index_budgets_isActive` "
                    + "ON `budgets` (`isActive`)";
            database.execSQL(createBudgetsIndex);

            final String addBudgetIdToReceipts = "ALTER TABLE `receipts` ADD COLUMN `budgetId` INTEGER";
            database.execSQL(addBudgetIdToReceipts);

            final String addDeletedAtToReceipts = "ALTER TABLE `receipts` ADD COLUMN `deletedAt` INTEGER";
            database.execSQL(addDeletedAtToReceipts);

            final String createReceiptsBudgetIdIndex = "CREATE INDEX IF NOT EXISTS `index_receipts_budgetId` "
                    + "ON `receipts` (`budgetId`)";
            database.execSQL(createReceiptsBudgetIdIndex);

            final String createReceiptsDeletedAtIndex = "CREATE INDEX IF NOT EXISTS `index_receipts_deletedAt` "
                    + "ON `receipts` (`deletedAt`)";
            database.execSQL(createReceiptsDeletedAtIndex);
        }
    };

    /**
     * v3 -> v4: add the {@code parentId} column to {@code budgets} so a
     * budget can be a top-level parent (NULL) or a sub-budget (non-null
     * pointing at the parent). Adds an index on parentId for the "list
     * children of this parent" query. Existing rows default to NULL
     * parentId, which keeps them as top-level parents — no semantics
     * change for any data written before v4.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            final String addParentId = "ALTER TABLE `budgets` ADD COLUMN `parentId` INTEGER";
            database.execSQL(addParentId);

            final String createParentIdIndex = "CREATE INDEX IF NOT EXISTS `index_budgets_parentId` "
                    + "ON `budgets` (`parentId`)";
            database.execSQL(createParentIdIndex);
        }
    };


    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    final AppDatabase built = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DATABASE_NAME)
                            .addMigrations(MIGRATION_1_2, MIGRATION_3_4)
                            // Last-resort: if a future version can't migrate,
                            // wipe rather than crash. Pre-alpha, so OK.
                            .fallbackToDestructiveMigration()
                            .build();
                    instance = built;
                }
            }
        }
        return instance;
    }
}
