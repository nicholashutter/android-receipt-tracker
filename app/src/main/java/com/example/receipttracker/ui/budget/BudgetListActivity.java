package com.example.receipttracker.ui.budget;


import android.app.AlertDialog;

import android.content.Intent;

import android.os.Bundle;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.CheckBox;

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

import com.example.receipttracker.data.ReceiptDao;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import com.google.android.material.textfield.TextInputEditText;


import java.util.Collections;

import java.util.HashMap;

import java.util.List;

import java.util.Map;


/**
 * Lists all user budgets and lets the user create, open, set-active,
 * or delete them. Tapping a card opens {@link BudgetDetailActivity}.
 */
public class BudgetListActivity extends AppCompatActivity {

    private static final String TAG = "BudgetList";

    private static final int PROGRESS_PERCENT_MAX = 100;
    private static final int LONG_PRESS_OPTION_TOGGLE_ACTIVE = 0;
    private static final int LONG_PRESS_OPTION_EDIT = 1;
    private static final int LONG_PRESS_OPTION_DELETE = 2;


    private RecyclerView recyclerView;
    private View emptyView;
    private BudgetAdapter adapter;
    private BudgetDao budgetDao;
    private ReceiptDao receiptDao;
    private final AppExecutors executors = AppExecutors.get();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("BUDGET LIST");
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_budget_list);

        recyclerView = findViewById(R.id.rv);
        emptyView = findViewById(R.id.tv_empty);
        final ExtendedFloatingActionButton fab = findViewById(R.id.fab_new_budget);

        budgetDao = AppDatabase.get(this).budgetDao();
        receiptDao = AppDatabase.get(this).receiptDao();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BudgetAdapter();
        recyclerView.setAdapter(adapter);

        budgetDao.getAllActiveLive().observe(this, this::render);
        // Observe receipt changes so spent totals stay in sync.
        receiptDao.getAllActiveLive().observe(this, list -> adapter.notifyAllBudgetsChanged());

        fab.setOnClickListener(clickedView -> showCreateDialog(null));
    }


    private void render(List<Budget> budgets) {
        final int budgetCount;
        if (budgets == null) {
            budgetCount = 0;
        } else {
            budgetCount = budgets.size();
        }
        Logger.i(TAG, "render: " + budgetCount + " budgets");

        adapter.set(budgets);

        final boolean isEmpty = budgets == null || budgets.isEmpty();
        if (isEmpty) {
            recyclerView.setVisibility(View.GONE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
        }
        if (isEmpty) {
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }


    private void showCreateDialog(Budget edit) {
        final boolean isEdit = edit != null;

        final View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_budget, null, false);
        final TextInputEditText etName = dialogView.findViewById(R.id.et_name);
        final TextInputEditText etMax = dialogView.findViewById(R.id.et_max);
        final CheckBox cbActive = dialogView.findViewById(R.id.cb_set_active);

        if (isEdit) {
            etName.setText(edit.name);
            etMax.setText(String.valueOf(edit.maxAmount));
            cbActive.setChecked(edit.isActive);
        }

        final String dialogTitle;
        if (isEdit) {
            dialogTitle = "Edit budget";
        } else {
            dialogTitle = "New budget";
        }
        final String positiveButtonLabel;
        if (isEdit) {
            positiveButtonLabel = "Save";
        } else {
            positiveButtonLabel = "Create";
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(positiveButtonLabel, (dialogInterface, which) -> {
                    final String enteredName;
                    if (etName.getText() == null) {
                        enteredName = "";
                    } else {
                        enteredName = etName.getText().toString().trim();
                    }
                    final String enteredMaxText;
                    if (etMax.getText() == null) {
                        enteredMaxText = "";
                    } else {
                        enteredMaxText = etMax.getText().toString().trim();
                    }
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
                    if (parsedMax <= 0.0) {
                        Toast.makeText(this, "Max must be positive", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    final boolean wantActive = cbActive.isChecked();
                    persistBudgetChanges(edit, isEdit, enteredName, parsedMax, wantActive);
                })
                .create();
        dialog.show();
    }


    private void persistBudgetChanges(Budget edit, boolean isEdit,
                                       String name, double max, boolean wantActive) {
        executors.diskIO().execute(() -> {
            if (isEdit) {
                final Budget updated = edit.withName(name).withMaxAmount(max);
                budgetDao.update(updated);
                if (wantActive && !updated.isActive) {
                    budgetDao.setActive(updated.id);
                } else if (!wantActive && updated.isActive) {
                    budgetDao.clearAllActive();
                }
                Logger.i(TAG, "Updated budget id=" + updated.id);
            } else {
                final Budget newBudget = new Budget(name, max);
                final long newId = budgetDao.insert(newBudget);
                if (wantActive) {
                    budgetDao.setActive(newId);
                }
                Logger.i(TAG, "Created budget id=" + newId);
            }
        });
    }


    private void showDeleteDialog(Budget budget) {
        final String message = "'" + budget.name + "' will be removed. Linked receipts will be unlinked but kept.";
        new AlertDialog.Builder(this)
                .setTitle("Delete budget?")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (dialogInterface, which) -> {
                    final long idToDelete = budget.id;
                    executors.diskIO().execute(() -> {
                        receiptDao.clearBudgetOnReceipts(idToDelete);
                        budgetDao.softDelete(idToDelete);
                        Logger.i(TAG, "Soft-deleted budget id=" + idToDelete);
                    });
                })
                .show();
    }


    // ============ adapter ============

    class BudgetAdapter extends RecyclerView.Adapter<BudgetAdapter.BudgetViewHolder> {

        // MUTABLE: re-set in set().
        private List<Budget> data = Collections.emptyList();

        // We can't bind per-row spent in onBindViewHolder because the
        // LiveData is observed at the activity level, not per row. We
        // recompute spent by querying sumSpent on the disk executor
        // every time receipts change and post results back through a
        // simple map.
        private final Map<Long, Double> spentByBudget = new HashMap<>();


        void set(List<Budget> newData) {
            if (newData == null) {
                this.data = Collections.emptyList();
            } else {
                this.data = newData;
            }
            notifyDataSetChanged();
            refreshSpent();
        }


        void notifyAllBudgetsChanged() {
            refreshSpent();
        }


        private void refreshSpent() {
            executors.diskIO().execute(() -> {
                final Map<Long, Double> next = new HashMap<>();
                for (final Budget budget : data) {
                    next.put(budget.id, budgetDao.sumSpent(budget.id));
                }
                runOnUiThread(() -> {
                    spentByBudget.clear();
                    spentByBudget.putAll(next);
                    notifyDataSetChanged();
                });
            });
        }


        @NonNull
        @Override
        public BudgetViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_budget, parent, false);
            return new BudgetViewHolder(itemView);
        }


        @Override
        public void onBindViewHolder(@NonNull BudgetViewHolder holder, int position) {
            final Budget budget = data.get(position);
            final double spent = spentByBudget.getOrDefault(budget.id, 0.0);

            holder.name.setText(budget.name);
            final String amountsLine = String.format("%s / %s",
                    MoneyUtils.format(spent), MoneyUtils.format(budget.maxAmount));
            holder.amounts.setText(amountsLine);

            final int progressPct;
            if (budget.maxAmount > 0.0) {
                final double rawPercent = spent * 100.0 / budget.maxAmount;
                final double clampedPercent = Math.min(PROGRESS_PERCENT_MAX, Math.round(rawPercent));
                progressPct = (int) clampedPercent;
            } else {
                progressPct = 0;
            }
            holder.progress.setProgress(progressPct);
            holder.status.setText(progressPct + "% used");

            if (budget.isActive) {
                holder.activeChip.setVisibility(View.VISIBLE);
            } else {
                holder.activeChip.setVisibility(View.GONE);
            }

            holder.itemView.setOnClickListener(clickedView -> {
                final Intent detailIntent = new Intent(BudgetListActivity.this, BudgetDetailActivity.class);
                detailIntent.putExtra(BudgetDetailActivity.EXTRA_BUDGET_ID, budget.id);
                startActivity(detailIntent);
            });

            holder.itemView.setOnLongClickListener(clickedView -> {
                final String toggleLabel;
                if (budget.isActive) {
                    toggleLabel = "Deactivate";
                } else {
                    toggleLabel = "Set as active";
                }
                final String[] longPressOptions = {toggleLabel, "Edit", "Delete"};

                new AlertDialog.Builder(BudgetListActivity.this)
                        .setTitle(budget.name)
                        .setItems(longPressOptions, (dialogInterface, which) -> {
                            if (which == LONG_PRESS_OPTION_TOGGLE_ACTIVE) {
                                executors.diskIO().execute(() -> {
                                    if (budget.isActive) {
                                        budgetDao.clearAllActive();
                                    } else {
                                        budgetDao.setActive(budget.id);
                                    }
                                });
                            } else if (which == LONG_PRESS_OPTION_EDIT) {
                                showCreateDialog(budget);
                            } else {
                                showDeleteDialog(budget);
                            }
                        })
                        .show();
                return true;
            });
        }


        @Override
        public int getItemCount() {
            return data.size();
        }


        class BudgetViewHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView subtitle;
            final TextView amounts;
            final TextView status;
            final TextView activeChip;
            final android.widget.ProgressBar progress;

            BudgetViewHolder(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tv_name);
                subtitle = itemView.findViewById(R.id.tv_subtitle);
                amounts = itemView.findViewById(R.id.tv_amounts);
                status = itemView.findViewById(R.id.tv_status);
                activeChip = itemView.findViewById(R.id.tv_active_chip);
                progress = itemView.findViewById(R.id.pb_budget);
                subtitle.setText("Tap to view · long-press for options");
            }
        }
    }
}
