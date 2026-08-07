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

public class ExportActivity extends AppCompatActivity {

    private TextView tvFolder, tvStatus;
    private MaterialButton btnPick, btnExport, btnExportAll;

    private Uri treeUri;
    private AppDatabase db;
    private final AppExecutors exec = AppExecutors.get();

    private final ActivityResultLauncher<Uri> pickTree =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    Logger.i("Export", "User picked folder: " + uri);
                    // Persist permission across reboots
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    treeUri = uri;
                    renderFolder();
                } else {
                    Logger.w("Export", "Folder picker returned null");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        tvFolder = findViewById(R.id.tv_folder);
        tvStatus = findViewById(R.id.tv_status);
        btnPick = findViewById(R.id.btn_pick);
        btnExport = findViewById(R.id.btn_export);
        btnExportAll = findViewById(R.id.btn_export_all);

        db = AppDatabase.get(this);

        btnPick.setOnClickListener(v -> pickTree.launch(null));
        btnExport.setEnabled(false);
        btnExport.setOnClickListener(v -> exportNewest());
        btnExportAll.setOnClickListener(v -> exportAll());
    }

    private void renderFolder() {
        if (treeUri == null) {
            tvFolder.setText("No folder selected");
            btnExport.setEnabled(false);
            return;
        }
        DocumentFile tree = DocumentFile.fromTreeUri(this, treeUri);
        tvFolder.setText(tree == null ? treeUri.toString() : tree.getName());
        btnExport.setEnabled(true);
    }

    private void exportAll() {
        if (treeUri == null) {
            Toast.makeText(this, "Pick a folder first", Toast.LENGTH_SHORT).show();
            return;
        }
        Logger.section("EXPORT ALL");
        Logger.i("Export", "exportAll: treeUri=" + treeUri);
        btnExportAll.setEnabled(false);
        tvStatus.setText("Exporting...");
        final Uri uri = treeUri;
        exec.diskIO().execute(() -> {
            List<Receipt> all = db.receiptDao().getAll();
            Logger.i("Export", "exportAll: " + all.size() + " receipts to export");
            int ok = 0, fail = 0;
            DocumentFile tree = DocumentFile.fromTreeUri(ExportActivity.this, uri);
            if (tree == null) {
                Logger.e("Export", "exportAll: fromTreeUri returned null");
                int[] result = new int[]{0, all.size()};
                exec.mainThread().execute(() -> finishExportAll(result));
                return;
            }
            for (Receipt r : all) {
                try {
                    ReceiptExporter.export(ExportActivity.this, tree, r);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    Logger.e("Export", "Failed to export receipt id=" + r.id, e);
                }
            }
            int[] result = new int[]{ok, fail};
            exec.mainThread().execute(() -> finishExportAll(result));
        });
    }

    private void finishExportAll(int[] counts) {
        btnExportAll.setEnabled(true);
        tvStatus.setText("Exported " + counts[0] + " receipt(s). Failed: " + counts[1]);
        Toast.makeText(ExportActivity.this,
                "Exported " + counts[0] + " receipt(s)", Toast.LENGTH_LONG).show();
    }

    private void exportNewest() {
        if (treeUri == null) {
            Toast.makeText(this, "Pick a folder first", Toast.LENGTH_SHORT).show();
            return;
        }
        Logger.i("Export", "exportNewest clicked");
        btnExport.setEnabled(false);
        tvStatus.setText("Exporting newest...");
        final Uri uri = treeUri;
        final String[] msg = new String[1];
        exec.diskIO().execute(() -> {
            Receipt r = newestReceipt();
            if (r == null) {
                msg[0] = "No receipts to export";
            } else {
                DocumentFile tree = DocumentFile.fromTreeUri(ExportActivity.this, uri);
                if (tree == null) {
                    msg[0] = "Folder not accessible";
                } else {
                    try {
                        ReceiptExporter.export(ExportActivity.this, tree, r);
                        msg[0] = "Exported: " + (r.merchant == null ? "receipt" : r.merchant);
                        Logger.i("Export", "exportNewest: wrote receipt id=" + r.id);
                    } catch (Exception e) {
                        msg[0] = "Failed: " + e.getMessage();
                        Logger.e("Export", "exportNewest: failed for id=" + r.id, e);
                    }
                }
            }
            final String result = msg[0];
            exec.mainThread().execute(() -> finishExportNewest(result));
        });
    }

    private void finishExportNewest(String msg) {
        btnExport.setEnabled(true);
        tvStatus.setText(msg);
        Toast.makeText(ExportActivity.this, msg, Toast.LENGTH_LONG).show();
    }

    private Receipt newestReceipt() {
        List<Receipt> all = db.receiptDao().getAll();
        if (all.isEmpty()) return null;
        // already ordered by dateMillis DESC
        return all.get(0);
    }
}
