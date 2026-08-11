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

import java.util.List;

import java.util.Locale;


/**
 * Debug-only activity that exercises the full receipt pipeline against
 * a caller-supplied image path. The path is read from Intent extra
 * {@code extra_image_path} (or {@code extra_image_uri} as a fallback).
 *
 * <p>Flow: open the image → downscale to 1600px → save under
 * {@code app/receipts/} via {@link ReceiptImageStore} → run ML Kit
 * OCR → run {@link ReceiptParser} → show all intermediate results
 * on screen and in the log.</p>
 *
 * <p>Lets us run a regression receipt through the pipeline from a
 * shell command: {@code adb shell am start -n .../TestPipelineActivity
 * --es extra_image_path /sdcard/Pictures/sample_receipt.jpg}</p>
 */
public class TestPipelineActivity extends Activity {

    private static final String TAG = "TestPipe";
    private static final String EXTRA_IMAGE_PATH = "extra_image_path";
    private static final String EXTRA_IMAGE_URI = "extra_image_uri";
    private static final int PIPELINE_INPUT_DIM = 1600;
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final String COPY_TARGET_FILENAME = "pipeline_in.jpg";
    private static final String RUNNING_LABEL = "Running pipeline...";
    private static final String PIPELINE_DONE_TOAST = "Done — see logs";
    private static final String RUN_PIPELINE_FIRST = "Run the pipeline first";
    private static final String NULL_RAW_TEXT_LABEL = "(null)";
    private static final String ADJUSTED_LABEL = "  [adjusted]";
    private static final String PRICE_LABEL = "[PRICE]";
    private static final String DROP_LABEL = "[drop] ";
    private static final String BIAS_LABEL = "bias";


    // Cached so the "Open in editor" button can re-use the last
    // successful run. All five are MUTABLE.
    private String lastSavedPath;
    private String lastRawText;
    private String lastMerchant;
    private Long lastDateMillis;
    private Double lastAmount;
    private MaterialButton openEditorButton;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("TEST PIPELINE");
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_test_pipeline);

        final TextView resultView = findViewById(R.id.tv_result);
        final MaterialButton runButton = findViewById(R.id.btn_run);
        openEditorButton = findViewById(R.id.btn_open_editor);

        final String pathExtra = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        final Uri uriExtra = getIntent().getParcelableExtra(EXTRA_IMAGE_URI);
        Logger.i(TAG, "path extra: " + pathExtra + ", uri extra: " + uriExtra);

        runButton.setOnClickListener(clickedView -> runPipeline(pathExtra, uriExtra, resultView));
        openEditorButton.setOnClickListener(clickedView -> openInEditor());

        runPipeline(pathExtra, uriExtra, resultView);
    }


    private void openInEditor() {
        if (lastSavedPath == null) {
            Toast.makeText(this, RUN_PIPELINE_FIRST, Toast.LENGTH_SHORT).show();
            return;
        }
        // Don't pre-insert the receipt. The editor is a new-receipt
        // session and will insert on save, with auto-pick firing on
        // the OCR text we pass in. (Pre-inserting made the editor
        // think it was editing an existing row, which skipped the
        // auto-pick path.)
        runOnUiThread(() -> {
            final Intent editorIntent = new Intent(this, EditReceiptActivity.class);
            editorIntent.putExtra(EditReceiptActivity.EXTRA_PHOTO_PATH, lastSavedPath);
            editorIntent.putExtra(EditReceiptActivity.EXTRA_RAW_TEXT, lastRawText);
            editorIntent.putExtra(EditReceiptActivity.EXTRA_MERCHANT, lastMerchant);
            editorIntent.putExtra(EditReceiptActivity.EXTRA_AMOUNT, lastAmount);
            editorIntent.putExtra(EditReceiptActivity.EXTRA_DATE_MILLIS, lastDateMillis);
            startActivity(editorIntent);
        });
    }


    private void runPipeline(String pathExtra, Uri uriExtra, TextView resultView) {
        resultView.setText(RUNNING_LABEL);
        AppExecutors.get().diskIO().execute(() -> {
            final StringBuilder logBuilder = new StringBuilder();
            try {
                final LoadedImage loaded = loadImage(pathExtra, uriExtra);
                appendImageHeader(logBuilder, loaded);
                final String rawText = runOcrAndReport(loaded.bitmap, logBuilder);
                final ParsedReceipt parsed = runParserAndReport(rawText, logBuilder);
                cacheLastRunResults(loaded.savedFile, rawText, parsed);
                appendVerifierAndClassifierSections(loaded.bitmap, rawText, logBuilder);
            } catch (Exception pipelineFailure) {
                Logger.e(TAG, "Pipeline failed", pipelineFailure);
                logBuilder.append("FAILED: ").append(pipelineFailure.getMessage());
            }

            final String finalResult = logBuilder.toString();
            runOnUiThread(() -> publishResult(resultView, finalResult));
        });
    }


    /** Loaded image data: the in-memory bitmap plus the on-disk copy we saved. */
    private static final class LoadedImage {
        final Bitmap bitmap;
        final File savedFile;

        LoadedImage(Bitmap bitmap, File savedFile) {
            this.bitmap = bitmap;
            this.savedFile = savedFile;
        }
    }


    private LoadedImage loadImage(String pathExtra, Uri uriExtra) throws Exception {
        if (pathExtra != null) {
            return loadImageFromPath(pathExtra);
        }
        if (uriExtra != null) {
            return loadImageFromUri(uriExtra);
        }
        throw new IllegalStateException("No image path or URI supplied");
    }


    private LoadedImage loadImageFromPath(String pathExtra) throws Exception {
        // Scoped storage: we can't read /sdcard/... via raw File API
        // on API 29+, so copy the file to our private dir first.
        final File sourceFile = new File(pathExtra);
        Logger.i(TAG, "Reading from path: " + sourceFile.getAbsolutePath()
                + " exists=" + sourceFile.exists() + " size=" + sourceFile.length());

        final File copiedFile = new File(getFilesDir(), COPY_TARGET_FILENAME);
        try (java.io.InputStream input = new java.io.FileInputStream(sourceFile);
             java.io.OutputStream output = new java.io.FileOutputStream(copiedFile)) {
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) > 0) {
                output.write(buffer, 0, bytesRead);
            }
        }
        Logger.i(TAG, "Copied to " + copiedFile.getAbsolutePath()
                + " size=" + copiedFile.length());

        final Bitmap decoded = ReceiptImageStore.decodeSampled(
                copiedFile.getAbsolutePath(), PIPELINE_INPUT_DIM, PIPELINE_INPUT_DIM);
        if (decoded == null) {
            throw new IllegalStateException("decodeSampled returned null");
        }
        final File saved = ReceiptImageStore.saveBitmap(this, decoded);
        return new LoadedImage(decoded, saved);
    }


    private LoadedImage loadImageFromUri(Uri uriExtra) throws Exception {
        Logger.i(TAG, "Importing from URI: " + uriExtra);
        final File saved = ReceiptImageStore.importFromUri(this, uriExtra);
        final Bitmap decoded = ReceiptImageStore.decodeSampled(
                saved.getAbsolutePath(), PIPELINE_INPUT_DIM, PIPELINE_INPUT_DIM);
        return new LoadedImage(decoded, saved);
    }


    private void appendImageHeader(StringBuilder logBuilder, LoadedImage loaded) {
        final Bitmap bitmap = loaded.bitmap;
        final File saved = loaded.savedFile;
        logBuilder.append("Image: ").append(bitmap.getWidth()).append("x").append(bitmap.getHeight())
                .append("\nSaved: ").append(saved.getAbsolutePath())
                .append("  size=").append(saved.length()).append(" bytes\n\n");
        Logger.i(TAG, "Running OCR on " + bitmap.getWidth() + "x" + bitmap.getHeight());
    }


    private String runOcrAndReport(Bitmap bitmap, StringBuilder logBuilder) {
        final String rawText = ReceiptOcr.recognizeText(bitmap);
        final int rawTextLength;
        if (rawText == null) {
            rawTextLength = 0;
        } else {
            rawTextLength = rawText.length();
        }
        Logger.i(TAG, "OCR raw text length: " + rawTextLength);

        final String displayText;
        if (rawText == null) {
            displayText = NULL_RAW_TEXT_LABEL;
        } else {
            displayText = rawText;
        }
        logBuilder.append("=== RAW OCR TEXT ===\n").append(displayText).append("\n\n");
        return rawText;
    }


    private ParsedReceipt runParserAndReport(String rawText, StringBuilder logBuilder) {
        Logger.i(TAG, "Running parser");
        final ParsedReceipt parsed = ReceiptParser.parse(rawText);
        Logger.i(TAG, "Parser result: " + parsed);

        logBuilder.append("=== PARSED ===\n");
        logBuilder.append("merchant: ").append(parsed.merchant).append("\n");
        logBuilder.append("dateMillis: ").append(parsed.dateMillis).append("\n");
        logBuilder.append("amount: ").append(parsed.amount).append("\n\n");
        return parsed;
    }


    private void cacheLastRunResults(File savedFile, String rawText, ParsedReceipt parsed) {
        lastSavedPath = savedFile.getAbsolutePath();
        lastRawText = rawText;
        lastMerchant = parsed.merchant;
        lastDateMillis = parsed.dateMillis;
        lastAmount = parsed.amount;
    }


    private void appendVerifierAndClassifierSections(Bitmap bitmap, String rawText, StringBuilder logBuilder) {
        Logger.i(TAG, "Running TotalVerifier for each detected number");
        final List<DetectedNumber> detectedNumbers = ReceiptParser.extractAllNumbers(rawText);
        appendDetectedNumbers(logBuilder, detectedNumbers);

        appendPriceClassifierWeights(logBuilder);
        appendPriceClassifierPerNumber(logBuilder, detectedNumbers);

        appendLinearLearnerWeights(logBuilder);

        appendVerifierPerCandidate(logBuilder, detectedNumbers);
    }


    private void appendDetectedNumbers(StringBuilder logBuilder, List<DetectedNumber> detectedNumbers) {
        logBuilder.append("=== DETECTED NUMBERS (").append(detectedNumbers.size()).append(") ===\n");
        for (final DetectedNumber detectedNumber : detectedNumbers) {
            logBuilder.append(String.format(Locale.US,
                    "  $%.2f  line=%d  keyword=%s  \"%s\"\n",
                    detectedNumber.value, detectedNumber.lineIndex,
                    detectedNumber.keyword, detectedNumber.line.trim()));
        }
    }


    private void appendPriceClassifierWeights(StringBuilder logBuilder) {
        logBuilder.append("\n=== STAGE 1: PRICE CLASSIFIER (trained weights) ===\n");
        final double[] priceWeights = PriceClassifier.getWeights();
        for (int i = 0; i < PriceClassifier.FEATURE_NAMES.length; i++) {
            logBuilder.append(String.format(Locale.US,
                    "  %-22s = %+.3f\n", PriceClassifier.FEATURE_NAMES[i], priceWeights[i]));
        }
        logBuilder.append(String.format(Locale.US,
                "  %-22s = %+.3f\n", BIAS_LABEL, PriceClassifier.getBias()));
    }


    private void appendPriceClassifierPerNumber(StringBuilder logBuilder, List<DetectedNumber> detectedNumbers) {
        logBuilder.append("\n--- per-number P(is price) ---\n");
        for (final DetectedNumber detectedNumber : detectedNumbers) {
            final double[] features = PriceClassifier.extractFeatures(detectedNumber);
            final double probability = PriceClassifier.predictProbability(features);

            final String priceLabel;
            if (probability >= PriceClassifier.PRICE_THRESHOLD) {
                priceLabel = PRICE_LABEL;
            } else {
                priceLabel = DROP_LABEL;
            }

            logBuilder.append(String.format(Locale.US,
                    "  $%-7.2f  P(isPrice)=%.3f  %s  line=%d  \"%s\"\n",
                    detectedNumber.value, probability, priceLabel,
                    detectedNumber.lineIndex, detectedNumber.line.trim()));
        }
    }


    private void appendLinearLearnerWeights(StringBuilder logBuilder) {
        logBuilder.append("\n=== STAGE 2: LINEAR LEARNER (trained weights) ===\n");
        final double[] learnerWeights = LinearLearner.getWeights();
        for (int i = 0; i < LinearLearner.FEATURE_NAMES.length; i++) {
            logBuilder.append(String.format(Locale.US,
                    "  %-22s = %+.3f\n", LinearLearner.FEATURE_NAMES[i], learnerWeights[i]));
        }
        logBuilder.append(String.format(Locale.US,
                "  %-22s = %+.3f\n", BIAS_LABEL, LinearLearner.getBias()));
    }


    private void appendVerifierPerCandidate(StringBuilder logBuilder, List<DetectedNumber> detectedNumbers) {
        logBuilder.append("\n=== VERIFIER (for each candidate) ===\n");
        for (final DetectedNumber detectedNumber : detectedNumbers) {
            final TotalVerifier.Result verdict = TotalVerifier.verify(detectedNumber.value, detectedNumbers);

            final String adjustedLabel;
            if (verdict.wasAdjusted) {
                adjustedLabel = ADJUSTED_LABEL;
            } else {
                adjustedLabel = "";
            }

            logBuilder.append(String.format(Locale.US,
                    "  $%.2f  ->  total=$%.2f  conf=%.0f%%%s  P(price)=%.2f  P(total)=%.2f  P(best-alt)=%.2f\n",
                    detectedNumber.value, verdict.total, verdict.confidence * 100,
                    adjustedLabel,
                    verdict.priceProbability, verdict.candidateProbability,
                    verdict.bestAlternativeProbability));
        }
    }


    private void publishResult(TextView resultView, String finalResult) {
        resultView.setText(finalResult);
        resultView.setVisibility(View.VISIBLE);
        if (lastSavedPath != null && openEditorButton != null) {
            openEditorButton.setEnabled(true);
        }
        Toast.makeText(this, PIPELINE_DONE_TOAST, Toast.LENGTH_SHORT).show();
    }
}
