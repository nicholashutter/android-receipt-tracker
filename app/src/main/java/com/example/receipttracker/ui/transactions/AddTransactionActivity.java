package com.example.receipttracker.ui.transactions;


import android.app.DatePickerDialog;

import android.os.Bundle;

import android.widget.Toast;


import androidx.appcompat.app.AppCompatActivity;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.BankTransaction;

import com.example.receipttracker.data.BankTransactionDao;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;

import com.google.android.material.button.MaterialButton;

import com.google.android.material.textfield.TextInputEditText;


import java.util.Calendar;

import java.util.TimeZone;


/**
 * Editor for one bank transaction. In "new" mode (no {@link #EXTRA_TRANSACTION_ID}
 * extra) it just creates a fresh row on save. In "edit" mode it loads the
 * existing row, lets the user tweak it, and updates.
 */
public class AddTransactionActivity extends AppCompatActivity {

    public static final String EXTRA_TRANSACTION_ID = "tx_id";

    public static final String EXTRA_DESCRIPTION = "description";

    public static final String EXTRA_AMOUNT = "amount";

    public static final String EXTRA_DATE_MILLIS = "date_millis";

    public static final String EXTRA_ACCOUNT = "account";

    private static final String TAG = "AddTx";


    private TextInputEditText etDescription;

    private TextInputEditText etAmount;

    private TextInputEditText etDate;

    private TextInputEditText etAccount;

    private MaterialButton btnSave;

    private MaterialButton btnCancel;


    // MUTABLE: re-set on DatePicker callbacks.
    private long existingId = -1L;

    // MUTABLE: re-set on DatePicker callbacks.
    private long dateMillis = System.currentTimeMillis();


    private final AppExecutors executors = AppExecutors.get();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Logger.section("ADD TX");
        Logger.i(TAG, "onCreate existingId=" + existingId);
        setContentView(R.layout.activity_add_transaction);


        etDescription = findViewById(R.id.et_description);
        etAmount = findViewById(R.id.et_amount);
        etDate = findViewById(R.id.et_date);
        etAccount = findViewById(R.id.et_account);
        btnSave = findViewById(R.id.btn_save);
        btnCancel = findViewById(R.id.btn_cancel);


        etDate.setOnClickListener(clickedView -> showDatePicker());
        renderDate();


        if (getIntent().hasExtra(EXTRA_TRANSACTION_ID)) {
            existingId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, -1L);
            loadExisting();
        }


        btnSave.setOnClickListener(clickedView -> save());
        btnCancel.setOnClickListener(clickedView -> finish());
    }


    private void loadExisting() {
        final long idToLoad = existingId;
        executors.diskIO().execute(() -> {
            final BankTransaction existing = AppDatabase.get(AddTransactionActivity.this)
                    .bankTransactionDao().getById(idToLoad);
            executors.mainThread().execute(() -> {
                if (existing == null) {
                    finish();
                    return;
                }
                etDescription.setText(existing.description);
                etAmount.setText(String.valueOf(existing.amount));
                final String accountText;
                if (existing.account == null) {
                    accountText = "";
                } else {
                    accountText = existing.account;
                }
                etAccount.setText(accountText);
                dateMillis = existing.dateMillis;
                renderDate();
            });
        });
    }


    private void renderDate() {
        etDate.setText(MoneyUtils.formatDate(dateMillis));
    }


    private void showDatePicker() {
        final Calendar initial = Calendar.getInstance(TimeZone.getDefault());
        initial.setTimeInMillis(dateMillis);

        new DatePickerDialog(this,
                (view, pickedYear, pickedMonth, pickedDay) -> {
                    final Calendar picked = Calendar.getInstance(TimeZone.getDefault());
                    picked.clear();
                    picked.set(pickedYear, pickedMonth, pickedDay);
                    dateMillis = picked.getTimeInMillis();
                    renderDate();
                },
                initial.get(Calendar.YEAR),
                initial.get(Calendar.MONTH),
                initial.get(Calendar.DAY_OF_MONTH))
                .show();
    }


    private boolean validate() {
        final String descriptionText;
        if (etDescription.getText() == null) {
            descriptionText = "";
        } else {
            descriptionText = etDescription.getText().toString().trim();
        }

        boolean isValid = true;
        if (descriptionText.isEmpty()) {
            etDescription.setError(getString(R.string.error_required));
            isValid = false;
        }
        if (parseAmount() <= 0.0) {
            etAmount.setError(getString(R.string.error_invalid_amount));
            isValid = false;
        }
        return isValid;
    }


    private double parseAmount() {
        if (etAmount.getText() == null) return 0.0;

        final String rawText = etAmount.getText().toString().replace("$", "").replace(",", "").trim();
        if (rawText.isEmpty()) return 0.0;

        try {
            return Double.parseDouble(rawText);
        } catch (NumberFormatException parseFailure) {
            return 0.0;
        }
    }


    private void save() {
        if (!validate()) return;

        final long resolvedId;
        if (existingId >= 0L) {
            resolvedId = existingId;
        } else {
            resolvedId = 0L;
        }

        final String accountText;
        if (etAccount.getText() == null) {
            accountText = null;
        } else {
            accountText = etAccount.getText().toString().trim();
        }

        final BankTransaction draft = new BankTransaction(
                resolvedId,
                etDescription.getText().toString().trim(),
                dateMillis,
                parseAmount(),
                accountText,
                System.currentTimeMillis(),
                null);

        Logger.i(TAG, "save: id=" + draft.id
                + " desc='" + draft.description + "'"
                + " amount=" + draft.amount
                + " dateMillis=" + draft.dateMillis
                + " account='" + draft.account + "'");


        executors.diskIO().execute(() -> {
            final BankTransactionDao dao = AppDatabase.get(AddTransactionActivity.this)
                    .bankTransactionDao();

            if (draft.id > 0L) {
                final BankTransaction existing = dao.getById(draft.id);
                final BankTransaction toUpdate;
                if (existing != null) {
                    toUpdate = draft
                            .withMatchGroupId(existing.matchGroupId)
                            .withCreatedAt(existing.createdAt);
                } else {
                    toUpdate = draft;
                }
                dao.update(toUpdate);
                Logger.i(TAG, "Updated bank transaction id=" + toUpdate.id);
            } else {
                final long newId = dao.insert(draft);
                Logger.i(TAG, "Inserted bank transaction id=" + newId);
            }

            executors.mainThread().execute(() -> {
                Toast.makeText(AddTransactionActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }
}
