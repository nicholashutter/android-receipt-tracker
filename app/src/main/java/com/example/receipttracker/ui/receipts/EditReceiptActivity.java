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

            if (merchant != null) {
                // Stage 2: refine the parsed merchant through the JSON
                // classifier. If the classifier has a high-confidence
                // match, replace the raw OCR string with the canonical
                // form (e.g. "WHOLE FOODS" -> "Whole Foods Market").
                // Otherwise keep the OCR string.
                MerchantClassifier.Prediction pred = MerchantClassifier.predict(merchant);

                String refined;

                if (pred != null && pred.confidence >= 0.40) {
                    refined = pred.name;
                } else {
                    refined = merchant;
                }

                etMerchant.setText(refined);

                if (pred != null) {
                    Logger.i("Edit", "merchant refined: '" + merchant
                            + "' -> '" + refined + "' (conf=" + String.format("%.2f", pred.confidence) + ")");
                }
            }

            if (amount > 0) etAmount.setText(String.valueOf(amount));

            if (date > 0) dateMillis = date;
        }


        renderPhoto();

        renderDate();


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


        if (existingId < 0) {
            btnDelete.setVisibility(View.GONE);
        } else {
            // Load existing receipt from DB on the disk executor.
            final long id = existingId;

            exec.diskIO().execute(() -> {
                Receipt r = AppDatabase.get(EditReceiptActivity.this).receiptDao().getById(id);

                exec.mainThread().execute(() -> {
                    if (r == null) { finish(); return; }

                    String merchantText;

                    if (r.merchant == null) {
                        merchantText = "";
                    } else {
                        merchantText = r.merchant;
                    }

                    etMerchant.setText(merchantText);

                    String amountText;

                    if (r.amount > 0) {
                        amountText = String.valueOf(r.amount);
                    } else {
                        amountText = "";
                    }

                    etAmount.setText(amountText);

                    String notesText;

                    if (r.notes == null) {
                        notesText = "";
                    } else {
                        notesText = r.notes;
                    }

                    etNotes.setText(notesText);

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

            DetectedNumber picked = ReceiptParser.pickCircledCandidate(numbers);

            if (picked == null) {
                Logger.w("Edit", "autoPick: pickCircledCandidate returned null");

                return;
            }

            String handwritingNote;
            if (picked.isHandwrittenAndMarked()) {
                handwritingNote = " [HANDWRITTEN — Tesseract re-recognised as $" + picked.value + "]";
            } else {
                handwritingNote = "";
            }
            Logger.i("Edit", "autoPick: chose $" + picked.value
                    + " from line " + picked.lineIndex
                    + " (keyword=" + picked.keyword
                    + ", hl=" + String.format(Locale.US, "%.2f", picked.highlightScore)
                    + ", cr=" + String.format(Locale.US, "%.2f", picked.circleScore)
                    + handwritingNote + ")");

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
            Bitmap bmp = ReceiptImageStore.decodeSampled(photoPath, 1600, 1600);

            if (bmp == null) {
                Logger.w("Edit", "tryExtractWithVisualSignals: failed to decode bitmap");

                return Collections.emptyList();
            }

            List<ReceiptOcr.OcrLine> lines = ReceiptOcr.recognizeWithBoxes(bmp);

            if (lines == null || lines.isEmpty()) {
                Logger.w("Edit", "tryExtractWithVisualSignals: structured OCR returned 0 lines");

                return Collections.emptyList();
            }

            return ReceiptParser.extractAllNumbersWithVisualSignals(bmp, lines);
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

            final TotalVerifier.Result r = holder[0];

            runOnUiThread(() -> {
                renderVerifier(picked, r, entered, autoPicked);

                lastVerifiedTotal = r.recommendedTotal;

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
        String[] labels = new String[cachedNumbers.size()];

        for (int i = 0; i < cachedNumbers.size(); i++) {
            DetectedNumber n = cachedNumbers.get(i);

            String kw;

            if (n.keyword == null) {
                kw = "";
            } else {
                kw = "  •  " + n.keyword.toUpperCase();
            }

            labels[i] = String.format(Locale.US, "$%.2f%s   [line %d]  %s",
                    n.value, kw, n.lineIndex, trim(n.line, 60));
        }

        Logger.i("Edit", "Showing " + labels.length + " numbers to user (override)");

        new AlertDialog.Builder(this)
                .setTitle(R.string.action_pick_total)
                .setItems(labels, (dialog, which) -> {
                    DetectedNumber picked = cachedNumbers.get(which);

                    Logger.i("Edit", "User picked value=" + picked.value
                            + " from line " + picked.lineIndex
                            + " (keyword=" + picked.keyword + ")");

                    runVerifier(picked, /*autoPicked=*/false);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    Logger.i("Edit", "Re-pick dialog cancelled");

                    d.dismiss();
                })
                .show();
    }


    private void runVerifier(DetectedNumber picked, boolean autoPicked) {
        final double entered = parseAmount();

        Logger.i("Edit", "runVerifier: entered=" + entered + ", picked=" + picked.value
                + ", autoPicked=" + autoPicked);

        exec.diskIO().execute(() -> {
            TotalVerifier.Result r = TotalVerifier.verify(picked.value, cachedNumbers, entered);

            runOnUiThread(() -> {
                // Show the verdict panel for comparison, but DON'T let it
                // override the re-pick — the user explicitly chose this
                // number, so it should be what shows up in the budget.
                renderVerifier(picked, r, entered, autoPicked,
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


    private void renderVerifier(DetectedNumber picked, TotalVerifier.Result r,
                                double entered, boolean autoPicked) {
        renderVerifier(picked, r, entered, autoPicked, /*overwriteAmount=*/true,
                /*showToast=*/true);
    }


    /**
     * @param overwriteAmount when true (the default), the amount field is
     *     replaced with {@code r.recommendedTotal}. When false, the field is
     *     left untouched — used by callers (re-pick and save-time sanity
     *     check) that want the verdict panel for comparison but must NOT
     *     clobber the user's explicit choice.
     * @param showToast when false, the "Re-picked: $X.XX" confirmation toast
     *     is suppressed. Used by the save-time sanity check, which fires its
     *     own "Sanity check: $X.XX" toast; firing the re-pick toast too
     *     would be misleading (the user didn't re-pick this number — the
     *     sanity check chose it as the closest candidate).
     */
    private void renderVerifier(DetectedNumber picked, TotalVerifier.Result r,
                                double entered, boolean autoPicked,
                                boolean overwriteAmount, boolean showToast) {
        applyVerdictBackground(r);

        StringBuilder body = new StringBuilder();

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

        if (picked.isHandwrittenAndMarked()) {
            body.append(String.format(Locale.US,
                    "  Handwritten — Tesseract re-recognised this bbox%n"));
        }

        if (entered > 0) {
            body.append(String.format(Locale.US, "Entered: $%.2f%n", entered));
        }

        body.append(String.format(Locale.US, "%nVerifier verdict:%n"));

        body.append(String.format(Locale.US, "  Total: $%.2f   (source: %s)%n", r.recommendedTotal, r.recommendedSource));

        body.append(String.format(Locale.US, "  Confidence: %.0f%%%n", r.confidence * 100));

        if (r.ensembleSize > 1) {
            body.append(String.format(Locale.US, "  Ensemble: %d/%d runs voted $%.2f  (consensus conf=%.0f%%)%n",
                    r.ensembleVotesForWinner, r.ensembleSize, r.recommendedTotal,
                    r.ensembleConfidence * 100));
        }

        body.append(String.format(Locale.US, "  P(circled is a price):  %.2f%n", r.priceProbability));

        body.append(String.format(Locale.US, "  P(circled is the total): %.2f  (best other: %.2f)%n",
                r.candidateProbability, r.bestAlternativeProbability));

        if (entered > 0) {
            body.append(String.format(Locale.US, "  P(entered is a price):  %.2f%n", r.enteredPriceProbability));

            body.append(String.format(Locale.US, "  P(entered is the total): %.2f%n", r.enteredProbability));

            if (r.enteredMatchesMarked) {
                body.append("  Cross-check: entered and circled agree (within $0.10)\n");
            } else {
                body.append("  Cross-check: entered and circled differ\n");
            }
        }

        body.append(String.format(Locale.US, "  Sanity: %s%n", r.sanityCheck));

        if (r.wasAdjusted) {
            body.append(String.format(Locale.US, "  (adjusted from marked value)%n%n"));
        } else {
            body.append(String.format(Locale.US, "  (kept marked value)%n%n"));
        }

        body.append(r.reasoning);

        tvVerifier.setText(body.toString());

        tvVerifier.setVisibility(View.VISIBLE);

        // Auto-apply the verifier's recommended total, unless the caller
        // explicitly opted out (re-pick honors the user's pick; save-time
        // sanity check is advisory only).
        if (overwriteAmount) {
            etAmount.setText(String.format(Locale.US, "%.2f", r.recommendedTotal));
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
            String toast;

            if (overwriteAmount) {
                toast = String.format(Locale.US,
                        "Total: $%.2f  (%.0f%%, P(total)=%.2f, source=%s)",
                        r.recommendedTotal, r.confidence * 100, r.candidateProbability, r.recommendedSource);
            } else if (r.recommendedTotal == picked.value) {
                toast = String.format(Locale.US,
                        "Re-picked: $%.2f  (verifier agrees, %.0f%%)",
                        picked.value, r.confidence * 100);
            } else {
                toast = String.format(Locale.US,
                        "Re-picked: $%.2f  (verifier recommends $%.2f, %.0f%%)",
                        picked.value, r.recommendedTotal, r.confidence * 100);
            }

            Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
        }
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

                // Show the verdict in the on-screen panel too, so the user
                // sees the math. Advisory only — do NOT overwrite the
                // entered amount: if the user re-picked or typed a value,
                // that's the amount that should be saved and reflected in
                // the budget. Suppress the "Re-picked" toast too — the
                // user didn't re-pick this number (the sanity check chose
                // it as the closest candidate); the sanity check's own
                // toast below already shows the comparison.
                renderVerifier(candidate, r, entered, /*autoPicked=*/false,
                        /*overwriteAmount=*/false, /*showToast=*/false);

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

        double pct;

        if (active.maxAmount > 0) {
            pct = Math.min(100, total * 100.0 / active.maxAmount);
        } else {
            pct = 0;
        }

        String msg = String.format(Locale.US,
                "Add $%.2f to '%s' budget? (%.0f%% used, %s cap)",
                total, active.name,
                pct,
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

        if (existingId >= 0) {
            r.id = existingId;
        } else {
            r.id = 0;
        }

        r.merchant = etMerchant.getText().toString().trim();

        r.amount = parseAmount();

        r.dateMillis = dateMillis;

        if (etNotes.getText() == null) {
            r.notes = null;
        } else {
            r.notes = etNotes.getText().toString().trim();
        }

        r.photoPath = photoPath;

        r.rawText = rawText;

        r.createdAt = System.currentTimeMillis();

        // Link to the budget the user picked in the prompt. Only set on insert;
        // updates preserve the existing budgetId.
        final Long budgetIdToSet = pendingBudgetId;

        String photoPathLog;

        if (r.photoPath == null) {
            photoPathLog = "null";
        } else {
            photoPathLog = r.photoPath;
        }

        Logger.i("Edit", "saveReceiptInternal: id=" + r.id + " merchant='" + r.merchant
                + "' amount=" + r.amount + " dateMillis=" + r.dateMillis
                + " photoPath=" + photoPathLog
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

                    if (budgetIdToSet != null) {
                        r.budgetId = budgetIdToSet;
                    } else {
                        r.budgetId = existing.budgetId;
                    }
                }

                dao.update(r);

                rowId = r.id;

                Logger.i("Edit", "Updated receipt id=" + r.id);
            } else {
                if (budgetIdToSet != null) {
                    r.budgetId = budgetIdToSet;
                }

                rowId = dao.insert(r);

                Logger.i("Edit", "Inserted receipt id=" + rowId + " budgetId=" + budgetIdToSet);
            }

            exec.mainThread().execute(() -> {
                String saved = getString(R.string.saved);

                String msg;

                if (budgetIdToSet != null) {
                    msg = saved + " · added to budget";
                } else {
                    msg = saved;
                }

                Toast.makeText(EditReceiptActivity.this, msg, Toast.LENGTH_SHORT).show();

                finish();
            });
        });
    }


    private static String trim(String s, int max) {
        if (s == null) return "";

        s = s.trim();

        if (s.length() <= max) {
            return s;
        }

        return s.substring(0, max - 1) + "…";
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
