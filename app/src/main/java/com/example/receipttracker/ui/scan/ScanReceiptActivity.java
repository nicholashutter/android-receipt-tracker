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
import com.example.receipttracker.ocr.ReceiptImageStore;
import com.example.receipttracker.ocr.ReceiptOcr;
import com.example.receipttracker.ui.receipts.EditReceiptActivity;
import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanReceiptActivity extends AppCompatActivity {

    private static final int MAX_IMAGE_DIM = 1600;

    private PreviewView previewView;
    private ImageView ivCaptured;
    private TextView tvHint;
    private TextView tvProcessing;
    private ProgressBar progress;
    private MaterialButton btnCapture;
    private MaterialButton btnPickGallery;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
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
        Logger.i("Scan", "onCreate");
        setContentView(R.layout.activity_scan_receipt);

        previewView = findViewById(R.id.preview);
        ivCaptured = findViewById(R.id.iv_captured);
        tvHint = findViewById(R.id.tv_hint);
        tvProcessing = findViewById(R.id.tv_processing);
        progress = findViewById(R.id.progress);
        btnCapture = findViewById(R.id.btn_capture);
        btnPickGallery = findViewById(R.id.btn_pick_gallery);

        cameraExecutor = Executors.newSingleThreadExecutor();

        btnCapture.setOnClickListener(v -> {
            Logger.i("Scan", "btn_capture clicked (showingResult=" + showingResult + ")");
            if (showingResult) {
                // Tapping "capture" after a result just resets to live preview.
                resetToCamera();
            } else {
                capturePhoto();
            }
        });
        btnPickGallery.setOnClickListener(v -> {
            Logger.i("Scan", "btn_pick_gallery clicked");
            pickImageLauncher.launch("image/*");
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            Logger.i("Scan", "Camera permission already granted");
            startCamera();
        } else {
            Logger.i("Scan", "Requesting camera permission");
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        Logger.i("Scan", "startCamera: requesting ProcessCameraProvider");
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);
                Logger.i("Scan", "startCamera: camera bound to lifecycle");
            } catch (Exception e) {
                Logger.e("Scan", "startCamera: failed to bind camera", e);
                Toast.makeText(this, "Camera unavailable: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capturePhoto() {
        if (imageCapture == null) {
            Logger.w("Scan", "capturePhoto: imageCapture is null (camera not bound?)");
            return;
        }
        Logger.i("Scan", "capturePhoto: taking picture");
        btnCapture.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        tvProcessing.setVisibility(View.VISIBLE);

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Logger.i("Scan", "capture: onCaptureSuccess (format=" + image.getFormat() + ")");
                Bitmap bmp = imageProxyToBitmap(image);
                image.close();
                if (bmp == null) {
                    Logger.w("Scan", "capture: imageProxyToBitmap returned null");
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        tvProcessing.setVisibility(View.GONE);
                        btnCapture.setEnabled(true);
                        Toast.makeText(ScanReceiptActivity.this,
                                "Capture failed", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                runOnUiThread(() -> showCapturedAndOcr(bmp));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Logger.e("Scan", "capture: onError", exception);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    tvProcessing.setVisibility(View.GONE);
                    btnCapture.setEnabled(true);
                    Toast.makeText(ScanReceiptActivity.this,
                            "Capture failed: " + exception.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private Bitmap imageProxyToBitmap(ImageProxy proxy) {
        try {
            ByteBuffer buffer = proxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (raw == null) return null;
            // Downscale to keep memory and OCR time reasonable.
            int w = raw.getWidth(), h = raw.getHeight();
            int longest = Math.max(w, h);
            if (longest > MAX_IMAGE_DIM) {
                float scale = MAX_IMAGE_DIM / (float) longest;
                int nw = Math.round(w * scale), nh = Math.round(h * scale);
                Logger.i("Scan", "Downscaling " + w + "x" + h + " -> " + nw + "x" + nh
                        + " (longest " + longest + " > " + MAX_IMAGE_DIM + ")");
                return Bitmap.createScaledBitmap(raw, nw, nh, true);
            }
            Logger.i("Scan", "Image " + w + "x" + h + " fits within limit, no downscale");
            return raw;
        } catch (Exception e) {
            Logger.e("Scan", "imageProxyToBitmap failed", e);
            return null;
        }
    }

    private void handlePickedImage(Uri uri) {
        Logger.i("Scan", "handlePickedImage: uri=" + uri);
        progress.setVisibility(View.VISIBLE);
        tvProcessing.setVisibility(View.VISIBLE);
        btnCapture.setEnabled(false);
        cameraExecutor.execute(() -> {
            try {
                File f = ReceiptImageStore.importFromUri(this, uri);
                Bitmap bmp = ReceiptImageStore.decodeSampled(
                        f.getAbsolutePath(), MAX_IMAGE_DIM, MAX_IMAGE_DIM);
                runOnUiThread(() -> {
                    if (bmp != null) {
                        showCapturedAndOcr(bmp);
                    } else {
                        progress.setVisibility(View.GONE);
                        tvProcessing.setVisibility(View.GONE);
                        btnCapture.setEnabled(true);
                        Toast.makeText(this, "Couldn't decode that image",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Logger.e("Scan", "handlePickedImage failed", e);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    tvProcessing.setVisibility(View.GONE);
                    btnCapture.setEnabled(true);
                    Toast.makeText(this, "Couldn't load image: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showCapturedAndOcr(Bitmap bmp) {
        Logger.i("Scan", "showCapturedAndOcr: bitmap=" + bmp.getWidth() + "x" + bmp.getHeight());
        ivCaptured.setImageBitmap(bmp);
        ivCaptured.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
        tvHint.setVisibility(View.GONE);
        showingResult = true;
        btnCapture.setText("Retake");

        cameraExecutor.execute(() -> {
            String rawText = ReceiptOcr.recognizeText(bmp);
            final String[] savedPath = new String[1];
            try {
                File saved = ReceiptImageStore.saveBitmap(this, bmp);
                if (saved != null) savedPath[0] = saved.getAbsolutePath();
            } catch (Exception e) {
                Logger.e("Scan", "Failed to persist captured bitmap", e);
            }
            final String finalRaw = rawText;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                tvProcessing.setVisibility(View.GONE);
                btnCapture.setEnabled(true);
                if (rawText == null || rawText.trim().isEmpty()) {
                    Logger.w("Scan", "OCR returned empty/null text");
                    Toast.makeText(this, R.string.scan_no_text, Toast.LENGTH_LONG).show();
                }
                Logger.i("Scan", "Launching EditReceiptActivity, photoPath="
                        + savedPath[0] + ", rawTextLen=" + (finalRaw == null ? 0 : finalRaw.length()));
                Intent i = new Intent(this, EditReceiptActivity.class);
                if (savedPath[0] != null) i.putExtra(EditReceiptActivity.EXTRA_PHOTO_PATH, savedPath[0]);
                i.putExtra(EditReceiptActivity.EXTRA_RAW_TEXT, finalRaw == null ? "" : finalRaw);
                startActivityForResult(i, REQ_EDIT);
            });
        });
    }

    private static final int REQ_EDIT = 9001;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT) {
            // Whether saved or cancelled, finish the scan flow so the user returns to main.
            setResult(Activity.RESULT_OK, data);
            finish();
        }
    }

    private void resetToCamera() {
        ivCaptured.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        tvHint.setVisibility(View.VISIBLE);
        showingResult = false;
        btnCapture.setText(R.string.scan_capture);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
