package com.example.receipttracker.ui.scan;


import android.Manifest;

import android.app.Activity;

import android.content.Intent;

import android.content.pm.PackageManager;

import android.graphics.Bitmap;

import android.net.Uri;

import android.os.Bundle;

import android.view.View;

import android.widget.ImageView;

import android.widget.ProgressBar;

import android.widget.TextView;

import android.widget.Toast;


import androidx.activity.result.ActivityResultLauncher;

import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;

import androidx.camera.core.CameraSelector;

import androidx.camera.core.ImageCapture;

import androidx.camera.core.ImageCaptureException;

import androidx.camera.core.ImageProxy;

import androidx.camera.core.Preview;

import androidx.camera.lifecycle.ProcessCameraProvider;

import androidx.camera.view.PreviewView;

import androidx.core.content.ContextCompat;


import com.example.receipttracker.R;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ocr.ImageQualityGate;

import com.example.receipttracker.ocr.ReceiptImageStore;

import com.example.receipttracker.ocr.ReceiptOcr;

import com.example.receipttracker.ui.receipts.EditReceiptActivity;

import com.google.android.material.button.MaterialButton;

import com.google.common.util.concurrent.ListenableFuture;


import java.io.File;

import java.nio.ByteBuffer;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;


/**
 * Camera-driven receipt capture. Two entry points: a live preview
 * with a capture button, and a "pick from gallery" button for images
 * already on the device. After capture (or pick), the bitmap is OCR'd
 * and the user is forwarded to the editor.
 */
public class ScanReceiptActivity extends AppCompatActivity {

    private static final String TAG = "Scan";
    private static final int MAX_IMAGE_DIM = 1600;
    private static final int CAMERA_PERMISSION_REQUEST = 1;
    private static final int REQ_EDIT = 9001;
    private static final int IO_BUFFER_SIZE = 8192;
    private static final int INVALID_DIMENSION = 0;
    private static final String LABEL_RETAKE = "Retake";
    private static final String IMAGE_MIME = "image/*";

    private PreviewView previewView;
    private ImageView capturedView;
    private TextView hintView;
    private TextView processingView;
    private ProgressBar progressBar;
    private MaterialButton captureButton;
    private MaterialButton pickGalleryButton;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    // MUTABLE: toggled on capture/retake.
    private boolean showingResult = false;


    private final ActivityResultLauncher<String> requestCameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, R.string.scan_permission_denied, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) handlePickedImage(uri);
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_scan_receipt);

        previewView = findViewById(R.id.preview);
        capturedView = findViewById(R.id.iv_captured);
        hintView = findViewById(R.id.tv_hint);
        processingView = findViewById(R.id.tv_processing);
        progressBar = findViewById(R.id.progress);
        captureButton = findViewById(R.id.btn_capture);
        pickGalleryButton = findViewById(R.id.btn_pick_gallery);

        cameraExecutor = Executors.newSingleThreadExecutor();

        captureButton.setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_capture clicked (showingResult=" + showingResult + ")");
            if (showingResult) {
                resetToCamera();
            } else {
                capturePhoto();
            }
        });
        pickGalleryButton.setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_pick_gallery clicked");
            pickImageLauncher.launch(IMAGE_MIME);
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            Logger.i(TAG, "Camera permission already granted");
            startCamera();
        } else {
            Logger.i(TAG, "Requesting camera permission");
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }


    private void startCamera() {
        Logger.i(TAG, "startCamera: requesting ProcessCameraProvider");
        final ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                final ProcessCameraProvider provider = providerFuture.get();
                final Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);
                Logger.i(TAG, "startCamera: camera bound to lifecycle");
            } catch (Exception bindFailure) {
                Logger.e(TAG, "startCamera: failed to bind camera", bindFailure);
                Toast.makeText(this, "Camera unavailable: " + bindFailure.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }


    private void capturePhoto() {
        if (imageCapture == null) {
            Logger.w(TAG, "capturePhoto: imageCapture is null (camera not bound?)");
            return;
        }
        Logger.i(TAG, "capturePhoto: taking picture");
        showProgress(true);

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Logger.i(TAG, "capture: onCaptureSuccess (format=" + image.getFormat() + ")");
                final Bitmap captured = imageProxyToBitmap(image);
                image.close();
                if (captured == null) {
                    Logger.w(TAG, "capture: imageProxyToBitmap returned null");
                    runOnUiThread(() -> {
                        showProgress(false);
                        Toast.makeText(ScanReceiptActivity.this, "Capture failed",
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                runOnUiThread(() -> showCapturedAndOcr(captured));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Logger.e(TAG, "capture: onError", exception);
                runOnUiThread(() -> {
                    showProgress(false);
                    Toast.makeText(ScanReceiptActivity.this,
                            "Capture failed: " + exception.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }


    private void showProgress(boolean visible) {
        progressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        processingView.setVisibility(visible ? View.VISIBLE : View.GONE);
        captureButton.setEnabled(!visible);
    }


    private Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            final Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (raw == null) return null;

            // Downscale to keep memory and OCR time reasonable.
            final int width = raw.getWidth();
            final int height = raw.getHeight();
            final int longest = Math.max(width, height);
            if (longest > MAX_IMAGE_DIM) {
                final float scale = MAX_IMAGE_DIM / (float) longest;
                final int newWidth = Math.round(width * scale);
                final int newHeight = Math.round(height * scale);
                Logger.i(TAG, "Downscaling " + width + "x" + height
                        + " -> " + newWidth + "x" + newHeight
                        + " (longest " + longest + " > " + MAX_IMAGE_DIM + ")");
                return Bitmap.createScaledBitmap(raw, newWidth, newHeight, true);
            }
            Logger.i(TAG, "Image " + width + "x" + height + " fits within limit, no downscale");
            return raw;
        } catch (Exception decodeFailure) {
            Logger.e(TAG, "imageProxyToBitmap failed", decodeFailure);
            return null;
        }
    }


    private void handlePickedImage(Uri uri) {
        Logger.i(TAG, "handlePickedImage: uri=" + uri);
        showProgress(true);

        cameraExecutor.execute(() -> {
            try {
                final File pickedFile = ReceiptImageStore.importFromUri(this, uri);
                final Bitmap decoded = ReceiptImageStore.decodeSampled(
                        pickedFile.getAbsolutePath(), MAX_IMAGE_DIM, MAX_IMAGE_DIM);
                runOnUiThread(() -> {
                    if (decoded != null) {
                        showCapturedAndOcr(decoded);
                    } else {
                        showProgress(false);
                        Toast.makeText(this, "Couldn't decode that image", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception pickFailure) {
                Logger.e(TAG, "handlePickedImage failed", pickFailure);
                runOnUiThread(() -> {
                    showProgress(false);
                    Toast.makeText(this, "Couldn't load image: " + pickFailure.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }


    private void showCapturedAndOcr(Bitmap captured) {
        Logger.i(TAG, "showCapturedAndOcr: bitmap=" + captured.getWidth() + "x" + captured.getHeight());
        capturedView.setImageBitmap(captured);
        capturedView.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
        hintView.setVisibility(View.GONE);
        showingResult = true;
        captureButton.setText(LABEL_RETAKE);

        cameraExecutor.execute(() -> {
            // Run the image quality gate in the same worker as the OCR
            // (it's a few ms on a sampled-down bitmap). We never block
            // — a poor-quality photo is still passed through, the user
            // just gets a "consider retaking" toast so they know the
            // result might be bad.
            final ImageQualityGate.Verdict quality = ImageQualityGate.assess(captured);
            Logger.i(TAG, "image quality: " + quality);

            final String rawText = ReceiptOcr.recognizeText(captured);
            final String[] savedPath = new String[1];

            try {
                final File savedFile = ReceiptImageStore.saveBitmap(this, captured);
                if (savedFile != null) {
                    savedPath[0] = savedFile.getAbsolutePath();
                }
            } catch (Exception saveFailure) {
                Logger.e(TAG, "Failed to persist captured bitmap", saveFailure);
            }

            final String finalRawText = rawText;
            final boolean acceptableQuality = quality.acceptable;
            runOnUiThread(() -> {
                if (!acceptableQuality) {
                    showQualityWarning(quality);
                }

                launchEditor(savedPath[0], finalRawText);
            });
        });
    }


    private void showQualityWarning(ImageQualityGate.Verdict quality) {
        final String message = "Photo quality is low (" + String.join(", ", quality.issues)
                + "). The scan result may be inaccurate — consider retaking.";

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }


    private void launchEditor(String photoPath, String rawText) {
        showProgress(false);

        if (rawText == null || rawText.trim().isEmpty()) {
            Logger.w(TAG, "OCR returned empty/null text");
            Toast.makeText(this, R.string.scan_no_text, Toast.LENGTH_LONG).show();
        }

        final int rawLen;
        if (rawText == null) {
            rawLen = INVALID_DIMENSION;
        } else {
            rawLen = rawText.length();
        }
        Logger.i(TAG, "Launching EditReceiptActivity, photoPath=" + photoPath
                + ", rawTextLen=" + rawLen);

        final Intent editorIntent = new Intent(this, EditReceiptActivity.class);
        if (photoPath != null) {
            editorIntent.putExtra(EditReceiptActivity.EXTRA_PHOTO_PATH, photoPath);
        }
        final String rawTextExtra;
        if (rawText == null) {
            rawTextExtra = "";
        } else {
            rawTextExtra = rawText;
        }
        editorIntent.putExtra(EditReceiptActivity.EXTRA_RAW_TEXT, rawTextExtra);
        startActivityForResult(editorIntent, REQ_EDIT);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT) {
            // Whether saved or cancelled, finish the scan flow so the
            // user returns to main.
            setResult(Activity.RESULT_OK, data);
            finish();
        }
    }


    private void resetToCamera() {
        capturedView.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        hintView.setVisibility(View.VISIBLE);
        showingResult = false;
        captureButton.setText(R.string.scan_capture);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
