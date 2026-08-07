package com.example.receipttracker.ui.transactions;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.receipttracker.R;
import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.data.BankTransaction;
import com.example.receipttracker.util.MoneyUtils;

import java.util.List;

public class TransactionListActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView tvEmpty;
    private TxAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_list);
        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tv_empty);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TxAdapter();
        rv.setAdapter(adapter);

        // LiveData: re-renders on every insert/update/delete without an onResume hook.
        AppDatabase.get(this).bankTransactionDao().getAllLive().observe(this, this::render);
    }

    private void render(List<BankTransaction> data) {
        adapter.set(data);
        boolean empty = data == null || data.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    class TxAdapter extends RecyclerView.Adapter<TxAdapter.VH> {
        private List<BankTransaction> data = java.util.Collections.emptyList();
        void set(List<BankTransaction> d) { this.data = d == null ? java.util.Collections.emptyList() : d; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_transaction, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            BankTransaction t = data.get(position);
            h.description.setText(t.description);
            String account = (t.account == null || t.account.isEmpty()) ? "" : " - " + t.account;
            h.dateAccount.setText(MoneyUtils.formatDate(t.dateMillis) + account);
            h.amount.setText(MoneyUtils.format(t.amount));
            if (t.matchGroupId != null) {
                h.status.setText(R.string.receipt_match_status_matched);
                h.status.setTextColor(getColor(R.color.ok));
            } else {
                h.status.setText(R.string.receipt_match_status_unmatched);
                h.status.setTextColor(getColor(R.color.warn));
            }
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(TransactionListActivity.this, AddTransactionActivity.class);
                i.putExtra(AddTransactionActivity.EXTRA_TRANSACTION_ID, t.id);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView description, dateAccount, status, amount;
            VH(View v) {
                super(v);
                description = v.findViewById(R.id.tv_description);
                dateAccount = v.findViewById(R.id.tv_date_account);
                status = v.findViewById(R.id.tv_status);
                amount = v.findViewById(R.id.tv_amount);
            }
        }
    }
}
