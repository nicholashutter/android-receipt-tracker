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

public class AddTransactionActivity extends AppCompatActivity {

    public static final String EXTRA_TRANSACTION_ID = "tx_id";
    public static final String EXTRA_DESCRIPTION = "description";
    public static final String EXTRA_AMOUNT = "amount";
    public static final String EXTRA_DATE_MILLIS = "date_millis";
    public static final String EXTRA_ACCOUNT = "account";

    private TextInputEditText etDescription, etAmount, etDate, etAccount;
    private MaterialButton btnSave, btnCancel;

    private long existingId = -1;
    private long dateMillis = System.currentTimeMillis();

    private final AppExecutors exec = AppExecutors.get();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("ADD TX");
        Logger.i("AddTx", "onCreate existingId=" + existingId);
        setContentView(R.layout.activity_add_transaction);

        etDescription = findViewById(R.id.et_description);
        etAmount = findViewById(R.id.et_amount);
        etDate = findViewById(R.id.et_date);
        etAccount = findViewById(R.id.et_account);
        btnSave = findViewById(R.id.btn_save);
        btnCancel = findViewById(R.id.btn_cancel);

        etDate.setOnClickListener(v -> showDatePicker());
        renderDate();

        if (getIntent().hasExtra(EXTRA_TRANSACTION_ID)) {
            existingId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, -1);
            loadExisting();
        }

        btnSave.setOnClickListener(v -> {
            Logger.i("AddTx", "btn_save clicked: desc='" + (etDescription.getText() == null ? "" : etDescription.getText())
                    + "' amount='" + (etAmount.getText() == null ? "" : etAmount.getText()) + "'");
            save();
        });
        btnCancel.setOnClickListener(v -> {
            Logger.i("AddTx", "btn_cancel clicked");
            finish();
        });
    }

    private void loadExisting() {
        final long id = existingId;
        exec.diskIO().execute(() -> {
            BankTransaction t = AppDatabase.get(AddTransactionActivity.this)
                    .bankTransactionDao().getById(id);
            exec.mainThread().execute(() -> {
                if (t == null) { finish(); return; }
                etDescription.setText(t.description);
                etAmount.setText(String.valueOf(t.amount));
                etAccount.setText(t.account == null ? "" : t.account);
                dateMillis = t.dateMillis;
                renderDate();
            });
        });
    }

    private void renderDate() {
        etDate.setText(MoneyUtils.formatDate(dateMillis));
    }

    private void showDatePicker() {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(dateMillis);
        new DatePickerDialog(this,
                (view, year, month, day) -> {
                    Calendar n = Calendar.getInstance(TimeZone.getDefault());
                    n.clear();
                    n.set(year, month, day);
                    dateMillis = n.getTimeInMillis();
                    renderDate();
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                .show();
    }

    private boolean validate() {
        boolean ok = true;
        if (etDescription.getText() == null || etDescription.getText().toString().trim().isEmpty()) {
            etDescription.setError(getString(R.string.error_required));
            ok = false;
        }
        if (parseAmount() <= 0) {
            etAmount.setError(getString(R.string.error_invalid_amount));
            ok = false;
        }
        return ok;
    }

    private double parseAmount() {
        if (etAmount.getText() == null) return 0;
        String s = etAmount.getText().toString().replace("$", "").replace(",", "").trim();
        if (s.isEmpty()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private void save() {
        if (!validate()) return;
        final BankTransaction t = new BankTransaction();
        t.id = existingId >= 0 ? existingId : 0;
        t.description = etDescription.getText().toString().trim();
        t.amount = parseAmount();
        t.dateMillis = dateMillis;
        t.account = etAccount.getText() == null ? null : etAccount.getText().toString().trim();
        t.createdAt = System.currentTimeMillis();
        Logger.i("AddTx", "save: id=" + t.id + " desc='" + t.description + "' amount=" + t.amount
                + " dateMillis=" + t.dateMillis + " account='" + t.account + "'");

        exec.diskIO().execute(() -> {
            BankTransactionDao dao = AppDatabase.get(AddTransactionActivity.this).bankTransactionDao();
            if (t.id > 0) {
                BankTransaction existing = dao.getById(t.id);
                if (existing != null) {
                    t.matchGroupId = existing.matchGroupId;
                    t.createdAt = existing.createdAt;
                }
                dao.update(t);
                Logger.i("AddTx", "Updated bank transaction id=" + t.id);
            } else {
                long newId = dao.insert(t);
                Logger.i("AddTx", "Inserted bank transaction id=" + newId);
            }
            exec.mainThread().execute(() -> {
                Toast.makeText(AddTransactionActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }
}
