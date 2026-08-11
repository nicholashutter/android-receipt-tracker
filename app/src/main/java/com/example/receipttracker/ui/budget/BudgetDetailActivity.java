package com.example.receipttracker.ui.budget;


import android.app.AlertDialog;

import android.content.Intent;

import android.os.Bundle;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.TextView;

import android.widget.Toast;


import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;

import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.recyclerview.widget.RecyclerView;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.Budget;

import com.example.receipttracker.data.BudgetDao;

import com.example.receipttracker.data.Receipt;

import com.example.receipttracker.data.ReceiptDao;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ui.receipts.EditReceiptActivity;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;

import com.google.android.material.button.MaterialButton;


import java.util.Collections;

import java.util.List;


/**
 * Detail view for a single budget: name, cap, live running spent
 * amount, progress bar, set-active toggle, edit, and a list of
 * linked receipts.
 */
public class BudgetDetailActivity extends AppCompatActivity {

    public static final String EXTRA_BUDGET_ID = "budget_id";

    private static final String TAG = "BudgetDetail";

    private static final int PROGRESS_PERCENT_MAX = 100;
    private static final int INVALID_BUDGET_ID = -1;
    private static final String PLACEHOLDER_NO_MERCHANT = "(no merchant)";
    private static final String STATUS_UNMATCHED = "unmatched";
    private static final String STATUS_MATCHED = "matched";
    private static final String ACTIVE_BUDGET_LABEL = "Active budget";
    private static final String SET_AS_ACTIVE_LABEL = "Set as active budget";


    private long budgetId;

    private BudgetDao budgetDao;
    private ReceiptDao receiptDao;
    private final AppExecutors executors = AppExecutors.get();

    private TextView nameView;
    private TextView activeChipView;
    private TextView spentView;
    private TextView capView;
    private TextView remainingView;
    private TextView noReceiptsView;
    private android.widget.ProgressBar budgetProgressBar;
    private MaterialButton setActiveButton;
    private MaterialButton editButton;
    private MaterialButton deleteButton;
    private RecyclerView receiptsRecyclerView;
    private LinkedReceiptsAdapter receiptsAdapter;


    // MUTABLE: re-set by LiveData observer.
    private Budget currentBudget;

    // MUTABLE: re-set by LiveData observer.
    private double currentSpent = 0.0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("BUDGET DETAIL");

        budgetId = getIntent().getLongExtra(EXTRA_BUDGET_ID, INVALID_BUDGET_ID);
        Logger.i(TAG, "onCreate budgetId=" + budgetId);

        if (budgetId < 0L) {
            finish();
            return;
        }
        setContentView(R.layout.activity_budget_detail);

        budgetDao = AppDatabase.get(this).budgetDao();
        receiptDao = AppDatabase.get(this).receiptDao();

        nameView = findViewById(R.id.tv_name);
        activeChipView = findViewById(R.id.tv_active_chip);
        spentView = findViewById(R.id.tv_spent);
        capView = findViewById(R.id.tv_cap);
        remainingView = findViewById(R.id.tv_remaining);
        noReceiptsView = findViewById(R.id.tv_no_receipts);
        budgetProgressBar = findViewById(R.id.pb_budget);
        setActiveButton = findViewById(R.id.btn_set_active);
        editButton = findViewById(R.id.btn_edit);
        deleteButton = findViewById(R.id.btn_delete);
        receiptsRecyclerView = findViewById(R.id.rv_receipts);

        receiptsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        receiptsAdapter = new LinkedReceiptsAdapter();
        receiptsRecyclerView.setAdapter(receiptsAdapter);

        budgetDao.getByIdLive(budgetId).observe(this, budget -> {
            if (budget == null) {
                finish();
                return;
            }
            currentBudget = budget;
            renderBudget();
        });

        budgetDao.sumSpentLive(budgetId).observe(this, rawSpent -> {
            final double spent;
            if (rawSpent == null) {
                spent = 0.0;
            } else {
                spent = rawSpent;
            }
            currentSpent = spent;
            renderAmounts();
        });

        receiptDao.getByBudgetLive(budgetId).observe(this, receipts -> {
            receiptsAdapter.set(receipts);
            final boolean isListEmpty = receipts == null || receipts.isEmpty();
            if (isListEmpty) {
                noReceiptsView.setVisibility(View.VISIBLE);
            } else {
                noReceiptsView.setVisibility(View.GONE);
            }
            if (isListEmpty) {
                receiptsRecyclerView.setVisibility(View.GONE);
            } else {
                receiptsRecyclerView.setVisibility(View.VISIBLE);
            }
        });

        setActiveButton.setOnClickListener(clickedView -> markCurrentActive());
        editButton.setOnClickListener(clickedView -> showEditDialog());
        deleteButton.setOnClickListener(clickedView -> showDeleteDialog());
    }


    private void markCurrentActive() {
        executors.diskIO().execute(() -> {
            if (currentBudget != null && !currentBudget.isActive) {
                budgetDao.setActive(currentBudget.id);
                Logger.i(TAG, "Set budget id=" + currentBudget.id + " as active");
            }
        });
    }


    private void renderBudget() {
        if (currentBudget == null) return;

        nameView.setText(currentBudget.name);

        if (currentBudget.isActive) {
            activeChipView.setVisibility(View.VISIBLE);
        } else {
            activeChipView.setVisibility(View.GONE);
        }

        final String activeButtonLabel;
        if (currentBudget.isActive) {
            activeButtonLabel = ACTIVE_BUDGET_LABEL;
        } else {
            activeButtonLabel = SET_AS_ACTIVE_LABEL;
        }
        setActiveButton.setText(activeButtonLabel);
        setActiveButton.setEnabled(!currentBudget.isActive);

        renderAmounts();
    }


    private void renderAmounts() {
        if (currentBudget == null) return;

        spentView.setText(MoneyUtils.format(currentSpent));
        capView.setText("of " + MoneyUtils.format(currentBudget.maxAmount));

        final int progressPct;
        if (currentBudget.maxAmount > 0.0) {
            final double rawPercent = currentSpent * 100.0 / currentBudget.maxAmount;
            final double clampedPercent = Math.min(PROGRESS_PERCENT_MAX, Math.round(rawPercent));
            progressPct = (int) clampedPercent;
        } else {
            progressPct = 0;
        }
        budgetProgressBar.setProgress(progressPct);

        final double remaining = currentBudget.maxAmount - currentSpent;
        if (remaining < 0.0) {
            remainingView.setText("Over by " + MoneyUtils.format(-remaining));
            remainingView.setTextColor(getColor(R.color.error));
        } else {
            remainingView.setText(MoneyUtils.format(remaining) + " remaining");
            remainingView.setTextColor(getColor(R.color.on_surface));
        }
    }


    private void showEditDialog() {
        if (currentBudget == null) return;

        final View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_budget, null, false);
        final TextView etName = dialogView.findViewById(R.id.et_name);
        final TextView etMax = dialogView.findViewById(R.id.et_max);
        final android.widget.CheckBox cbActive = dialogView.findViewById(R.id.cb_set_active);

        etName.setText(currentBudget.name);
        etMax.setText(String.valueOf(currentBudget.maxAmount));
        cbActive.setChecked(currentBudget.isActive);

        new AlertDialog.Builder(this)
                .setTitle("Edit budget")
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (dialogInterface, which) -> {
                    final String enteredName = etName.getText().toString().trim();
                    final String enteredMaxText = etMax.getText().toString().trim();
                    final boolean wantActive = cbActive.isChecked();

                    if (enteredName.isEmpty() || enteredMaxText.isEmpty()) {
                        Toast.makeText(this, "Name and max are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final double parsedMax;
                    try {
                        parsedMax = Double.parseDouble(enteredMaxText);
                    } catch (NumberFormatException parseFailure) {
                        Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    persistBudgetEdits(enteredName, parsedMax, wantActive);
                })
                .show();
    }


    private void persistBudgetEdits(String newName, double newMax, boolean wantActive) {
        executors.diskIO().execute(() -> {
            final Budget updated = currentBudget.withName(newName).withMaxAmount(newMax);
            budgetDao.update(updated);
            if (wantActive && !updated.isActive) {
                budgetDao.setActive(updated.id);
            } else if (!wantActive && updated.isActive) {
                budgetDao.clearAllActive();
            }
            Logger.i(TAG, "Edited budget id=" + currentBudget.id);
        });
    }


    private void showDeleteDialog() {
        if (currentBudget == null) return;
        final String message = "'" + currentBudget.name + "' will be removed. Linked receipts will be unlinked but kept.";
        new AlertDialog.Builder(this)
                .setTitle("Delete budget?")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (dialogInterface, which) -> {
                    final long idToDelete = currentBudget.id;
                    executors.diskIO().execute(() -> {
                        receiptDao.clearBudgetOnReceipts(idToDelete);
                        budgetDao.softDelete(idToDelete);
                        Logger.i(TAG, "Soft-deleted budget id=" + idToDelete);
                    });
                    finish();
                })
                .show();
    }


    // ============ adapter for linked receipts ============

    class LinkedReceiptsAdapter extends RecyclerView.Adapter<LinkedReceiptsAdapter.ReceiptViewHolder> {

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
            final View inflatedView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_receipt, parent, false);
            return new ReceiptViewHolder(inflatedView);
        }


        @Override
        public void onBindViewHolder(@NonNull ReceiptViewHolder holder, int position) {
            final Receipt receipt = data.get(position);

            final String merchantText;
            if (receipt.merchant == null || receipt.merchant.isEmpty()) {
                merchantText = PLACEHOLDER_NO_MERCHANT;
            } else {
                merchantText = receipt.merchant;
            }
            holder.merchant.setText(merchantText);
            holder.date.setText(MoneyUtils.formatDate(receipt.dateMillis));
            holder.amount.setText(MoneyUtils.format(receipt.amount));

            final String statusText;
            if (receipt.matchGroupId == null) {
                statusText = STATUS_UNMATCHED;
            } else {
                statusText = STATUS_MATCHED;
            }
            holder.status.setText(statusText);
            holder.status.setBackgroundResource(R.drawable.bg_chip_money);
            holder.status.setTextColor(getColor(R.color.on_warning_container));

            holder.itemView.setOnClickListener(clickedView -> {
                final Intent editIntent = new Intent(BudgetDetailActivity.this, EditReceiptActivity.class);
                editIntent.putExtra(EditReceiptActivity.EXTRA_RECEIPT_ID, receipt.id);
                startActivity(editIntent);
            });
        }


        @Override
        public int getItemCount() {
            return data.size();
        }


        class ReceiptViewHolder extends RecyclerView.ViewHolder {
            final TextView merchant;
            final TextView date;
            final TextView status;
            final TextView amount;

            ReceiptViewHolder(View itemView) {
                super(itemView);
                merchant = itemView.findViewById(R.id.tv_merchant);
                date = itemView.findViewById(R.id.tv_date);
                status = itemView.findViewById(R.id.tv_status);
                amount = itemView.findViewById(R.id.tv_amount);
            }
        }
    }
}
