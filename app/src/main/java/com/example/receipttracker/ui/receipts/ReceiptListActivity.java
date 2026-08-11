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


/**
 * Lists receipts. Two modes: "active" (default) shows non-deleted
 * receipts newest-first; toggling "Show deleted" in the menu flips
 * to a soft-deleted view with restore / delete-forever options.
 */
public class ReceiptListActivity extends AppCompatActivity {

    private static final String TAG = "ReceiptList";

    private static final int MENU_TOGGLE_SHOW_DELETED = 1;
    private static final int MENU_CLEAR_ALL = 2;
    private static final int MENU_RESTORE_ALL = 3;
    private static final int THUMBNAIL_DIM = 256;

    private static final String LABEL_SHOW_DELETED = "Show deleted";
    private static final String LABEL_HIDE_DELETED = "Hide deleted";
    private static final String LABEL_RESTORE_ALL = "Restore all";
    private static final String LABEL_CLEAR_ALL = "Clear all";
    private static final String STATUS_DELETED = "DELETED";
    private static final String PLACEHOLDER_NO_MERCHANT = "(no merchant)";
    private static final String CLEARED_TOAST = "All receipts cleared from view";


    private RecyclerView recyclerView;
    private View emptyView;
    private ReceiptAdapter adapter;
    private ReceiptDao dao;
    private final AppExecutors executors = AppExecutors.get();

    // MUTABLE: toggled in menu.
    private boolean showDeleted = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_receipt_list);

        recyclerView = findViewById(R.id.rv);
        emptyView = findViewById(R.id.tv_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReceiptAdapter();
        recyclerView.setAdapter(adapter);
        dao = AppDatabase.get(this).receiptDao();

        // The user can toggle "show deleted" via the menu. In both
        // modes we re-observe the right query and let the adapter show
        // the right empty state.
        observeCurrent();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final String toggleLabel;
        if (showDeleted) {
            toggleLabel = LABEL_HIDE_DELETED;
        } else {
            toggleLabel = LABEL_SHOW_DELETED;
        }
        menu.add(0, MENU_TOGGLE_SHOW_DELETED, 0, toggleLabel);
        if (showDeleted) {
            menu.add(0, MENU_RESTORE_ALL, 1, LABEL_RESTORE_ALL);
        } else {
            menu.add(0, MENU_CLEAR_ALL, 1, LABEL_CLEAR_ALL);
        }
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == MENU_TOGGLE_SHOW_DELETED) {
            showDeleted = !showDeleted;
            invalidateOptionsMenu();
            observeCurrent();
            return true;
        }
        if (itemId == MENU_CLEAR_ALL) {
            confirmClearAll();
            return true;
        }
        if (itemId == MENU_RESTORE_ALL) {
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


    private void render(List<Receipt> receipts) {
        final int count;
        if (receipts == null) {
            count = 0;
        } else {
            count = receipts.size();
        }
        Logger.i(TAG, "render: " + count + " receipts (showDeleted=" + showDeleted + ")");
        adapter.set(receipts);
        final boolean isEmpty = receipts == null || receipts.isEmpty();
        if (isEmpty) {
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
        if (isEmpty) {
            recyclerView.setVisibility(View.GONE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
        }
    }


    private void confirmClearAll() {
        executors.diskIO().execute(() -> {
            final int clearedCount = dao.softDeleteAll(System.currentTimeMillis());
            Logger.i(TAG, "softDeleteAll -> " + clearedCount);
        });
        Toast.makeText(this, CLEARED_TOAST, Toast.LENGTH_SHORT).show();
    }


    private void confirmRestoreAll() {
        new AlertDialog.Builder(this)
                .setTitle("Restore all deleted receipts?")
                .setMessage("This brings every cleared receipt back into the main list.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Restore", (dialogInterface, which) -> {
                    executors.diskIO().execute(() -> {
                        final int restoredCount = dao.restoreAll();
                        Logger.i(TAG, "restoreAll -> " + restoredCount);
                    });
                })
                .show();
    }


    // ============ adapter ============

    class ReceiptAdapter extends RecyclerView.Adapter<ReceiptAdapter.ReceiptViewHolder> {

        // MUTABLE: re-set in set().
        private List<Receipt> data = Collections.emptyList();


        void set(List<Receipt> newData) {
            if (newData == null) {
                this.data = Collections.emptyList();
            } else {
                this.data = newData;
            }
            notifyDataSetChanged();
        }


        @NonNull
        @Override
        public ReceiptViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_receipt, parent, false);
            return new ReceiptViewHolder(itemView);
        }


        @Override
        public void onBindViewHolder(@NonNull ReceiptViewHolder holder, int position) {
            final Receipt receipt = data.get(position);

            final String merchantLabel;
            if (receipt.merchant == null) {
                merchantLabel = PLACEHOLDER_NO_MERCHANT;
            } else {
                merchantLabel = receipt.merchant;
            }
            holder.merchant.setText(merchantLabel);
            holder.date.setText(MoneyUtils.formatDate(receipt.dateMillis));
            holder.amount.setText(MoneyUtils.format(receipt.amount));

            final boolean isDeleted = receipt.deletedAt != null;
            if (isDeleted) {
                bindDeletedRow(holder, receipt);
            } else if (receipt.matchGroupId != null) {
                bindMatchedRow(holder, receipt);
            } else {
                bindUnmatchedRow(holder, receipt);
            }

            bindThumbnail(holder, receipt);
            bindRowClick(holder, receipt, isDeleted);
        }


        private void bindDeletedRow(ReceiptViewHolder holder, Receipt receipt) {
            holder.status.setText(STATUS_DELETED);
            holder.status.setBackgroundResource(R.drawable.bg_chip_warning);
            holder.status.setTextColor(getColor(R.color.on_warning_container));
            holder.merchant.setAlpha(0.5f);
            holder.amount.setAlpha(0.5f);
        }


        private void bindMatchedRow(ReceiptViewHolder holder, Receipt receipt) {
            holder.status.setText(R.string.receipt_match_status_matched);
            holder.status.setBackgroundResource(R.drawable.bg_chip_success);
            holder.status.setTextColor(getColor(R.color.on_success_container));
            holder.merchant.setAlpha(1f);
            holder.amount.setAlpha(1f);
        }


        private void bindUnmatchedRow(ReceiptViewHolder holder, Receipt receipt) {
            holder.status.setText(R.string.receipt_match_status_unmatched);
            holder.status.setBackgroundResource(R.drawable.bg_chip_primary);
            holder.status.setTextColor(getColor(R.color.on_primary_container));
            holder.merchant.setAlpha(1f);
            holder.amount.setAlpha(1f);
        }


        private void bindThumbnail(ReceiptViewHolder holder, Receipt receipt) {
            if (receipt.photoPath != null && new File(receipt.photoPath).exists()) {
                final Bitmap thumbnail = ReceiptImageStore.decodeSampled(
                        receipt.photoPath, THUMBNAIL_DIM, THUMBNAIL_DIM);
                if (thumbnail != null) {
                    holder.thumb.setImageBitmap(thumbnail);
                } else {
                    holder.thumb.setImageDrawable(null);
                }
            } else {
                holder.thumb.setImageDrawable(null);
            }
        }


        private void bindRowClick(ReceiptViewHolder holder, Receipt receipt, boolean isDeleted) {
            holder.itemView.setOnClickListener(clickedView -> {
                if (isDeleted) {
                    showDeletedReceiptDialog(receipt);
                } else {
                    final Intent editIntent = new Intent(ReceiptListActivity.this, EditReceiptActivity.class);
                    editIntent.putExtra(EditReceiptActivity.EXTRA_RECEIPT_ID, receipt.id);
                    startActivity(editIntent);
                }
            });
        }


        private void showDeletedReceiptDialog(Receipt receipt) {
            // Offer restore instead of opening the editor on a deleted row.
            final String merchantLabel;
            if (receipt.merchant == null) {
                merchantLabel = PLACEHOLDER_NO_MERCHANT;
            } else {
                merchantLabel = receipt.merchant;
            }
            new AlertDialog.Builder(ReceiptListActivity.this)
                    .setTitle("Restore receipt?")
                    .setMessage("Bring '" + merchantLabel + "' back to the active list?")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Restore", (dialogInterface, which) -> {
                        final long idToRestore = receipt.id;
                        executors.diskIO().execute(() -> {
                            dao.restore(idToRestore);
                            Logger.i(TAG, "restored receipt id=" + idToRestore);
                        });
                    })
                    .setNeutralButton("Delete forever", (dialogInterface, which) -> {
                        final long idToHardDelete = receipt.id;
                        final String photoPath = receipt.photoPath;
                        new AlertDialog.Builder(ReceiptListActivity.this)
                                .setTitle("Delete forever?")
                                .setMessage("This will permanently remove the receipt and its photo.")
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton("Delete", (dialog2, which2) -> {
                                    executors.diskIO().execute(() -> {
                                        if (photoPath != null) {
                                            final File photoFile = new File(photoPath);
                                            if (photoFile.exists()) {
                                                final boolean deleted = photoFile.delete();
                                                if (!deleted) {
                                                    Logger.w(TAG, "Failed to delete photo: " + photoPath);
                                                }
                                            }
                                        }
                                        dao.delete(receipt);
                                        Logger.i(TAG, "hard-deleted receipt id=" + idToHardDelete);
                                    });
                                })
                                .show();
                    })
                    .show();
        }


        @Override
        public int getItemCount() {
            return data.size();
        }


        class ReceiptViewHolder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView merchant;
            final TextView date;
            final TextView status;
            final TextView amount;

            ReceiptViewHolder(View itemView) {
                super(itemView);
                thumb = itemView.findViewById(R.id.iv_thumb);
                merchant = itemView.findViewById(R.id.tv_merchant);
                date = itemView.findViewById(R.id.tv_date);
                status = itemView.findViewById(R.id.tv_status);
                amount = itemView.findViewById(R.id.tv_amount);
            }
        }
    }
}
