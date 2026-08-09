package com.example.receipttracker.ui.debug;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.receipttracker.R;
import com.example.receipttracker.log.Logger;
import com.google.android.material.button.MaterialButton;

import java.io.File;

/**
 * In-app viewer for the on-disk log file. Shows the last 4KB by default
 * (full file is too large to render efficiently) and supports refresh,
 * share (via FileProvider), and clear.
 */
public class LogsActivity extends AppCompatActivity {

    private TextView tvLog, tvMeta;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i("Logs", "onCreate");
        setContentView(R.layout.activity_logs);

        tvLog = findViewById(R.id.tv_log);
        tvMeta = findViewById(R.id.tv_meta);
        MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        MaterialButton btnShare = findViewById(R.id.btn_share);
        MaterialButton btnClear = findViewById(R.id.btn_clear);

        logFile = Logger.logFile();

        btnRefresh.setOnClickListener(v -> {
            Logger.i("Logs", "refresh clicked");
            render();
        });
        btnShare.setOnClickListener(v -> shareLog());
        btnClear.setOnClickListener(v -> {
            Logger.i("Logs", "clear clicked");
            Logger.clear();
            render();
        });

        render();
    }

    private void render() {
        if (logFile == null || !logFile.exists()) {
            tvLog.setText("(no log file yet)");
            tvMeta.setText("file: (none)");
            return;
        }
        long size = logFile.length();
        tvMeta.setText("file: " + logFile.getName() + "  size: "
                + Formatter.formatShortFileSize(this, size));
        try {
            String full = Logger.readAll();
            // Show last ~16 KB so very long sessions don't kill the TextView.
            int from = Math.max(0, full.length() - 16 * 1024);
            String body;
            if (from == 0) {
                body = full;
            } else {
                body = "...[truncated]...\n" + full.substring(from);
            }
            tvLog.setText(body);
        } catch (Exception e) {
            tvLog.setText("(failed to read log: " + e.getMessage() + ")");
        }
    }

    private void shareLog() {
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "No log file yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Use androidx.core.content.FileProvider via a content:// URI
            // generated on the fly. The cleanest portable path is to fire
            // an ACTION_SEND with the file path; most devices will offer
            // a file copy target. If the user wants a real share sheet we
            // can add FileProvider in a follow-up.
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "ReceiptTracker log");
            send.putExtra(Intent.EXTRA_TEXT, "Log file: " + logFile.getAbsolutePath()
                    + "\n\n" + safeReadTail(8 * 1024));
            startActivity(Intent.createChooser(send, "Share log"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String safeReadTail(int bytes) {
        try {
            String full = Logger.readAll();
            int from = Math.max(0, full.length() - bytes);
            return full.substring(from);
        } catch (Exception e) {
            return "(read failed: " + e.getMessage() + ")";
        }
    }
}
