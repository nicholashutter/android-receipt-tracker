package com.example.receipttracker.ui.receipts;


import android.content.Intent;

import android.os.Bundle;

import android.view.View;


import androidx.appcompat.app.AppCompatActivity;


import com.example.receipttracker.R;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ui.scan.ScanReceiptActivity;


/**
 * Wrapper entry point for "create a new receipt". Three big action cards:
 *
 * <ul>
 *   <li><b>Type the details</b> — opens {@link EditReceiptActivity} with no
 *       extras. The editor starts empty; the user types everything by hand.
 *       Use this for hand receipts, online orders, or any case where there's
 *       no photo and OCR is impossible.</li>
 *   <li><b>Take a photo</b> — opens {@link ScanReceiptActivity} in camera
 *       mode. The camera-greedy scan flow does OCR on the capture and
 *       forwards to the editor with whatever it could read.</li>
 *   <li><b>Pick from gallery</b> — opens {@link ScanReceiptActivity} in
 *       gallery mode. Same forward path as the camera one, but starting from
 *       an existing image on the device.</li>
 * </ul>
 *
 * <p>All three paths land in the same {@link EditReceiptActivity} so the
 * downstream behaviour (validation, save, budget linking, delete) is
 * identical. The wrapper exists only to give the user a clear choice at
 * the top of the flow.</p>
 */
public class CreateReceiptActivity extends AppCompatActivity {

    private static final String TAG = "CreateRx";

    /**
     * Optional mode for {@link ScanReceiptActivity}. Modes:
     * <ul>
     *   <li>{@value #MODE_CAMERA} — start on the camera preview (default).</li>
     *   <li>{@value #MODE_GALLERY} — auto-launch the gallery picker on open.</li>
     * </ul>
     */
    public static final String EXTRA_START_MODE = "start_mode";

    public static final String MODE_CAMERA = "camera";

    public static final String MODE_GALLERY = "gallery";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Logger.section("CREATE RECEIPT");

        Logger.i(TAG, "onCreate");

        setContentView(R.layout.activity_create_receipt);

        final View typeButton = findViewById(R.id.btn_create_type);
        final View cameraButton = findViewById(R.id.btn_create_camera);
        final View galleryButton = findViewById(R.id.btn_create_gallery);

        typeButton.setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_create_type clicked");

            launchEditorEmpty();
        });

        cameraButton.setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_create_camera clicked");

            launchScan(MODE_CAMERA);
        });

        galleryButton.setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_create_gallery clicked");

            launchScan(MODE_GALLERY);
        });
    }


    /**
     * Opens the editor with no extras. The editor recognises the
     * no-EXTRA_RECEIPT_ID case as "new" mode: empty form, no photo, no
     * raw text, no auto-pick. Validation fires on save.
     */
    private void launchEditorEmpty() {
        final Intent editorIntent = new Intent(this, EditReceiptActivity.class);

        startActivity(editorIntent);

        // The editor finishes itself on save; this wrapper doesn't need
        // to know what happened. Stay on the back stack so the user can
        // get back here with the system back button.
    }


    /**
     * Opens the scan flow in the requested mode. The scan flow does
     * the OCR and forwards to the editor with photo + raw-text extras.
     */
    private void launchScan(String mode) {
        final Intent scanIntent = new Intent(this, ScanReceiptActivity.class);

        scanIntent.putExtra(EXTRA_START_MODE, mode);

        startActivity(scanIntent);
    }


    @Override
    protected void onResume() {
        super.onResume();

        // If the user returned to this screen after finishing (or backing
        // out of) the editor, give them a soft nudge to continue. They
        // might have forgotten they were in the middle of creating a
        // receipt.
        if (!isTaskRoot()) {
            Logger.i(TAG, "onResume: returning to wrapper; user can re-pick or finish");
        }
    }
}
