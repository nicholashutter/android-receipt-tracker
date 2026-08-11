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
 * In-app viewer for the on-disk log file. Shows the last 16KB by default
 * (the full file is too large to render efficiently) and supports
 * refresh, share (via FileProvider), and clear.
 */
public class LogsActivity extends AppCompatActivity {

    private static final String TAG = "Logs";
    private static final String NO_LOG_FILE_LABEL = "(no log file yet)";
    private static final int TAIL_DISPLAY_BYTES = 16 * 1024;

    private TextView logView;
    private TextView metaView;
    private File logFile;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_logs);

        logView = findViewById(R.id.tv_log);
        metaView = findViewById(R.id.tv_meta);
        final MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        final MaterialButton btnShare = findViewById(R.id.btn_share);
        final MaterialButton btnClear = findViewById(R.id.btn_clear);

        logFile = Logger.logFile();

        btnRefresh.setOnClickListener(clickedView -> {
            Logger.i(TAG, "refresh clicked");
            render();
        });
        btnShare.setOnClickListener(clickedView -> shareLog());
        btnClear.setOnClickListener(clickedView -> {
            Logger.i(TAG, "clear clicked");
            Logger.clear();
            render();
        });

        render();
    }


    private void render() {
        if (logFile == null || !logFile.exists()) {
            logView.setText(NO_LOG_FILE_LABEL);
            metaView.setText("file: (none)");
            return;
        }

        final long sizeBytes = logFile.length();
        metaView.setText("file: " + logFile.getName() + "  size: "
                + Formatter.formatShortFileSize(this, sizeBytes));

        try {
            final String fullContents = Logger.readAll();
            final int fromIndex = Math.max(0, fullContents.length() - TAIL_DISPLAY_BYTES);
            final String displayBody;
            if (fromIndex == 0) {
                displayBody = fullContents;
            } else {
                displayBody = "...[truncated]...\n" + fullContents.substring(fromIndex);
            }
            logView.setText(displayBody);
        } catch (Exception readFailure) {
            logView.setText("(failed to read log: " + readFailure.getMessage() + ")");
        }
    }


    private void shareLog() {
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "No log file yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Use androidx.core.content.FileProvider via a content:// URI
            // generated on the fly. The cleanest portable path is to
            // fire an ACTION_SEND with the file path; most devices
            // will offer a file copy target. If the user wants a real
            // share sheet we can add FileProvider in a follow-up.
            final Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "ReceiptTracker log");
            final String tail = safeReadTail(8 * 1024);
            final String body = "Log file: " + logFile.getAbsolutePath() + "\n\n" + tail;
            sendIntent.putExtra(Intent.EXTRA_TEXT, body);
            startActivity(Intent.createChooser(sendIntent, "Share log"));
        } catch (Exception shareFailure) {
            Toast.makeText(this, "Share failed: " + shareFailure.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }


    private String safeReadTail(final int tailBytes) {
        try {
            final String fullContents = Logger.readAll();
            final int fromIndex = Math.max(0, fullContents.length() - tailBytes);
            return fullContents.substring(fromIndex);
        } catch (Exception readFailure) {
            return "(read failed: " + readFailure.getMessage() + ")";
        }
    }
}
