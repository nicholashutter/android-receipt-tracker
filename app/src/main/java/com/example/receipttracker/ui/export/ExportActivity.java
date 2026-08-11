package com.example.receipttracker.ui.export;


import android.content.Intent;

import android.net.Uri;

import android.os.Bundle;

import android.widget.TextView;

import android.widget.Toast;


import androidx.activity.result.ActivityResultLauncher;

import androidx.activity.result.contract.ActivityResultContracts;

import androidx.appcompat.app.AppCompatActivity;

import androidx.documentfile.provider.DocumentFile;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.Receipt;

import com.example.receipttracker.export.ReceiptExporter;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.util.AppExecutors;

import com.google.android.material.button.MaterialButton;


import java.util.List;


/**
 * Bulk-export of every receipt (or just the newest) into a SAF tree the
 * user picks. Each receipt becomes one {@code .jpg} + one {@code .json}
 * pair via {@link ReceiptExporter}.
 */
public class ExportActivity extends AppCompatActivity {

    private static final String TAG = "Export";
    private static final String MSG_NO_FOLDER = "Pick a folder first";
    private static final String MSG_NO_RECEIPTS = "No receipts to export";
    private static final String MSG_FOLDER_UNAVAILABLE = "Folder not accessible";
    private static final String MSG_EXPORTING = "Exporting...";
    private static final String MSG_EXPORTING_NEWEST = "Exporting newest...";
    private static final String MSG_NO_FOLDER_SELECTED = "No folder selected";


    private TextView folderView;
    private TextView statusView;
    private MaterialButton pickButton;
    private MaterialButton exportButton;
    private MaterialButton exportAllButton;

    // MUTABLE: set when the user picks a tree URI.
    private Uri treeUri;

    private AppDatabase database;
    private final AppExecutors executors = AppExecutors.get();


    private final ActivityResultLauncher<Uri> pickTree =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), pickedUri -> {
                if (pickedUri == null) {
                    Logger.w(TAG, "Folder picker returned null");
                    return;
                }
                Logger.i(TAG, "User picked folder: " + pickedUri);
                // Persist permission across reboots.
                getContentResolver().takePersistableUriPermission(
                        pickedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                treeUri = pickedUri;
                renderFolder();
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        folderView = findViewById(R.id.tv_folder);
        statusView = findViewById(R.id.tv_status);
        pickButton = findViewById(R.id.btn_pick);
        exportButton = findViewById(R.id.btn_export);
        exportAllButton = findViewById(R.id.btn_export_all);

        database = AppDatabase.get(this);

        pickButton.setOnClickListener(clickedView -> pickTree.launch(null));
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(clickedView -> exportNewest());
        exportAllButton.setOnClickListener(clickedView -> exportAll());
    }


    private void renderFolder() {
        if (treeUri == null) {
            folderView.setText(MSG_NO_FOLDER_SELECTED);
            exportButton.setEnabled(false);
            return;
        }
        final DocumentFile tree = DocumentFile.fromTreeUri(this, treeUri);
        final String folderLabel;
        if (tree == null) {
            folderLabel = treeUri.toString();
        } else {
            folderLabel = tree.getName();
        }
        folderView.setText(folderLabel);
        exportButton.setEnabled(true);
    }


    private void exportAll() {
        if (treeUri == null) {
            Toast.makeText(this, MSG_NO_FOLDER, Toast.LENGTH_SHORT).show();
            return;
        }
        Logger.section("EXPORT ALL");
        Logger.i(TAG, "exportAll: treeUri=" + treeUri);
        exportAllButton.setEnabled(false);
        statusView.setText(MSG_EXPORTING);

        final Uri targetUri = treeUri;
        executors.diskIO().execute(() -> {
            final List<Receipt> allReceipts = database.receiptDao().getAll();
            Logger.i(TAG, "exportAll: " + allReceipts.size() + " receipts to export");

            final DocumentFile tree = DocumentFile.fromTreeUri(ExportActivity.this, targetUri);
            final int[] counts;
            if (tree == null) {
                Logger.e(TAG, "exportAll: fromTreeUri returned null");
                counts = new int[]{0, allReceipts.size()};
            } else {
                counts = exportEachReceipt(allReceipts, tree);
            }

            executors.mainThread().execute(() -> finishExportAll(counts));
        });
    }


    /** Returns {@code [okCount, failCount]} after exporting every receipt. */
    private int[] exportEachReceipt(List<Receipt> receipts, DocumentFile tree) {
        int okCount = 0;
        int failCount = 0;
        for (final Receipt receipt : receipts) {
            try {
                ReceiptExporter.export(ExportActivity.this, tree, receipt);
                okCount++;
            } catch (Exception exportFailure) {
                failCount++;
                Logger.e(TAG, "Failed to export receipt id=" + receipt.id, exportFailure);
            }
        }
        return new int[]{okCount, failCount};
    }


    private void finishExportAll(int[] counts) {
        exportAllButton.setEnabled(true);
        final int ok = counts[0];
        final int fail = counts[1];
        final String statusLine = "Exported " + ok + " receipt(s). Failed: " + fail;
        statusView.setText(statusLine);
        Toast.makeText(this, "Exported " + ok + " receipt(s)", Toast.LENGTH_LONG).show();
    }


    private void exportNewest() {
        if (treeUri == null) {
            Toast.makeText(this, MSG_NO_FOLDER, Toast.LENGTH_SHORT).show();
            return;
        }
        Logger.i(TAG, "exportNewest clicked");
        exportButton.setEnabled(false);
        statusView.setText(MSG_EXPORTING_NEWEST);

        final Uri targetUri = treeUri;
        executors.diskIO().execute(() -> {
            final Receipt newest = newestReceipt();
            final String resultMessage;
            if (newest == null) {
                resultMessage = MSG_NO_RECEIPTS;
            } else {
                resultMessage = exportSingleReceipt(newest, targetUri);
            }
            executors.mainThread().execute(() -> finishExportNewest(resultMessage));
        });
    }


    /** Returns the user-visible message describing what happened to the single receipt. */
    private String exportSingleReceipt(Receipt receipt, Uri targetUri) {
        final DocumentFile tree = DocumentFile.fromTreeUri(ExportActivity.this, targetUri);
        if (tree == null) {
            return MSG_FOLDER_UNAVAILABLE;
        }
        try {
            ReceiptExporter.export(ExportActivity.this, tree, receipt);
            final String merchantLabel;
            if (receipt.merchant == null) {
                merchantLabel = "receipt";
            } else {
                merchantLabel = receipt.merchant;
            }
            Logger.i(TAG, "exportNewest: wrote receipt id=" + receipt.id);
            return "Exported: " + merchantLabel;
        } catch (Exception exportFailure) {
            Logger.e(TAG, "exportNewest: failed for id=" + receipt.id, exportFailure);
            return "Failed: " + exportFailure.getMessage();
        }
    }


    private void finishExportNewest(String resultMessage) {
        exportButton.setEnabled(true);
        statusView.setText(resultMessage);
        Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show();
    }


    private Receipt newestReceipt() {
        final List<Receipt> allReceipts = database.receiptDao().getAll();
        if (allReceipts.isEmpty()) return null;
        // already ordered by dateMillis DESC
        return allReceipts.get(0);
    }
}
