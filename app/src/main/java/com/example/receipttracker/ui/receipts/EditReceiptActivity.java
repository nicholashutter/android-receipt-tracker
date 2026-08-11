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

import com.example.receipttracker.data.Receipt;

import com.example.receipttracker.data.ReceiptDao;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.match.TotalVerifier;

import com.example.receipttracker.ocr.DetectedNumber;

import com.example.receipttracker.ocr.MerchantClassifier;

import com.example.receipttracker.ocr.ReceiptImageStore;

import com.example.receipttracker.ocr.ReceiptOcr;

import com.example.receipttracker.ocr.ReceiptParser;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;

import com.google.android.material.button.MaterialButton;

import com.google.android.material.textfield.TextInputEditText;


import java.io.File;

import java.util.ArrayList;

import java.util.Calendar;

import java.util.Collections;

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

    private TextView tvVerifier;

    private MaterialButton btnSave;

    private MaterialButton btnDelete;

    private MaterialButton btnMarkTotal;


    private long existingId = -1;

    private String photoPath;

    private String rawText;

    private long dateMillis = System.currentTimeMillis();

    private boolean showingRaw = false;


    // The amount the user just verified via "Pick & verify". When set and a
    // budget is active, we prompt to add the receipt to that budget on save.
    private Double lastVerifiedTotal = null;

    private boolean budgetPromptHandled = false;


    // Set when the user picks a budget in the "Add to budget?" dialog; consumed
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
        }
    }


    private void bindViews() {
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
        }


        // Auto-pick the "circled" total and run the verifier on it.
        // The whole point of OCR is that we don't make the user re-pick
        // the total — the activity should arrive with a reasonable
        // amount pre-filled, the verdict panel visible, and a manual
        // override button (btnMarkTotal) for the cases where the
        // auto-pick is wrong.
        if (existingId < 0 && rawText != null && !rawText.isEmpty()) {
            autoPickAndVerify();
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
    }


    private void loadExistingReceipt(final long id) {
        btnDelete.setVisibility(View.GONE);

        // Load existing receipt from DB on the disk executor.
        exec.diskIO().execute(() -> {
            final Receipt receipt = AppDatabase.get(EditReceiptActivity.this).receiptDao().getById(id);

            exec.mainThread().execute(() -> {
                if (receipt == null) {
                    finish();
                    return;
                }

                bindExistingReceipt(receipt);
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


    // ---------- mark + verify total flow ----------

    private List<DetectedNumber> cachedNumbers = new ArrayList<>();


    /**
     * Auto-pick the "circled" number on a freshly scanned receipt and
     * run the verifier on it. Fills the amount field with the
     * recommended total and renders the verdict panel, so the activity
     * opens looking like the user already picked the right number.
     * The user can edit the amount or tap "Re-pick" to override.
     *
     * <p>Visual-signal path: if we have a photo on disk, re-run OCR
     * with bounding boxes and run {@link VisualSignalDetector} on
     * each number's bbox, so a yellow-highlighted or pen-circled
     * number wins a *strong* boost in
     * {@link ReceiptParser#pickCircledCandidate}. If the re-OCR
     * fails, fall back to text-only number extraction.</p>
     *
     * <p>Ensemble: the final verdict is a 10-run ensemble across the
     * top 10 candidates by P(isTotal), so the confidence reflects
     * panel consensus rather than a single run.</p>
     */
    private void autoPickAndVerify() {
        if (rawText == null || rawText.isEmpty()) return;

        Logger.section("AUTO-PICK TOTAL");

        exec.diskIO().execute(() -> {
            // Try to re-OCR for visual signals (highlighted numbers win).
            List<DetectedNumber> numbers = tryExtractWithVisualSignals();

            if (numbers.isEmpty()) {
                Logger.w("Edit", "autoPick: fallback to text-only extractAllNumbers");

                numbers = ReceiptParser.extractAllNumbers(rawText);
            }

            cachedNumbers = numbers;

            if (numbers.isEmpty()) {
                Logger.w("Edit", "autoPick: parser found 0 numbers; nothing to auto-pick");

                return;
            }

            final DetectedNumber picked = ReceiptParser.pickCircledCandidate(numbers);

            if (picked == null) {
                Logger.w("Edit", "autoPick: pickCircledCandidate returned null");

                return;
            }

            Logger.i("Edit", "autoPick: chose $" + picked.value
                    + " from line " + picked.lineIndex
                    + " (keyword=" + picked.keyword
                    + ", hl=" + String.format(Locale.US, "%.2f", picked.highlightScore)
                    + ", cr=" + String.format(Locale.US, "%.2f", picked.circleScore) + ")");

            // Use the 10-run ensemble for the final verdict.
            runVerifierEnsemble(picked, /*autoPicked=*/true);
        });
    }


    /**
     * Re-OCRs the photo at {@code photoPath} and returns detected
     * numbers with per-bbox visual-signal scores. Returns an empty
     * list if the photo can't be decoded or OCR fails — the caller
     * falls back to text-only extraction in that case.
     */
    private List<DetectedNumber> tryExtractWithVisualSignals() {
        if (photoPath == null) return Collections.emptyList();

        try {
            final Bitmap bitmap = ReceiptImageStore.decodeSampled(photoPath, 1600, 1600);

            if (bitmap == null) {
                Logger.w("Edit", "tryExtractWithVisualSignals: failed to decode bitmap");

                return Collections.emptyList();
            }

            final List<ReceiptOcr.OcrLine> lines = ReceiptOcr.recognizeWithBoxes(bitmap);

            if (lines == null || lines.isEmpty()) {
                Logger.w("Edit", "tryExtractWithVisualSignals: structured OCR returned 0 lines");

                return Collections.emptyList();
            }

            return ReceiptParser.extractAllNumbersWithVisualSignals(bitmap, lines);
        } catch (Throwable t) {
            Logger.e("Edit", "tryExtractWithVisualSignals: failed", t);

            return Collections.emptyList();
        }
    }


    /**
     * Runs the 10-run ensemble verifier and renders the result. Falls
     * back to a single verify() call on failure.
     */
    private void runVerifierEnsemble(DetectedNumber picked, boolean autoPicked) {
        final double entered = parseAmount();

        final TotalVerifier.Result[] holder = new TotalVerifier.Result[1];

        Logger.i("Edit", "runVerifierEnsemble: entered=" + entered
                + ", picked=" + picked.value + ", autoPicked=" + autoPicked);

        exec.diskIO().execute(() -> {
            try {
                holder[0] = TotalVerifier.verifyEnsemble(picked.value, cachedNumbers, entered,
                        TotalVerifier.DEFAULT_ENSEMBLE_SIZE);
            } catch (Throwable t) {
                Logger.e("Edit", "verifyEnsemble failed, falling back to single verify", t);

                holder[0] = TotalVerifier.verify(picked.value, cachedNumbers, entered);
            }

            final TotalVerifier.Result result = holder[0];

            runOnUiThread(() -> {
                renderVerifier(picked, result, entered, autoPicked);

                lastVerifiedTotal = result.recommendedTotal;

                budgetPromptHandled = false;
            });
        });
    }


    private void onMarkTotalClicked() {
        Logger.section("RE-PICK TOTAL");

        Logger.i("Edit", "btn_mark_total clicked (manual override)");

        if (rawText == null || rawText.isEmpty()) {
            Logger.w("Edit", "No raw OCR text available; cannot enumerate numbers");

            Toast.makeText(this, "No OCR text on this receipt", Toast.LENGTH_SHORT).show();

            return;
        }

        if (cachedNumbers.isEmpty()) {
            cachedNumbers = ReceiptParser.extractAllNumbers(rawText);
        }

        if (cachedNumbers.isEmpty()) {
            Logger.w("Edit", "Parser found 0 numbers in raw text");

            Toast.makeText(this, "Parser found no numbers", Toast.LENGTH_SHORT).show();

            return;
        }

        // Build a readable label per number: "$23.45  •  Subtotal"
        final String[] labels = new String[cachedNumbers.size()];

        for (int index = 0; index < cachedNumbers.size(); index++) {
            final DetectedNumber number = cachedNumbers.get(index);

            final String keywordSuffix;
            if (number.keyword == null) {
                keywordSuffix = "";
            } else {
                keywordSuffix = "  •  " + number.keyword.toUpperCase();
            }

            labels[index] = String.format(Locale.US, "$%.2f%s   [line %d]  %s",
                    number.value, keywordSuffix, number.lineIndex, trim(number.line, 60));
        }

        Logger.i("Edit", "Showing " + labels.length + " numbers to user (override)");

        new AlertDialog.Builder(this)
                .setTitle(R.string.action_pick_total)
                .setItems(labels, (dialog, which) -> {
                    final DetectedNumber picked = cachedNumbers.get(which);

                    Logger.i("Edit", "User picked value=" + picked.value
                            + " from line " + picked.lineIndex
                            + " (keyword=" + picked.keyword + ")");

                    runVerifier(picked, /*autoPicked=*/false);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    Logger.i("Edit", "Re-pick dialog cancelled");

                    dialog.dismiss();
                })
                .show();
    }


    private void runVerifier(DetectedNumber picked, boolean autoPicked) {
        final double entered = parseAmount();

        Logger.i("Edit", "runVerifier: entered=" + entered + ", picked=" + picked.value
                + ", autoPicked=" + autoPicked);

        exec.diskIO().execute(() -> {
            final TotalVerifier.Result result = TotalVerifier.verify(picked.value, cachedNumbers, entered);

            runOnUiThread(() -> {
                // Show the verdict panel for comparison, but DON'T let it
                // override the re-pick — the user explicitly chose this
                // number, so it should be what shows up in the budget.
                renderVerifier(picked, result, entered, autoPicked,
                        /*overwriteAmount=*/false, /*showToast=*/true);

                // Honor the re-pick: the amount field and the "picked in
                // the budget" value both track the user's choice, not
                // the verifier's adjusted recommendation. The panel still
                // shows the comparison so the user can see if the
                // verifier disagreed.
                etAmount.setText(String.format(Locale.US, "%.2f", picked.value));

                lastVerifiedTotal = picked.value;

                budgetPromptHandled = false;
            });
        });
    }


    private void renderVerifier(DetectedNumber picked, TotalVerifier.Result result,
                                double entered, boolean autoPicked) {
        renderVerifier(picked, result, entered, autoPicked, /*overwriteAmount=*/true,
                /*showToast=*/true);
    }


    /**
     * @param overwriteAmount when true (the default), the amount field is
     *     replaced with {@code result.recommendedTotal}. When false, the field is
     *     left untouched — used by callers (re-pick and save-time sanity
     *     check) that want the verdict panel for comparison but must NOT
     *     clobber the user's explicit choice.
     * @param showToast when false, the "Re-picked: $X.XX" confirmation toast
     *     is suppressed. Used by the save-time sanity check, which fires its
     *     own "Sanity check: $X.XX" toast; firing the re-pick toast too
     *     would be misleading (the user didn't re-pick this number — the
     *     sanity check chose it as the closest candidate).
     */
    private void renderVerifier(DetectedNumber picked, TotalVerifier.Result result,
                                double entered, boolean autoPicked,
                                boolean overwriteAmount, boolean showToast) {
        applyVerdictBackground(result);

        tvVerifier.setText(buildVerifierBody(picked, result, entered, autoPicked));

        tvVerifier.setVisibility(View.VISIBLE);

        // Auto-apply the verifier's recommended total, unless the caller
        // explicitly opted out (re-pick honors the user's pick; save-time
        // sanity check is advisory only).
        if (overwriteAmount) {
            etAmount.setText(String.format(Locale.US, "%.2f", result.recommendedTotal));
        }

        // For auto-pick, the amount was pre-filled, so no toast — the
        // verdict panel communicates the result. For manual picks
        // (the user just tapped a number in the dialog) a toast is
        // still useful confirmation. When the caller's honoring the
        // re-pick (overwriteAmount=false), the amount field shows the
        // picked value, not the verifier's adjusted recommendation —
        // so the toast should reflect that, with a note if the
        // verifier disagreed.
        if (!autoPicked && showToast) {
            showRePickToast(picked, result, overwriteAmount);
        }
    }


    private String buildVerifierBody(DetectedNumber picked, TotalVerifier.Result result,
                                      double entered, boolean autoPicked) {
        final StringBuilder body = new StringBuilder();

        if (autoPicked) {
            body.append(String.format(Locale.US,
                    "Auto-picked by OCR: $%.2f  (line %d: %s)%n",
                    picked.value, picked.lineIndex, trim(picked.line, 50)));
        } else {
            body.append(String.format(Locale.US,
                    "Marked:  $%.2f  (line %d: %s)%n",
                    picked.value, picked.lineIndex, trim(picked.line, 50)));
        }

        if (picked.isVisuallyEmphasised()) {
            body.append(String.format(Locale.US,
                    "  Visually emphasised (hl=%.2f cr=%.2f)%n",
                    picked.highlightScore, picked.circleScore));
        }

        if (entered > 0) {
            body.append(String.format(Locale.US, "Entered: $%.2f%n", entered));
        }

        body.append(String.format(Locale.US, "%nVerifier verdict:%n"));

        body.append(String.format(Locale.US, "  Total: $%.2f   (source: %s)%n", result.recommendedTotal, result.recommendedSource));

        body.append(String.format(Locale.US, "  Confidence: %.0f%%%n", result.confidence * 100));

        if (result.ensembleSize > 1) {
            body.append(String.format(Locale.US, "  Ensemble: %d/%d runs voted $%.2f  (consensus conf=%.0f%%)%n",
                    result.ensembleVotesForWinner, result.ensembleSize, result.recommendedTotal,
                    result.ensembleConfidence * 100));
        }

        body.append(String.format(Locale.US, "  P(circled is a price):  %.2f%n", result.priceProbability));

        body.append(String.format(Locale.US, "  P(circled is the total): %.2f  (best other: %.2f)%n",
                result.candidateProbability, result.bestAlternativeProbability));

        if (entered > 0) {
            body.append(String.format(Locale.US, "  P(entered is a price):  %.2f%n", result.enteredPriceProbability));

            body.append(String.format(Locale.US, "  P(entered is the total): %.2f%n", result.enteredProbability));

            if (result.enteredMatchesMarked) {
                body.append("  Cross-check: entered and circled agree (within $0.10)\n");
            } else {
                body.append("  Cross-check: entered and circled differ\n");
            }
        }

        body.append(String.format(Locale.US, "  Sanity: %s%n", result.sanityCheck));

        if (result.wasAdjusted) {
            body.append(String.format(Locale.US, "  (adjusted from marked value)%n%n"));
        } else {
            body.append(String.format(Locale.US, "  (kept marked value)%n%n"));
        }

        body.append(result.reasoning);

        return body.toString();
    }


    private void showRePickToast(DetectedNumber picked, TotalVerifier.Result result, boolean overwriteAmount) {
        final String toastText;
        if (overwriteAmount) {
            toastText = String.format(Locale.US,
                    "Total: $%.2f  (%.0f%%, P(total)=%.2f, source=%s)",
                    result.recommendedTotal, result.confidence * 100, result.candidateProbability, result.recommendedSource);
        } else if (result.recommendedTotal == picked.value) {
            toastText = String.format(Locale.US,
                    "Re-picked: $%.2f  (verifier agrees, %.0f%%)",
                    picked.value, result.confidence * 100);
        } else {
            toastText = String.format(Locale.US,
                    "Re-picked: $%.2f  (verifier recommends $%.2f, %.0f%%)",
                    picked.value, result.recommendedTotal, result.confidence * 100);
        }

        Toast.makeText(this, toastText, Toast.LENGTH_LONG).show();
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
            final DetectedNumber closest = findClosestToEntered(cachedNumbers, entered);

            candidate = closest;
        } else {
            // No entered value — sanity-check against the largest detected number.
            final DetectedNumber largest = findLargestNumber(cachedNumbers);

            candidate = largest;
        }

        Logger.i("Edit", "save-time sanity check: candidate=" + candidate.value
                + "  entered=" + entered);

        exec.diskIO().execute(() -> {
            final TotalVerifier.Result result = TotalVerifier.verify(candidate.value, cachedNumbers, entered);

            runOnUiThread(() -> {
                final StringBuilder toastBody = buildSanityCheckToast(result, entered);

                Logger.i("Edit", "save-time sanity: " + toastBody);

                // Show the verdict in the on-screen panel too, so the user
                // sees the math. Advisory only — do NOT overwrite the
                // entered amount: if the user re-picked or typed a value,
                // that's the amount that should be saved and reflected in
                // the budget. Suppress the "Re-picked" toast too — the
                // user didn't re-pick this number (the sanity check chose
                // it as the closest candidate); the sanity check's own
                // toast below already shows the comparison.
                renderVerifier(candidate, result, entered, /*autoPicked=*/false,
                        /*overwriteAmount=*/false, /*showToast=*/false);

                Toast.makeText(this, toastBody.toString(), Toast.LENGTH_LONG).show();

                // Save regardless — the sanity check is advisory, not blocking.
                saveReceiptInternal();
            });
        });
    }


    private static DetectedNumber findClosestToEntered(List<DetectedNumber> numbers, double entered) {
        DetectedNumber best = numbers.get(0);

        double bestDelta = Math.abs(best.value - entered);

        for (DetectedNumber number : numbers) {
            final double delta = Math.abs(number.value - entered);

            if (delta < bestDelta) {
                best = number;

                bestDelta = delta;
            }
        }

        return best;
    }


    private static DetectedNumber findLargestNumber(List<DetectedNumber> numbers) {
        DetectedNumber largest = numbers.get(0);

        for (DetectedNumber number : numbers) {
            if (number.value > largest.value) largest = number;
        }

        return largest;
    }


    private StringBuilder buildSanityCheckToast(TotalVerifier.Result result, double entered) {
        final StringBuilder toastBody = new StringBuilder();

        toastBody.append(String.format(Locale.US,
                "Sanity check: $%.2f  (%s, %.0f%% conf)",
                result.recommendedTotal, result.recommendedSource, result.confidence * 100));

        if (entered > 0 && result.recommendedTotal != entered) {
            toastBody.append(String.format(Locale.US,
                    "%n%n  Entered:  $%.2f%n  Recommended: $%.2f%n  %s",
                    entered, result.recommendedTotal, result.sanityCheck));
        }

        return toastBody;
    }


    private void saveReceipt() {
        if (!validate()) return;

        // If the user verified a total via "Pick & verify" and a budget is
        // active, prompt to add this receipt to that budget. Skip the prompt
        // if we already asked (e.g. sanity check re-runs renderVerifier).
        if (lastVerifiedTotal != null && !budgetPromptHandled) {
            exec.diskIO().execute(() -> {
                final Budget active = AppDatabase.get(EditReceiptActivity.this)
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
        final double total = parseAmount();

        final double usedPercent;
        if (active.maxAmount > 0) {
            usedPercent = Math.min(100, total * 100.0 / active.maxAmount);
        } else {
            usedPercent = 0;
        }

        final String message = String.format(Locale.US,
                "Add $%.2f to '%s' budget? (%.0f%% used, %s cap)",
                total, active.name,
                usedPercent,
                MoneyUtils.format(active.maxAmount));

        new AlertDialog.Builder(this)
                .setTitle("Add to budget")
                .setMessage(message)
                .setPositiveButton("Add", (dialog, which) -> {
                    budgetPromptHandled = true;

                    pendingBudgetId = active.id;

                    runSanityCheckBeforeSave();
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    budgetPromptHandled = true;

                    runSanityCheckBeforeSave();
                })
                .setNeutralButton("Choose another", (dialog, which) -> {
                    budgetPromptHandled = true;

                    showBudgetPicker();
                })
                .setCancelable(false)
                .show();
    }


    private void showBudgetPicker() {
        exec.diskIO().execute(() -> {
            final List<Budget> allBudgets = AppDatabase.get(EditReceiptActivity.this)
                    .budgetDao().getAllActive();

            runOnUiThread(() -> {
                if (allBudgets == null || allBudgets.isEmpty()) {
                    Toast.makeText(this, "No budgets available", Toast.LENGTH_SHORT).show();

                    runSanityCheckBeforeSave();

                    return;
                }

                final String[] labels = new String[allBudgets.size()];

                for (int index = 0; index < allBudgets.size(); index++) {
                    final Budget budget = allBudgets.get(index);

                    labels[index] = String.format(Locale.US, "%s — %s / %s",
                            budget.name, MoneyUtils.format(budget.maxAmount), MoneyUtils.format(budget.maxAmount));
                }

                new AlertDialog.Builder(this)
                        .setTitle("Choose budget")
                        .setItems(labels, (dialog, which) -> {
                            pendingBudgetId = allBudgets.get(which).id;

                            runSanityCheckBeforeSave();
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> runSanityCheckBeforeSave())
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


    private static String trim(String input, int max) {
        if (input == null) return "";

        final String trimmed = input.trim();

        if (trimmed.length() <= max) {
            return trimmed;
        }

        return trimmed.substring(0, max - 1) + "…";
    }


    /**
     * Switches the verdict panel's background to match how confident the
     * verifier is. High confidence -> emerald (ok), mid -> amber (warn),
     * low or adjusted -> red (err). The colors come from the bg_verdict_*
     * drawables so they stay in sync with colors.xml.
     */
    private void applyVerdictBackground(TotalVerifier.Result result) {
        final int drawable;
        if (result.confidence >= 0.7) {
            drawable = R.drawable.bg_verdict_ok;
        } else if (result.confidence >= 0.4) {
            drawable = R.drawable.bg_verdict_warn;
        } else {
            drawable = R.drawable.bg_verdict_err;
        }

        tvVerifier.setBackgroundResource(drawable);
    }
}
