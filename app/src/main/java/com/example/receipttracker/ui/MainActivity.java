package com.example.receipttracker.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.receipttracker.R;
import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ui.debug.LogsActivity;
import com.example.receipttracker.ui.export.ExportActivity;
import com.example.receipttracker.ui.match.MatchActivity;
import com.example.receipttracker.ui.receipts.ReceiptListActivity;
import com.example.receipttracker.ui.scan.ScanReceiptActivity;
import com.example.receipttracker.ui.transactions.AddTransactionActivity;
import com.example.receipttracker.ui.transactions.TransactionListActivity;

public class MainActivity extends AppCompatActivity {

    private TextView tvReceipts;
    private TextView tvTransactions;
    private AppDatabase db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i("Main", "onCreate");
        setContentView(R.layout.activity_main);

        db = AppDatabase.get(this);

        tvReceipts = findViewById(R.id.btn_view_receipts);
        tvTransactions = findViewById(R.id.btn_view_transactions);

        findViewById(R.id.btn_scan).setOnClickListener(v -> {
            Logger.i("Main", "btn_scan clicked");
            startActivity(new Intent(this, ScanReceiptActivity.class));
        });
        findViewById(R.id.btn_view_receipts).setOnClickListener(v -> {
            Logger.i("Main", "btn_view_receipts clicked");
            startActivity(new Intent(this, ReceiptListActivity.class));
        });
        findViewById(R.id.btn_add_transaction).setOnClickListener(v -> {
            Logger.i("Main", "btn_add_transaction clicked");
            startActivity(new Intent(this, AddTransactionActivity.class));
        });
        findViewById(R.id.btn_view_transactions).setOnClickListener(v -> {
            Logger.i("Main", "btn_view_transactions clicked");
            startActivity(new Intent(this, TransactionListActivity.class));
        });
        findViewById(R.id.btn_match).setOnClickListener(v -> {
            Logger.i("Main", "btn_match clicked");
            startActivity(new Intent(this, MatchActivity.class));
        });
        findViewById(R.id.btn_export).setOnClickListener(v -> {
            Logger.i("Main", "btn_export clicked");
            startActivity(new Intent(this, ExportActivity.class));
        });
        findViewById(R.id.btn_logs).setOnClickListener(v -> {
            Logger.i("Main", "btn_logs clicked");
            startActivity(new Intent(this, LogsActivity.class));
        });

        // LiveData: the counts re-render automatically on every DB change. No more
        // onResume() + AsyncTask refresh.
        db.receiptDao().getAllLive().observe(this, list -> {
            int n = list == null ? 0 : list.size();
            Logger.d("Main", "receipts LiveData -> " + n);
            tvReceipts.setText(getString(R.string.action_view_receipts, n));
        });
        db.bankTransactionDao().countLive().observe(this, count -> {
            int n = count == null ? 0 : count;
            Logger.d("Main", "transactions LiveData -> " + n);
            tvTransactions.setText(getString(R.string.action_view_transactions, n));
        });
    }
}
