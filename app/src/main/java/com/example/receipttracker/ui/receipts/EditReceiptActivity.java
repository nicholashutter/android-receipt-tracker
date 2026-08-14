package com.example.receipttracker.ui.receipts;


import android.app.AlertDialog;

import android.app.DatePickerDialog;

import android.content.Intent;

import android.graphics.Bitmap;

import android.os.Bundle;

import android.view.View;

import android.widget.ImageView;

import android.widget.TextView;

import android.widget.Toast;


import androidx.appcompat.app.AppCompatActivity;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.BankTransaction;

import com.example.receipttracker.data.Budget;

import com.example.receipttracker.data.BudgetDao;

import com.example.receipttracker.data.Receipt;

import com.example.receipttracker.data.ReceiptDao;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ocr.DetectedNumber;

import com.example.receipttracker.ocr.MerchantClassifier;

import com.example.receipttracker.ocr.NumberCategory;

import com.example.receipttracker.ocr.ReceiptImageStore;

import com.example.receipttracker.ocr.ReceiptParser;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;

import com.google.android.material.button.MaterialButton;

import com.google.android.material.textfield.TextInputEditText;


import java.io.File;

import java.util.ArrayList;

import java.util.Calendar;

import java.util.List;

import java.util.Locale;

import java.util.TimeZone;


public class EditReceiptActivity extends AppCompatActivity {

    public static final String EXTRA_RECEIPT_ID = "receipt_id";

    public static final String EXTRA_PHOTO_PATH = "photo_path";

    public static final String EXTRA_RAW_TEXT = "raw_text";

    public static final String EXTRA_MERCHANT = "merchant";

    public static final String EXTRA_AMOUNT = "amount";

    public static final String EXTRA_DATE_MILLIS = "date_millis";


    private ImageView ivThumb;

    private TextInputEditText etMerchant;

    private TextInputEditText etDate;

    private TextInputEditText etAmount;

    private TextInputEditText etNotes;

    private TextView tvRawText;

    private TextView tvBudgetName;

    private View sectionBudget;

    private MaterialButton btnSave;

    private MaterialButton btnDelete;

    private MaterialButton btnAddToBudget;

    private MaterialButton btnRePickTotal;

    private MaterialButton btnChangeBudget;

    private MaterialButton btnRemoveBudget;


    private long existingId = -1;

    private String photoPath;

    private String rawText;

    private long dateMillis = System.currentTimeMillis();

    private boolean showingRaw = false;


    // Set when the user picks a budget in the "Add to budget" dialog; consumed
    // by saveReceiptInternal to attach the new receipt to that budget.
    private Long pendingBudgetId = null;


    private final AppExecutors exec = AppExecutors.get();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Logger.section("EDIT RECEIPT");

        Logger.i("Edit", "onCreate existingId=" + existingId);

        setContentView(R.layout.activity_edit_receipt);

        bindViews();

        extractIntentExtras();

        prefillFromIntent();

        renderPhoto();

        renderDate();

        attachListeners();

        if (existingId >= 0) {
            loadExistingReceipt(existingId);
        } else {
            // New (unsaved) receipts are discarded by the back button;
            // hide the destructive action so the user doesn't think a
            // delete is meaningful here.
            btnDelete.setVisibility(View.GONE);
        }
    }


    private void bindViews() {
        ivThumb = findViewById(R.id.iv_thumb);

        etMerchant = findViewById(R.id.et_merchant);

        etDate = findViewById(R.id.et_date);

        etAmount = findViewById(R.id.et_amount);

        etNotes = findViewById(R.id.et_notes);

        tvRawText = findViewById(R.id.tv_raw_text);

        tvBudgetName = findViewById(R.id.tv_budget_name);

        sectionBudget = findViewById(R.id.section_budget);

        btnSave = findViewById(R.id.btn_save);

        btnDelete = findViewById(R.id.btn_delete);

        btnAddToBudget = findViewById(R.id.btn_add_to_budget);

        btnRePickTotal = findViewById(R.id.btn_repick_total);

        btnChangeBudget = findViewById(R.id.btn_change_budget);

        btnRemoveBudget = findViewById(R.id.btn_remove_budget);
    }


    private void extractIntentExtras() {
        final Intent intent = getIntent();

        if (intent.hasExtra(EXTRA_RECEIPT_ID)) {
            existingId = intent.getLongExtra(EXTRA_RECEIPT_ID, -1);
        }

        photoPath = intent.getStringExtra(EXTRA_PHOTO_PATH);

        rawText = intent.getStringExtra(EXTRA_RAW_TEXT);
    }


    /**
     * New-from-scan path: prefill the form with parsed values from
     * the intent. The merchant string is run through the JSON
     * classifier — high-confidence matches are replaced with the
     * canonical form (e.g. "WHOLE FOODS" -> "Whole Foods Market");
     * low-confidence predictions leave the OCR string as-is.
     */
    private void prefillFromIntent() {
        if (existingId >= 0) return;

        final Intent intent = getIntent();

        final String merchant = intent.getStringExtra(EXTRA_MERCHANT);

        final double amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0);

        final long date = intent.getLongExtra(EXTRA_DATE_MILLIS, 0);

        if (merchant != null) {
            final MerchantClassifier.Prediction prediction = MerchantClassifier.predict(merchant);

            final String refined;
            if (prediction != null && prediction.confidence >= 0.40) {
                refined = prediction.name;
            } else {
                refined = merchant;
            }

            etMerchant.setText(refined);

            if (prediction != null) {
                Logger.i("Edit", "merchant refined: '" + merchant
                        + "' -> '" + refined + "' (conf=" + String.format("%.2f", prediction.confidence) + ")");
            }
        }

        if (amount > 0) etAmount.setText(String.valueOf(amount));

        if (date > 0) dateMillis = date;
    }


    private void attachListeners() {
        etDate.setOnClickListener(v -> showDatePicker());

        tvRawText.setOnClickListener(v -> {
            showingRaw = !showingRaw;

            if (showingRaw) {
                tvRawText.setVisibility(View.VISIBLE);
            } else {
                tvRawText.setVisibility(View.GONE);
            }
        });

        if (rawText != null && !rawText.isEmpty()) {
            tvRawText.setText(rawText);

            // Show the Re-pick button when OCR text is on hand so the
            // user can override the auto-pick without re-typing.
            btnRePickTotal.setVisibility(View.VISIBLE);
        }


        // Auto-pick the most likely receipt total from the OCR text. The
        // verifier ensemble is gone for now (Plaid isn't wired up yet) —
        // we surface a WARN log line if no candidate was found so the
        // support team can see it, but the user sees an empty amount
        // field and can type the total.
        if (existingId < 0 && rawText != null && !rawText.isEmpty()) {
            autoPickTotal();
        }


        btnSave.setOnClickListener(v -> {
            Logger.i("Edit", "btn_save clicked: merchant='" + etMerchant.getText()
                    + "', amount='" + etAmount.getText() + "'");

            saveReceipt();
        });

        btnDelete.setOnClickListener(v -> {
            Logger.i("Edit", "btn_delete clicked: existingId=" + existingId);

            deleteReceipt();
        });

        btnAddToBudget.setOnClickListener(v -> showAddToBudgetDialog());

        btnChangeBudget.setOnClickListener(v -> showAddToBudgetDialog());

        btnRemoveBudget.setOnClickListener(v -> onRemoveBudgetClicked());

        btnRePickTotal.setOnClickListener(v -> showRePickDialog());
    }


    private void loadExistingReceipt(final long id) {
        // Existing receipts: delete is meaningful (cascades to the
        // paired bank transaction and removes the photo file).
        btnDelete.setVisibility(View.VISIBLE);

        // The legacy "Add to budget" button is replaced by the
        // Budget section (which has its own Change/Remove buttons) for
        // existing receipts. Hide it here so the two paths don't both
        // show. For new receipts, btnAddToBudget stays gone by default.
        btnAddToBudget.setVisibility(View.GONE);

        // Reveal the budget section for existing receipts even before
        // the DB load completes (so a quick tap of the Change button
        // works without a race). The async DB load below will replace
        // the placeholder with the actual budget name.
        sectionBudget.setVisibility(View.VISIBLE);
        showBudget(null);

        // Load existing receipt from DB on the disk executor.
        exec.diskIO().execute(() -> {
            final Receipt receipt = AppDatabase.get(EditReceiptActivity.this).receiptDao().getById(id);

            exec.mainThread().execute(() -> {
                if (receipt == null) {
                    finish();
                    return;
                }

                bindExistingReceipt(receipt);

                if (receipt.budgetId != null) {
                    loadAndShowBudget(receipt.budgetId);
                }
            });
        });
    }


    private void bindExistingReceipt(Receipt receipt) {
        final String merchantText;
        if (receipt.merchant == null) {
            merchantText = "";
        } else {
            merchantText = receipt.merchant;
        }

        etMerchant.setText(merchantText);

        final String amountText;
        if (receipt.amount > 0) {
            amountText = String.valueOf(receipt.amount);
        } else {
            amountText = "";
        }

        etAmount.setText(amountText);

        final String notesText;
        if (receipt.notes == null) {
            notesText = "";
        } else {
            notesText = receipt.notes;
        }

        etNotes.setText(notesText);

        dateMillis = receipt.dateMillis;

        photoPath = receipt.photoPath;

        rawText = receipt.rawText;

        renderPhoto();

        renderDate();

        if (rawText != null) tvRawText.setText(rawText);
    }


    private void renderPhoto() {
        if (photoPath == null) {
            ivThumb.setImageDrawable(null);

            return;
        }

        final Bitmap bitmap = ReceiptImageStore.decodeSampled(photoPath, 1200, 1200);

        if (bitmap != null) ivThumb.setImageBitmap(bitmap);
    }


    private void renderDate() {
        etDate.setText(MoneyUtils.formatDate(dateMillis));
    }


    private void showDatePicker() {
        final Calendar calendar = Calendar.getInstance(TimeZone.getDefault());

        calendar.setTimeInMillis(dateMillis);

        final int year = calendar.get(Calendar.YEAR);

        final int month = calendar.get(Calendar.MONTH);

        final int day = calendar.get(Calendar.DAY_OF_MONTH);

        new DatePickerDialog(this,
                (view, pickedYear, pickedMonth, pickedDay) -> {
                    final Calendar next = Calendar.getInstance(TimeZone.getDefault());

                    next.clear();

                    next.set(pickedYear, pickedMonth, pickedDay);

                    dateMillis = next.getTimeInMillis();

                    renderDate();
                },
                year, month, day)
                .show();
    }


    private boolean validate() {
        boolean ok = true;

        if (etMerchant.getText() == null || etMerchant.getText().toString().trim().isEmpty()) {
            etMerchant.setError(getString(R.string.error_required));

            ok = false;
        }

        final double amount = parseAmount();

        if (amount <= 0) {
            etAmount.setError(getString(R.string.error_invalid_amount));

            ok = false;
        }

        return ok;
    }


    private double parseAmount() {
        if (etAmount.getText() == null) return 0;

        final String cleaned = etAmount.getText().toString().replace("$", "").replace(",", "").trim();

        if (cleaned.isEmpty()) return 0;

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }


    private void deleteReceipt() {
        if (existingId < 0) {
            finish();
            return;
        }

        final long id = existingId;

        Logger.i("Edit", "deleteReceipt: id=" + id);

        exec.diskIO().execute(() -> {
            final AppDatabase db = AppDatabase.get(EditReceiptActivity.this);

            final Receipt receipt = db.receiptDao().getById(id);

            if (receipt != null) {
                // If this receipt was matched, also unmatch the bank transaction side
                // so it shows up in "unmatched" again and the user can re-link it.
                if (receipt.matchGroupId != null) {
                    for (BankTransaction tx : db.bankTransactionDao().getAll()) {
                        if (receipt.matchGroupId.equals(tx.matchGroupId)) {
                            db.bankTransactionDao().clearMatchGroup(tx.id);

                            Logger.i("Edit", "Cascade-unmatched tx id=" + tx.id
                                    + " (was paired with deleted receipt)");

                            break;
                        }
                    }
                }

                db.receiptDao().delete(receipt);

                if (receipt.photoPath != null) {
                    final boolean deleted = new File(receipt.photoPath).delete();

                    Logger.i("Edit", "Deleted photo " + receipt.photoPath + " ok=" + deleted);
                }
            }

            exec.mainThread().execute(() -> {
                Toast.makeText(EditReceiptActivity.this, R.string.deleted, Toast.LENGTH_SHORT).show();

                finish();
            });
        });
    }


    // ---------- auto-pick total ----------

    /**
     * Best-effort auto-pick of the receipt total. Runs in the background
     * so we don't block the activity open. Categorisation filters out
     * tax percentages, dates, phone numbers, auth codes, and the rest
     * before {@link ReceiptParser#pickCircledCandidate} runs the
     * keyword/largest-decimal fallback. The user sees the result
     * pre-filled in the amount field; they can override it manually.
     */
    private void autoPickTotal() {
        if (rawText == null || rawText.isEmpty()) return;

        Logger.section("AUTO-PICK TOTAL");

        exec.diskIO().execute(() -> {
            final List<DetectedNumber> numbers = ReceiptParser.extractAllNumbers(rawText);

            if (numbers.isEmpty()) {
                Logger.w("Edit", "autoPick: parser found 0 numbers; nothing to auto-pick");

                return;
            }

            final DetectedNumber picked = ReceiptParser.pickCircledCandidate(numbers);

            if (picked == null) {
                Logger.w("Edit", "autoPick: pickCircledCandidate returned null");

                return;
            }

            final StringBuilder categorySummary = new StringBuilder();

            for (DetectedNumber number : numbers) {
                final NumberCategory category = number.classify();

                if (categorySummary.length() > 0) categorySummary.append(", ");

                categorySummary.append(String.format(Locale.US, "$%.2f=%s", number.value, category.name()));
            }

            Logger.i("Edit", "autoPick: candidates -> " + categorySummary);

            Logger.i("Edit", "autoPick: chose $" + picked.value
                    + " from line " + picked.lineIndex
                    + " (keyword=" + picked.keyword
                    + ", category=" + picked.classify().name() + ")");

            runOnUiThread(() -> {
                if (etAmount.getText() == null || etAmount.getText().toString().trim().isEmpty()) {
                    etAmount.setText(String.format(Locale.US, "%.2f", picked.value));
                }
            });
        });
    }


    // ---------- budget display ----------

    /**
     * Refreshes the budget section for a loaded existing receipt. The
     * budget is loaded async because the activity-load path is already
     * on the disk executor; we don't want to block on a second DB hit.
     * The new-receipt path never shows the budget section — there's no
     * row to link to until the user saves.
     */
    private void loadAndShowBudget(long budgetId) {
        if (budgetId <= 0) {
            showBudget(null);
            return;
        }

        exec.diskIO().execute(() -> {
            final Budget budget = AppDatabase.get(EditReceiptActivity.this)
                    .budgetDao().getById(budgetId);
            runOnUiThread(() -> showBudget(budget));
        });
    }


    /**
     * Renders the budget section for a (possibly null) budget. Hides the
     * "Change"/"Remove" pair cleanly when no budget is set, so the user
     * sees the empty state without an awkward Remove button they can't
     * press.
     */
    private void showBudget(Budget budget) {
        if (budget == null) {
            tvBudgetName.setText("(no budget — tap Change to add one)");

            btnChangeBudget.setText("Add to budget");
            btnRemoveBudget.setVisibility(View.GONE);
        } else {
            tvBudgetName.setText(budget.name);

            btnChangeBudget.setText("Change budget");
            btnRemoveBudget.setVisibility(View.VISIBLE);
        }
    }


    /**
     * User pressed the "Remove" link on the budget section. We update
     * the in-memory {@code pendingBudgetId} to a sentinel and resave,
     * so the next save persists budgetId=null. Skipping save entirely
     * and writing through here would also work but means a second
     * DB write per gesture; piggy-backing on the next save keeps it to one.
     */
    private void onRemoveBudgetClicked() {
        Logger.i("Edit", "onRemoveBudgetClicked: marking pending budget as null");
        pendingBudgetId = null;
        saveReceipt();
    }


    // ---------- save ----------

    private void saveReceipt() {
        if (!validate()) return;

        saveReceiptInternal();
    }


    /**
     * DTO row for {@link #showAddToBudgetDialog()}. A row is either a
     * pickable leaf (sub-budget, or parent with no children) or a
     * non-pickable parent header row that just announces the sub-budgets
     * coming below.
     */
    private static final class PickerRow {
        final String label;
        final long budgetId;
        final boolean isLeaf;

        PickerRow(String label, long budgetId, boolean isLeaf) {
            this.label = label;
            this.budgetId = budgetId;
            this.isLeaf = isLeaf;
        }
    }


    /**
     * Shows a picker of every number the OCR detected on the receipt,
     * sorted by category priority (TOTAL → SUBTOTAL → LINE_ITEM →
     * other) and value descending. The top of the list is the auto-pick
     * recommendation; the bottom is "everything else the OCR saw".
     * Numbers the classifier filtered out (TAX, DATE, PERCENTAGE, etc.)
     * are shown with a small "(excluded)" tag so the user can see what
     * was skipped and why.
     *
     * <p>Tapping a row sets the amount field to that value. No
     * verification, no verdict panel — the user is explicitly picking
     * the number they want, so we just honor the choice.</p>
     */
    private void showRePickDialog() {
        if (rawText == null || rawText.isEmpty()) {
            Toast.makeText(this, "No OCR text on this receipt", Toast.LENGTH_SHORT).show();

            return;
        }

        final List<DetectedNumber> numbers = ReceiptParser.extractAllNumbers(rawText);

        if (numbers.isEmpty()) {
            Toast.makeText(this, "Parser found no numbers", Toast.LENGTH_SHORT).show();

            return;
        }

        // Sort by category priority (lowest number = highest priority),
        // then value descending. The comparator intentionally keeps
        // "excluded" categories after the candidates so the user sees
        // the sensible picks first.
        final List<DetectedNumber> sorted = new ArrayList<>(numbers);

        sorted.sort((a, b) -> {
            final int priorityA = rePickCategoryPriority(a.classify());
            final int priorityB = rePickCategoryPriority(b.classify());

            if (priorityA != priorityB) return Integer.compare(priorityA, priorityB);

            return Double.compare(b.value, a.value);
        });

        final String[] labels = new String[sorted.size()];
        final double[] values = new double[sorted.size()];

        for (int index = 0; index < sorted.size(); index++) {
            final DetectedNumber number = sorted.get(index);

            values[index] = number.value;

            final String keywordSuffix;
            if (number.keyword == null) {
                keywordSuffix = "";
            } else {
                keywordSuffix = "  •  " + number.keyword.toUpperCase();
            }

            final NumberCategory category = number.classify();

            final String excludedTag;
            if (isExcludedCategory(category)) {
                excludedTag = "  (excluded: " + category.name().toLowerCase() + ")";
            } else {
                excludedTag = "";
            }

            labels[index] = String.format(Locale.US, "$%.2f%s  [%s]  •  line %d%s",
                    number.value, keywordSuffix, category.name(), number.lineIndex, excludedTag);
        }

        Logger.i("Edit", "showRePickDialog: " + labels.length + " numbers (raw size=" + numbers.size() + ")");

        new AlertDialog.Builder(this)
                .setTitle(R.string.action_pick_total)
                .setItems(labels, (dialog, which) -> {
                    final double picked = values[which];

                    Logger.i("Edit", "Re-pick: chose $" + picked);

                    etAmount.setText(String.format(Locale.US, "%.2f", picked));

                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }


    /**
     * Lower priority number = more likely to be the receipt total.
     * Categories the auto-pick already picks from get 0..2; "excluded"
     * categories get a higher priority so they sink to the bottom of
     * the picker.
     */
    private static int rePickCategoryPriority(NumberCategory category) {
        if (category == NumberCategory.TOTAL) return 0;
        if (category == NumberCategory.SUBTOTAL) return 1;
        if (category == NumberCategory.LINE_ITEM) return 2;
        return 3;
    }


    private static boolean isExcludedCategory(NumberCategory category) {
        return category != NumberCategory.TOTAL
                && category != NumberCategory.SUBTOTAL
                && category != NumberCategory.LINE_ITEM;
    }


    /**
     * Shows a picker of all active budgets so the user can attach this
     * receipt to one. Used for existing receipts via the "Add to budget"
     * button (the button only appears for existing receipts — see
     * {@link #loadExistingReceipt}).
     *
     * <p>Hierarchy-aware: parent budgets with no sub-budgets are the
     * valid "leaf" pick. Parent budgets WITH sub-budgets roll up
     * their children's spend, so the receipt must attach to a child
     * — tapping a parent row in that case opens an info dialog
     * explaining the hierarchy.</p>
     */
    private void showAddToBudgetDialog() {
        exec.diskIO().execute(() -> {
            final BudgetDao dao = AppDatabase.get(EditReceiptActivity.this).budgetDao();

            final List<Budget> parents = dao.getAllParents();

            // For each parent, fetch children. Build a flat list of
            // (label, targetBudgetId, isLeaf) tuples.
            final List<PickerRow> rows = new ArrayList<>();

            final List<Budget> noChildParents = new ArrayList<>();

            for (Budget parent : parents) {
                final List<Budget> children = dao.getChildren(parent.id);

                if (children == null || children.isEmpty()) {
                    noChildParents.add(parent);
                } else {
                    // Parent row (non-pickable, info only).
                    rows.add(new PickerRow(parent.name + "  (parent — pick a sub-budget below)",
                            parent.id, false));

                    for (Budget child : children) {
                        rows.add(new PickerRow("    • " + child.name + " — " + MoneyUtils.format(child.maxAmount) + " cap",
                                child.id, true));
                    }
                }
            }

            for (Budget parent : noChildParents) {
                rows.add(new PickerRow(parent.name + " — " + MoneyUtils.format(parent.maxAmount) + " cap",
                        parent.id, true));
            }

            runOnUiThread(() -> {
                if (rows.isEmpty()) {
                    Toast.makeText(this, "No budgets available. Create one first.", Toast.LENGTH_SHORT).show();

                    return;
                }

                final String[] labels = new String[rows.size()];

                for (int index = 0; index < rows.size(); index++) {
                    labels[index] = rows.get(index).label;
                }

                new AlertDialog.Builder(this)
                        .setTitle("Add to budget")
                        .setItems(labels, (dialog, which) -> {
                            final PickerRow picked = rows.get(which);

                            if (!picked.isLeaf) {
                                Toast.makeText(this,
                                        "Pick a sub-budget of '" + picked.label.split("  ")[0] + "' to add this receipt to.",
                                        Toast.LENGTH_LONG).show();

                                return;
                            }

                            pendingBudgetId = picked.budgetId;

                            Logger.i("Edit", "Add to budget: chose id=" + pendingBudgetId);

                            saveReceiptInternal();

                            dialog.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .show();
            });
        });
    }


    private void saveReceiptInternal() {
        final long resolvedId;
        if (existingId >= 0) {
            resolvedId = existingId;
        } else {
            resolvedId = 0L;
        }

        final String notesText;
        if (etNotes.getText() == null) {
            notesText = null;
        } else {
            notesText = etNotes.getText().toString().trim();
        }

        final Receipt draft = new Receipt(
                resolvedId,
                etMerchant.getText().toString().trim(),
                dateMillis,
                parseAmount(),
                photoPath,
                rawText,
                notesText,
                System.currentTimeMillis(),
                null,
                null,
                null);

        // Link to the budget the user picked in the prompt. Only set on insert;
        // updates preserve the existing budgetId.
        final Long budgetIdToSet = pendingBudgetId;

        final String photoPathLog;
        if (draft.photoPath == null) {
            photoPathLog = "null";
        } else {
            photoPathLog = draft.photoPath;
        }

        Logger.i("Edit", "saveReceiptInternal: id=" + draft.id + " merchant='" + draft.merchant
                + "' amount=" + draft.amount + " dateMillis=" + draft.dateMillis
                + " photoPath=" + photoPathLog
                + " budgetId=" + budgetIdToSet);


        exec.diskIO().execute(() -> {
            final AppDatabase db = AppDatabase.get(EditReceiptActivity.this);

            final ReceiptDao dao = db.receiptDao();

            if (draft.id > 0) {
                final Receipt existing = dao.getById(draft.id);

                final Receipt toUpdate;
                if (existing != null) {
                    final Long effectiveBudgetId;
                    if (budgetIdToSet != null) {
                        effectiveBudgetId = budgetIdToSet;
                    } else {
                        effectiveBudgetId = existing.budgetId;
                    }

                    toUpdate = draft
                            .withMatchGroupId(existing.matchGroupId)
                            .withCreatedAt(existing.createdAt)
                            .withBudgetId(effectiveBudgetId);
                } else {
                    toUpdate = draft;
                }

                dao.update(toUpdate);

                Logger.i("Edit", "Updated receipt id=" + toUpdate.id);
            } else {
                final Receipt toInsert;
                if (budgetIdToSet != null) {
                    toInsert = draft.withBudgetId(budgetIdToSet);
                } else {
                    toInsert = draft;
                }

                final long rowId = dao.insert(toInsert);

                Logger.i("Edit", "Inserted receipt id=" + rowId + " budgetId=" + budgetIdToSet);
            }

            exec.mainThread().execute(() -> {
                final String saved = getString(R.string.saved);

                final String toastMessage;
                if (budgetIdToSet != null) {
                    toastMessage = saved + " · added to budget";
                } else {
                    toastMessage = saved;
                }

                Toast.makeText(EditReceiptActivity.this, toastMessage, Toast.LENGTH_SHORT).show();

                finish();
            });
        });
    }
}
