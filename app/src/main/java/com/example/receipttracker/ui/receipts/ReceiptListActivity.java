package com.example.receipttracker.ui.receipts;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.receipttracker.R;
import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.data.Receipt;
import com.example.receipttracker.data.ReceiptDao;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ocr.ReceiptImageStore;
import com.example.receipttracker.util.AppExecutors;
import com.example.receipttracker.util.MoneyUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class ReceiptListActivity extends AppCompatActivity {

    private static final String TAG = "ReceiptList";
    private static final int MENU_SHOW_DELETED = 1;
    private static final int MENU_CLEAR_ALL = 2;
    private static final int MENU_RESTORE_ALL = 3;

    private RecyclerView rv;
    private View tvEmpty;
    private ReceiptAdapter adapter;
    private ReceiptDao dao;
    private final AppExecutors exec = AppExecutors.get();
    private boolean showDeleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_receipt_list);
        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tv_empty);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReceiptAdapter();
        rv.setAdapter(adapter);
        dao = AppDatabase.get(this).receiptDao();
        // The user can toggle "show deleted" via the menu. In both modes
        // we re-observe the right query and let the adapter show the right
        // empty state.
        observeCurrent();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        String showDeletedLabel;
        if (showDeleted) {
            showDeletedLabel = "Hide deleted";
        } else {
            showDeletedLabel = "Show deleted";
        }
        menu.add(0, MENU_SHOW_DELETED, 0, showDeletedLabel);
        if (showDeleted) {
            menu.add(0, MENU_RESTORE_ALL, 1, "Restore all");
        } else {
            menu.add(0, MENU_CLEAR_ALL, 1, "Clear all");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == MENU_SHOW_DELETED) {
            showDeleted = !showDeleted;
            invalidateOptionsMenu();
            observeCurrent();
            return true;
        } else if (id == MENU_CLEAR_ALL) {
            confirmClearAll();
            return true;
        } else if (id == MENU_RESTORE_ALL) {
            confirmRestoreAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void observeCurrent() {
        // Always re-observe from a fresh LiveData. Room gives us a new
        // LiveData instance per call, so removeObservers first.
        dao.getAllActiveLive().removeObservers(this);
        dao.getAllLive().removeObservers(this);
        if (showDeleted) {
            dao.getAllLive().observe(this, this::render);
        } else {
            dao.getAllActiveLive().observe(this, this::render);
        }
    }

    private void render(List<Receipt> data) {
        int n;
        if (data == null) {
            n = 0;
        } else {
            n = data.size();
        }
        Logger.i(TAG, "render: " + n + " receipts (showDeleted=" + showDeleted + ")");
        adapter.set(data);
        boolean empty = data == null || data.isEmpty();
        if (empty) {
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
        if (empty) {
            rv.setVisibility(View.GONE);
        } else {
            rv.setVisibility(View.VISIBLE);
        }
    }

    private void confirmClearAll() {
        exec.diskIO().execute(() -> {
            int n = dao.softDeleteAll(System.currentTimeMillis());
            Logger.i(TAG, "softDeleteAll -> " + n);
        });
        Toast.makeText(this, "All receipts cleared from view", Toast.LENGTH_SHORT).show();
    }

    private void confirmRestoreAll() {
        new AlertDialog.Builder(this)
                .setTitle("Restore all deleted receipts?")
                .setMessage("This brings every cleared receipt back into the main list.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Restore", (d, w) -> {
                    exec.diskIO().execute(() -> {
                        int n = dao.restoreAll();
                        Logger.i(TAG, "restoreAll -> " + n);
                    });
                })
                .show();
    }

    // ============ adapter ============

    class ReceiptAdapter extends RecyclerView.Adapter<ReceiptAdapter.VH> {
        private List<Receipt> data = Collections.emptyList();

        void set(List<Receipt> d) {
            if (d == null) {
                this.data = Collections.emptyList();
            } else {
                this.data = d;
            }
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
            String merchantText;
            if (r.merchant == null) {
                merchantText = "(no merchant)";
            } else {
                merchantText = r.merchant;
            }
            h.merchant.setText(merchantText);
            h.date.setText(MoneyUtils.formatDate(r.dateMillis));
            h.amount.setText(MoneyUtils.format(r.amount));
            boolean deleted = r.deletedAt != null;
            if (deleted) {
                h.status.setText("DELETED");
                h.status.setBackgroundResource(R.drawable.bg_chip_warning);
                h.status.setTextColor(getColor(R.color.on_warning_container));
                h.merchant.setAlpha(0.5f);
                h.amount.setAlpha(0.5f);
            } else if (r.matchGroupId != null) {
                h.status.setText(R.string.receipt_match_status_matched);
                h.status.setBackgroundResource(R.drawable.bg_chip_success);
                h.status.setTextColor(getColor(R.color.on_success_container));
                h.merchant.setAlpha(1f);
                h.amount.setAlpha(1f);
            } else {
                h.status.setText(R.string.receipt_match_status_unmatched);
                h.status.setBackgroundResource(R.drawable.bg_chip_primary);
                h.status.setTextColor(getColor(R.color.on_primary_container));
                h.merchant.setAlpha(1f);
                h.amount.setAlpha(1f);
            }
            if (r.photoPath != null && new File(r.photoPath).exists()) {
                Bitmap bmp = ReceiptImageStore.decodeSampled(r.photoPath, 256, 256);
                if (bmp != null) h.thumb.setImageBitmap(bmp);
                else h.thumb.setImageDrawable(null);
            } else {
                h.thumb.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(v -> {
                if (deleted) {
                    // Offer restore instead of opening the editor on a deleted row.
                    String restoreMerchant;
                    if (r.merchant == null) {
                        restoreMerchant = "(no merchant)";
                    } else {
                        restoreMerchant = r.merchant;
                    }
                    new AlertDialog.Builder(ReceiptListActivity.this)
                            .setTitle("Restore receipt?")
                            .setMessage("Bring '" + restoreMerchant
                                    + "' back to the active list?")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Restore", (d, w) -> {
                                exec.diskIO().execute(() -> {
                                    dao.restore(r.id);
                                    Logger.i(TAG, "restored receipt id=" + r.id);
                                });
                            })
                            .setNeutralButton("Delete forever", (d, w) -> {
                                new AlertDialog.Builder(ReceiptListActivity.this)
                                        .setTitle("Delete forever?")
                                        .setMessage("This will permanently remove the receipt and its photo.")
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .setPositiveButton("Delete", (d2, w2) -> {
                                            exec.diskIO().execute(() -> {
                                                if (r.photoPath != null) {
                                                    File f = new File(r.photoPath);
                                                    if (f.exists()) f.delete();
                                                }
                                                dao.delete(r);
                                                Logger.i(TAG, "hard-deleted receipt id=" + r.id);
                                            });
                                        })
                                        .show();
                            })
                            .show();
                } else {
                    Intent i = new Intent(ReceiptListActivity.this, EditReceiptActivity.class);
                    i.putExtra(EditReceiptActivity.EXTRA_RECEIPT_ID, r.id);
                    startActivity(i);
                }
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView merchant, date, status, amount;
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
