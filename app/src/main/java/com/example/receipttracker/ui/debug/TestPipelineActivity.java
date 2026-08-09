package com.example.receipttracker.ui.debug;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.example.receipttracker.R;
import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.data.Receipt;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.match.LinearLearner;
import com.example.receipttracker.match.PriceClassifier;
import com.example.receipttracker.match.TotalVerifier;
import com.example.receipttracker.ocr.DetectedNumber;
import com.example.receipttracker.ocr.ParsedReceipt;
import com.example.receipttracker.ocr.ReceiptImageStore;
import com.example.receipttracker.ocr.ReceiptOcr;
import com.example.receipttracker.ocr.ReceiptParser;
import com.example.receipttracker.ui.receipts.EditReceiptActivity;
import com.example.receipttracker.util.AppExecutors;
import com.google.android.material.button.MaterialButton;

import java.io.File;

/**
 * Debug-only activity that exercises the full receipt pipeline against a
 * caller-supplied image path. The path is read from Intent extra
 * {@code extra_image_path} (or {@code extra_image_uri} as a fallback).
 *
 * Flow:
 *   1. Open the image, downscale to 1600px
 *   2. Save a copy under app/receipts/ via ReceiptImageStore
 *   3. Run ML Kit OCR
 *   4. Run ReceiptParser
 *   5. Show all intermediate results on screen and in the log
 *
 * This lets us run a regression receipt through the pipeline from a
 * shell command: `adb shell am start -n .../TestPipelineActivity --es
 * extra_image_path /sdcard/Pictures/sample_receipt.jpg`
 */
public class TestPipelineActivity extends Activity {

    // Cached so the "Open in editor" button can re-use the last successful run.
    private String lastSavedPath;
    private String lastRawText;
    private String lastMerchant;
    private long lastDateMillis;
    private double lastAmount;
    private MaterialButton btnOpenEditor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("TEST PIPELINE");
        Logger.i("TestPipe", "onCreate");
        setContentView(R.layout.activity_test_pipeline);

        TextView tvResult = findViewById(R.id.tv_result);
        MaterialButton btnRun = findViewById(R.id.btn_run);
        btnOpenEditor = findViewById(R.id.btn_open_editor);

        String pathExtra = getIntent().getStringExtra("extra_image_path");
        Uri uriExtra = getIntent().getParcelableExtra("extra_image_uri");
        Logger.i("TestPipe", "path extra: " + pathExtra + ", uri extra: " + uriExtra);

        btnRun.setOnClickListener(v -> runPipeline(pathExtra, uriExtra, tvResult));
        btnOpenEditor.setOnClickListener(v -> openInEditor());
        runPipeline(pathExtra, uriExtra, tvResult);
    }

    private void openInEditor() {
        if (lastSavedPath == null) {
            Toast.makeText(this, "Run the pipeline first", Toast.LENGTH_SHORT).show();
            return;
        }
        // Don't pre-insert the receipt. The editor is a new-receipt session
        // and will insert on save, with auto-pick firing on the OCR text
        // we pass in. (Pre-inserting made the editor think it was editing
        // an existing row, which skipped the auto-pick path.)
        runOnUiThread(() -> {
            Intent i = new Intent(this, EditReceiptActivity.class);
            i.putExtra(EditReceiptActivity.EXTRA_PHOTO_PATH, lastSavedPath);
            i.putExtra(EditReceiptActivity.EXTRA_RAW_TEXT, lastRawText);
            i.putExtra(EditReceiptActivity.EXTRA_MERCHANT, lastMerchant);
            i.putExtra(EditReceiptActivity.EXTRA_AMOUNT, lastAmount);
            i.putExtra(EditReceiptActivity.EXTRA_DATE_MILLIS, lastDateMillis);
            startActivity(i);
        });
    }

    private void runPipeline(String pathExtra, Uri uriExtra, TextView tvResult) {
        tvResult.setText("Running pipeline...");
        AppExecutors.get().diskIO().execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                File saved = null;
                Bitmap bmp = null;
                if (pathExtra != null) {
                    // Scoped storage: we can't read /sdcard/... via raw File API on
                    // API 29+, so copy the file to our private dir first.
                    File src = new File(pathExtra);
                    Logger.i("TestPipe", "Reading from path: " + src.getAbsolutePath()
                            + " exists=" + src.exists() + " size=" + src.length());
                    File copied = new File(getFilesDir(), "pipeline_in.jpg");
                    try (java.io.InputStream in = new java.io.FileInputStream(src);
                         java.io.OutputStream out = new java.io.FileOutputStream(copied)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    Logger.i("TestPipe", "Copied to " + copied.getAbsolutePath()
                            + " size=" + copied.length());
                    bmp = ReceiptImageStore.decodeSampled(copied.getAbsolutePath(), 1600, 1600);
                    if (bmp == null) throw new IllegalStateException("decodeSampled returned null");
                    saved = ReceiptImageStore.saveBitmap(this, bmp);
                } else if (uriExtra != null) {
                    Logger.i("TestPipe", "Importing from URI: " + uriExtra);
                    saved = ReceiptImageStore.importFromUri(this, uriExtra);
                    bmp = ReceiptImageStore.decodeSampled(saved.getAbsolutePath(), 1600, 1600);
                } else {
                    throw new IllegalStateException("No image path or URI supplied");
                }
                sb.append("Image: ").append(bmp.getWidth()).append("x").append(bmp.getHeight())
                        .append("\nSaved: ").append(saved.getAbsolutePath())
                        .append("  size=").append(saved.length()).append(" bytes\n\n");

                Logger.i("TestPipe", "Running OCR on " + bmp.getWidth() + "x" + bmp.getHeight());
                String rawText = ReceiptOcr.recognizeText(bmp);
                int rawTextLen;
                if (rawText == null) {
                    rawTextLen = 0;
                } else {
                    rawTextLen = rawText.length();
                }
                Logger.i("TestPipe", "OCR raw text length: " + rawTextLen);
                String rawTextDisplay;
                if (rawText == null) {
                    rawTextDisplay = "(null)";
                } else {
                    rawTextDisplay = rawText;
                }
                sb.append("=== RAW OCR TEXT ===\n").append(rawTextDisplay)
                        .append("\n\n");

                Logger.i("TestPipe", "Running parser");
                ParsedReceipt parsed = ReceiptParser.parse(rawText);
                Logger.i("TestPipe", "Parser result: " + parsed);
                sb.append("=== PARSED ===\n");
                sb.append("merchant: ").append(parsed.merchant).append("\n");
                sb.append("dateMillis: ").append(parsed.dateMillis).append("\n");
                sb.append("amount: ").append(parsed.amount).append("\n\n");

                // Cache for the "Open in editor" button.
                lastSavedPath = saved.getAbsolutePath();
                lastRawText = rawText;
                lastMerchant = parsed.merchant;
                lastDateMillis = parsed.dateMillis;
                lastAmount = parsed.amount;

                // === Now exercise the TotalVerifier against every detected number ===
                Logger.i("TestPipe", "Running TotalVerifier for each detected number");
                java.util.List<DetectedNumber> numbers = ReceiptParser.extractAllNumbers(rawText);
                sb.append("=== DETECTED NUMBERS (").append(numbers.size()).append(") ===\n");
                for (DetectedNumber n : numbers) {
                    sb.append(String.format(java.util.Locale.US,
                            "  $%.2f  line=%d  keyword=%s  \"%s\"\n",
                            n.value, n.lineIndex, n.keyword, n.line.trim()));
                }

                // === STAGE 1: PriceClassifier ===
                sb.append("\n=== STAGE 1: PRICE CLASSIFIER (trained weights) ===\n");
                double[] pw = PriceClassifier.getWeights();
                for (int i = 0; i < PriceClassifier.FEATURE_NAMES.length; i++) {
                    sb.append(String.format(java.util.Locale.US,
                            "  %-22s = %+.3f\n", PriceClassifier.FEATURE_NAMES[i], pw[i]));
                }
                sb.append(String.format(java.util.Locale.US,
                        "  %-22s = %+.3f\n", "bias", PriceClassifier.getBias()));
                sb.append("\n--- per-number P(is price) ---\n");
                for (DetectedNumber n : numbers) {
                    double[] f = PriceClassifier.extractFeatures(n);
                    double p = PriceClassifier.predictProbability(f);
                    String priceLabel;
                    if (p >= PriceClassifier.PRICE_THRESHOLD) {
                        priceLabel = "[PRICE]";
                    } else {
                        priceLabel = "[drop] ";
                    }
                    sb.append(String.format(java.util.Locale.US,
                            "  $%-7.2f  P(isPrice)=%.3f  %s  line=%d  \"%s\"\n",
                            n.value, p,
                            priceLabel,
                            n.lineIndex, n.line.trim()));
                }

                // === STAGE 2: LinearLearner (TotalLearner) ===
                sb.append("\n=== STAGE 2: LINEAR LEARNER (trained weights) ===\n");
                double[] w = LinearLearner.getWeights();
                for (int i = 0; i < LinearLearner.FEATURE_NAMES.length; i++) {
                    sb.append(String.format(java.util.Locale.US,
                            "  %-22s = %+.3f\n", LinearLearner.FEATURE_NAMES[i], w[i]));
                }
                sb.append(String.format(java.util.Locale.US,
                        "  %-22s = %+.3f\n", "bias", LinearLearner.getBias()));

                // === Final verdict for each candidate ===
                sb.append("\n=== VERIFIER (for each candidate) ===\n");
                for (DetectedNumber n : numbers) {
                    TotalVerifier.Result r = TotalVerifier.verify(n.value, numbers);
                    String adjustedLabel;
                    if (r.wasAdjusted) {
                        adjustedLabel = "  [adjusted]";
                    } else {
                        adjustedLabel = "";
                    }
                    sb.append(String.format(java.util.Locale.US,
                            "  $%.2f  ->  total=$%.2f  conf=%.0f%%%s  P(price)=%.2f  P(total)=%.2f  P(best-alt)=%.2f\n",
                            n.value, r.total, r.confidence * 100,
                            adjustedLabel,
                            r.priceProbability, r.candidateProbability, r.bestAlternativeProbability));
                }

            } catch (Exception e) {
                Logger.e("TestPipe", "Pipeline failed", e);
                sb.append("FAILED: ").append(e.getMessage());
            }
            final String result = sb.toString();
            runOnUiThread(() -> {
                tvResult.setText(result);
                tvResult.setVisibility(View.VISIBLE);
                if (lastSavedPath != null && btnOpenEditor != null) {
                    btnOpenEditor.setEnabled(true);
                }
                Toast.makeText(this, "Done — see logs", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
