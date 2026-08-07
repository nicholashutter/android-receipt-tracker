package com.example.receipttracker.ui.receipts;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.receipttracker.R;
import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.data.Receipt;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ocr.ReceiptImageStore;
import com.example.receipttracker.util.MoneyUtils;

import java.io.File;
import java.util.List;

public class ReceiptListActivity extends AppCompatActivity {

    private RecyclerView rv;
    private View tvEmpty;
    private ReceiptAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i("ReceiptList", "onCreate");
        setContentView(R.layout.activity_receipt_list);
        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tv_empty);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReceiptAdapter();
        rv.setAdapter(adapter);

        // LiveData: re-renders automatically on every insert/update/delete. No onResume hook.
        AppDatabase.get(this).receiptDao().getAllLive().observe(this, this::render);
    }

    private void render(List<Receipt> data) {
        int n = data == null ? 0 : data.size();
        Logger.i("ReceiptList", "render: " + n + " receipts");
        adapter.set(data);
        boolean empty = data == null || data.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    class ReceiptAdapter extends RecyclerView.Adapter<ReceiptAdapter.VH> {
        private List<Receipt> data = java.util.Collections.emptyList();

        void set(List<Receipt> d) {
            this.data = d == null ? java.util.Collections.emptyList() : d;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_receipt, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Receipt r = data.get(position);
            h.merchant.setText(r.merchant == null ? "(no merchant)" : r.merchant);
            h.date.setText(MoneyUtils.formatDate(r.dateMillis));
            h.amount.setText(MoneyUtils.format(r.amount));
            if (r.matchGroupId != null) {
                h.status.setText(R.string.receipt_match_status_matched);
                h.status.setTextColor(getColor(R.color.ok));
            } else {
                h.status.setText(R.string.receipt_match_status_unmatched);
                h.status.setTextColor(getColor(R.color.warn));
            }
            // Thumbnail - best effort
            if (r.photoPath != null && new File(r.photoPath).exists()) {
                Bitmap bmp = ReceiptImageStore.decodeSampled(r.photoPath, 256, 256);
                if (bmp != null) h.thumb.setImageBitmap(bmp);
                else h.thumb.setImageDrawable(null);
            } else {
                h.thumb.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ReceiptListActivity.this, EditReceiptActivity.class);
                i.putExtra(EditReceiptActivity.EXTRA_RECEIPT_ID, r.id);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView thumb; final TextView merchant, date, status, amount;
            VH(View v) {
                super(v);
                thumb = v.findViewById(R.id.iv_thumb);
                merchant = v.findViewById(R.id.tv_merchant);
                date = v.findViewById(R.id.tv_date);
                status = v.findViewById(R.id.tv_status);
                amount = v.findViewById(R.id.tv_amount);
            }
        }
    }
}
