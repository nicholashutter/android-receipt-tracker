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
import com.example.receipttracker.match.TotalVerifier;
import com.example.receipttracker.ocr.DetectedNumber;
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
    private TextInputEditText etMerchant, etDate, etAmount, etNotes;
    private TextView tvRawText, tvVerifier;
    private MaterialButton btnSave, btnDelete, btnMarkTotal;

    private long existingId = -1;
    private String photoPath;
    private String rawText;
    private long dateMillis = System.currentTimeMillis();
    private boolean showingRaw = false;

    // The amount the user just verified via "Pick & verify". When set and a
    // budget is active, we prompt to add the receipt to that budget on save.
    private Double lastVerifiedTotal = null;
    private boolean budgetPromptHandled = false;

    private final AppExecutors exec = AppExecutors.get();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("EDIT RECEIPT");
        Logger.i("Edit", "onCreate existingId=" + existingId);
        setContentView(R.layout.activity_edit_receipt);

        ivThumb = findViewById(R.id.iv_thumb);
        etMerchant = findViewById(R.id.et_merchant);
        etDate = findViewById(R.id.et_date);
        etAmount = findViewById(R.id.et_amount);
        etNotes = findViewById(R.id.et_notes);
        tvRawText = findViewById(R.id.tv_raw_text);
        tvVerifier = findViewById(R.id.tv_verifier);
        btnSave = findViewById(R.id.btn_save);
        btnDelete = findViewById(R.id.btn_delete);
        btnMarkTotal = findViewById(R.id.btn_mark_total);

        Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_RECEIPT_ID)) {
            existingId = intent.getLongExtra(EXTRA_RECEIPT_ID, -1);
        }
        photoPath = intent.getStringExtra(EXTRA_PHOTO_PATH);
        rawText = intent.getStringExtra(EXTRA_RAW_TEXT);

        // New-from-scan path: prefill the form with parsed values
        if (existingId < 0) {
            String merchant = intent.getStringExtra(EXTRA_MERCHANT);
            double amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0);
            long date = intent.getLongExtra(EXTRA_DATE_MILLIS, 0);
            if (merchant != null) etMerchant.setText(merchant);
            if (amount > 0) etAmount.setText(String.valueOf(amount));
            if (date > 0) dateMillis = date;
        }

        renderPhoto();
        renderDate();

        etDate.setOnClickListener(v -> showDatePicker());
        tvRawText.setOnClickListener(v -> {
            showingRaw = !showingRaw;
            tvRawText.setVisibility(showingRaw ? View.VISIBLE : View.GONE);
        });
        if (rawText != null && !rawText.isEmpty()) {
            tvRawText.setText(rawText);
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
        btnMarkTotal.setOnClickListener(v -> onMarkTotalClicked());

        if (existingId < 0) {
            btnDelete.setVisibility(View.GONE);
        } else {
            // Load existing receipt from DB on the disk executor.
            final long id = existingId;
            exec.diskIO().execute(() -> {
                Receipt r = AppDatabase.get(EditReceiptActivity.this).receiptDao().getById(id);
                exec.mainThread().execute(() -> {
                    if (r == null) { finish(); return; }
                    etMerchant.setText(r.merchant == null ? "" : r.merchant);
                    etAmount.setText(r.amount > 0 ? String.valueOf(r.amount) : "");
                    etNotes.setText(r.notes == null ? "" : r.notes);
                    dateMillis = r.dateMillis;
                    photoPath = r.photoPath;
                    rawText = r.rawText;
                    renderPhoto();
                    renderDate();
                    if (rawText != null) tvRawText.setText(rawText);
                });
            });
        }
    }

    private void renderPhoto() {
        if (photoPath == null) {
            ivThumb.setImageDrawable(null);
            return;
        }
        Bitmap bmp = ReceiptImageStore.decodeSampled(photoPath, 1200, 1200);
        if (bmp != null) ivThumb.setImageBitmap(bmp);
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
        if (etMerchant.getText() == null || etMerchant.getText().toString().trim().isEmpty()) {
            etMerchant.setError(getString(R.string.error_required));
            ok = false;
        }
        double amt = parseAmount();
        if (amt <= 0) {
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

    private void deleteReceipt() {
        if (existingId < 0) { finish(); return; }
        final long id = existingId;
        Logger.i("Edit", "deleteReceipt: id=" + id);
        exec.diskIO().execute(() -> {
            AppDatabase db = AppDatabase.get(EditReceiptActivity.this);
            Receipt r = db.receiptDao().getById(id);
            if (r != null) {
                // If this receipt was matched, also unmatch the bank transaction side
                // so it shows up in "unmatched" again and the user can re-link it.
                if (r.matchGroupId != null) {
                    for (BankTransaction t : db.bankTransactionDao().getAll()) {
                        if (r.matchGroupId.equals(t.matchGroupId)) {
                            db.bankTransactionDao().clearMatchGroup(t.id);
                            Logger.i("Edit", "Cascade-unmatched tx id=" + t.id
                                    + " (was paired with deleted receipt)");
                            break;
                        }
                    }
                }
                db.receiptDao().delete(r);
                if (r.photoPath != null) {
                    boolean ok = new File(r.photoPath).delete();
                    Logger.i("Edit", "Deleted photo " + r.photoPath + " ok=" + ok);
                }
            }
            exec.mainThread().execute(() -> {
                Toast.makeText(EditReceiptActivity.this, R.string.deleted, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    // ---------- mark + verify total flow ----------

    private List<DetectedNumber> cachedNumbers = new ArrayList<>();

    private void onMarkTotalClicked() {
        Logger.section("MARK TOTAL");
        Logger.i("Edit", "btn_mark_total clicked");
        if (rawText == null || rawText.isEmpty()) {
            Logger.w("Edit", "No raw OCR text available; cannot enumerate numbers");
            Toast.makeText(this, "No OCR text on this receipt", Toast.LENGTH_SHORT).show();
            return;
        }
        cachedNumbers = ReceiptParser.extractAllNumbers(rawText);
        if (cachedNumbers.isEmpty()) {
            Logger.w("Edit", "Parser found 0 numbers in raw text");
            Toast.makeText(this, "Parser found no numbers", Toast.LENGTH_SHORT).show();
            return;
        }
        // Build a readable label per number: "$23.45  •  Subtotal"
        String[] labels = new String[cachedNumbers.size()];
        for (int i = 0; i < cachedNumbers.size(); i++) {
            DetectedNumber n = cachedNumbers.get(i);
            String kw = n.keyword == null ? "" : "  •  " + n.keyword.toUpperCase();
            labels[i] = String.format(Locale.US, "$%.2f%s   [line %d]  %s",
                    n.value, kw, n.lineIndex, trim(n.line, 60));
        }
        Logger.i("Edit", "Showing " + labels.length + " numbers to user");
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_pick_total)
                .setItems(labels, (dialog, which) -> {
                    DetectedNumber picked = cachedNumbers.get(which);
                    Logger.i("Edit", "User picked value=" + picked.value
                            + " from line " + picked.lineIndex
                            + " (keyword=" + picked.keyword + ")");
                    runVerifier(picked);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    Logger.i("Edit", "Mark-total dialog cancelled");
                    d.dismiss();
                })
                .show();
    }

    private void runVerifier(DetectedNumber picked) {
        final double entered = parseAmount();
        Logger.i("Edit", "runVerifier: entered=" + entered + ", picked=" + picked.value);
        exec.diskIO().execute(() -> {
            TotalVerifier.Result r = TotalVerifier.verify(picked.value, cachedNumbers, entered);
            runOnUiThread(() -> {
                renderVerifier(picked, r, entered);
                // Capture the verified total so save can offer to add to a budget.
                lastVerifiedTotal = r.recommendedTotal;
                budgetPromptHandled = false;
            });
        });
    }

    private void renderVerifier(DetectedNumber picked, TotalVerifier.Result r, double entered) {
        applyVerdictBackground(r);
        StringBuilder body = new StringBuilder();
        body.append(String.format(Locale.US,
                "Marked:  $%.2f  (line %d: %s)%n", picked.value, picked.lineIndex, trim(picked.line, 50)));
        if (entered > 0) {
            body.append(String.format(Locale.US, "Entered: $%.2f%n", entered));
        }
        body.append(String.format(Locale.US, "%nVerifier verdict:%n"));
        body.append(String.format(Locale.US, "  Total: $%.2f   (source: %s)%n", r.recommendedTotal, r.recommendedSource));
        body.append(String.format(Locale.US, "  Confidence: %.0f%%%n", r.confidence * 100));
        body.append(String.format(Locale.US, "  P(circled is a price):  %.2f%n", r.priceProbability));
        body.append(String.format(Locale.US, "  P(circled is the total): %.2f  (best other: %.2f)%n",
                r.candidateProbability, r.bestAlternativeProbability));
        if (entered > 0) {
            body.append(String.format(Locale.US, "  P(entered is a price):  %.2f%n", r.enteredPriceProbability));
            body.append(String.format(Locale.US, "  P(entered is the total): %.2f%n", r.enteredProbability));
            body.append(r.enteredMatchesMarked
                    ? "  Cross-check: entered and circled agree (within $0.10)\n"
                    : "  Cross-check: entered and circled differ\n");
        }
        body.append(String.format(Locale.US, "  Sanity: %s%n", r.sanityCheck));
        body.append(String.format(Locale.US, "  %s%n%n", r.wasAdjusted
                ? "(adjusted from marked value)"
                : "(kept marked value)"));
        body.append(r.reasoning);
        tvVerifier.setText(body.toString());
        tvVerifier.setVisibility(View.VISIBLE);
        // Auto-apply the verifier's recommended total
        etAmount.setText(String.format(Locale.US, "%.2f", r.recommendedTotal));
        String toast = String.format(Locale.US,
                "Total: $%.2f  (%.0f%%, P(total)=%.2f, source=%s)",
                r.recommendedTotal, r.confidence * 100, r.candidateProbability, r.recommendedSource);
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
    }

    /**
     * Runs the cross-check on save: passes the current entered amount
     * and the best-guess circled value (the largest money on the receipt,
     * or the entered value itself if no candidates exist) so the
     * verifier can run its sanity check before we persist.
     */
    private void runSanityCheckBeforeSave() {
        if (rawText == null || rawText.isEmpty()) {
            Logger.w("Edit", "No raw text; skipping sanity check on save");
            return;
        }
        if (cachedNumbers.isEmpty()) {
            cachedNumbers = ReceiptParser.extractAllNumbers(rawText);
        }
        if (cachedNumbers.isEmpty()) {
            Logger.w("Edit", "No numbers to sanity-check; saving as-is");
            return;
        }
        final double entered = parseAmount();
        // The "candidate" for the sanity check is whichever number is closest to
        // the entered value — that's the one the user effectively marked.
        final DetectedNumber candidate;
        if (entered > 0) {
            DetectedNumber best = cachedNumbers.get(0);
            double bestDelta = Math.abs(best.value - entered);
            for (DetectedNumber n : cachedNumbers) {
                double d = Math.abs(n.value - entered);
                if (d < bestDelta) { best = n; bestDelta = d; }
            }
            candidate = best;
        } else {
            // No entered value — sanity-check against the largest detected number.
            DetectedNumber largest = cachedNumbers.get(0);
            for (DetectedNumber n : cachedNumbers) if (n.value > largest.value) largest = n;
            candidate = largest;
        }
        Logger.i("Edit", "save-time sanity check: candidate=" + candidate.value
                + "  entered=" + entered);
        exec.diskIO().execute(() -> {
            TotalVerifier.Result r = TotalVerifier.verify(candidate.value, cachedNumbers, entered);
            runOnUiThread(() -> {
                StringBuilder msg = new StringBuilder();
                msg.append(String.format(Locale.US,
                        "Sanity check: $%.2f  (%s, %.0f%% conf)",
                        r.recommendedTotal, r.recommendedSource, r.confidence * 100));
                if (entered > 0 && r.recommendedTotal != entered) {
                    msg.append(String.format(Locale.US,
                            "%n%n  Entered:  $%.2f%n  Recommended: $%.2f%n  %s",
                            entered, r.recommendedTotal, r.sanityCheck));
                }
                Logger.i("Edit", "save-time sanity: " + msg);
                // Show the verdict in the on-screen panel too, so the user sees the math.
                renderVerifier(candidate, r, entered);
                Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show();
                // Save regardless — the sanity check is advisory, not blocking.
                saveReceiptInternal();
            });
        });
    }

    private void saveReceipt() {
        if (!validate()) return;
        // If the user verified a total via "Pick & verify" and a budget is
        // active, prompt to add this receipt to that budget. Skip the prompt
        // if we already asked (e.g. sanity check re-runs renderVerifier).
        if (lastVerifiedTotal != null && !budgetPromptHandled) {
            exec.diskIO().execute(() -> {
                Budget active = AppDatabase.get(EditReceiptActivity.this)
                        .budgetDao().getActive();
                runOnUiThread(() -> {
                    if (active == null) {
                        runSanityCheckBeforeSave();
                    } else {
                        showBudgetPrompt(active);
                    }
                });
            });
            return;
        }
        runSanityCheckBeforeSave();
    }

    private void showBudgetPrompt(Budget active) {
        double total = parseAmount();
        String msg = String.format(Locale.US,
                "Add $%.2f to '%s' budget? (%.0f%% used, %s cap)",
                total, active.name,
                active.maxAmount > 0 ? Math.min(100, total * 100.0 / active.maxAmount) : 0,
                MoneyUtils.format(active.maxAmount));
        new AlertDialog.Builder(this)
                .setTitle("Add to budget")
                .setMessage(msg)
                .setPositiveButton("Add", (d, w) -> {
                    budgetPromptHandled = true;
                    pendingBudgetId = active.id;
                    runSanityCheckBeforeSave();
                })
                .setNegativeButton("Skip", (d, w) -> {
                    budgetPromptHandled = true;
                    runSanityCheckBeforeSave();
                })
                .setNeutralButton("Choose another", (d, w) -> {
                    budgetPromptHandled = true;
                    showBudgetPicker();
                })
                .setCancelable(false)
                .show();
    }

    private Long pendingBudgetId = null;

    private void showBudgetPicker() {
        exec.diskIO().execute(() -> {
            List<Budget> all = AppDatabase.get(EditReceiptActivity.this)
                    .budgetDao().getAllActive();
            runOnUiThread(() -> {
                if (all == null || all.isEmpty()) {
                    Toast.makeText(this, "No budgets available", Toast.LENGTH_SHORT).show();
                    runSanityCheckBeforeSave();
                    return;
                }
                String[] labels = new String[all.size()];
                for (int i = 0; i < all.size(); i++) {
                    Budget b = all.get(i);
                    labels[i] = String.format(Locale.US, "%s — %s / %s",
                            b.name, MoneyUtils.format(b.maxAmount), MoneyUtils.format(b.maxAmount));
                }
                new AlertDialog.Builder(this)
                        .setTitle("Choose budget")
                        .setItems(labels, (d, w) -> {
                            pendingBudgetId = all.get(w).id;
                            runSanityCheckBeforeSave();
                        })
                        .setNegativeButton(android.R.string.cancel, (d2, w2) -> runSanityCheckBeforeSave())
                        .show();
            });
        });
    }

    private void saveReceiptInternal() {
        final Receipt r = new Receipt();
        r.id = existingId >= 0 ? existingId : 0;
        r.merchant = etMerchant.getText().toString().trim();
        r.amount = parseAmount();
        r.dateMillis = dateMillis;
        r.notes = etNotes.getText() == null ? null : etNotes.getText().toString().trim();
        r.photoPath = photoPath;
        r.rawText = rawText;
        r.createdAt = System.currentTimeMillis();
        // Link to the budget the user picked in the prompt. Only set on insert;
        // updates preserve the existing budgetId.
        final Long budgetIdToSet = pendingBudgetId;
        Logger.i("Edit", "saveReceiptInternal: id=" + r.id + " merchant='" + r.merchant
                + "' amount=" + r.amount + " dateMillis=" + r.dateMillis
                + " photoPath=" + (r.photoPath == null ? "null" : r.photoPath)
                + " budgetId=" + budgetIdToSet);

        exec.diskIO().execute(() -> {
            AppDatabase db = AppDatabase.get(EditReceiptActivity.this);
            ReceiptDao dao = db.receiptDao();
            long rowId;
            if (r.id > 0) {
                Receipt existing = dao.getById(r.id);
                if (existing != null) {
                    r.matchGroupId = existing.matchGroupId;
                    r.createdAt = existing.createdAt;
                    if (budgetIdToSet != null) r.budgetId = budgetIdToSet;
                    else r.budgetId = existing.budgetId;
                }
                dao.update(r);
                rowId = r.id;
                Logger.i("Edit", "Updated receipt id=" + r.id);
            } else {
                if (budgetIdToSet != null) r.budgetId = budgetIdToSet;
                rowId = dao.insert(r);
                Logger.i("Edit", "Inserted receipt id=" + rowId + " budgetId=" + budgetIdToSet);
            }
            exec.mainThread().execute(() -> {
                String saved = getString(R.string.saved);
                String msg = budgetIdToSet != null
                        ? saved + " · added to budget"
                        : saved;
                Toast.makeText(EditReceiptActivity.this, msg, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Switches the verdict panel's background to match how confident the
     * verifier is. High confidence -> emerald (ok), mid -> amber (warn),
     * low or adjusted -> red (err). The colors come from the bg_verdict_*
     * drawables so they stay in sync with colors.xml.
     */
    private void applyVerdictBackground(TotalVerifier.Result r) {
        int drawable;
        if (r.confidence >= 0.7) {
            drawable = R.drawable.bg_verdict_ok;
        } else if (r.confidence >= 0.4) {
            drawable = R.drawable.bg_verdict_warn;
        } else {
            drawable = R.drawable.bg_verdict_err;
        }
        tvVerifier.setBackgroundResource(drawable);
    }
}
