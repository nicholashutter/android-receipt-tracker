package com.example.receipttracker;


import android.app.Application;


import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ocr.MerchantClassifier;

import com.example.receipttracker.util.AppExecutors;


/** Holds the singleton Room database reference for the process. */
public class ReceiptTrackerApp extends Application {


    // MUTABLE: process-wide singleton so HandwritingOcr (which lives in
    // the ocr package, not the ui package) can resolve an app context for
    // loading its traineddata from assets without taking a hard dep on
    // the Application class hierarchy.
    private static ReceiptTrackerApp INSTANCE;


    public static ReceiptTrackerApp get() {
        return INSTANCE;
    }


    @Override
    public void onCreate() {
        super.onCreate();

        INSTANCE = this;

        // Start the logger first so even an early crash (e.g. Room failure) is recorded.
        Logger.init(this);

        // Touch the database eagerly so the first screen open is snappy.
        AppDatabase.get(this);

        // Pre-load the merchant JSON on a background thread so the first
        // OCR scan doesn't stall on asset I/O.
        AppExecutors.get().diskIO().execute(() -> MerchantClassifier.load(this));

        // Pre-load Tesseract's traineddata on a background thread too.
        // Tesseract init is cheap once the file is on disk, but the first
        // call also copies ~22 MB from assets to filesDir, which we
        // don't want to block the UI thread on.
        AppExecutors.get().diskIO().execute(() -> com.example.receipttracker.ocr.HandwritingOcr.get().ensureInit(this));

        Logger.i("App", "ReceiptTrackerApp.onCreate complete");
    }


    @Override
    public void onTerminate() {
        // Best-effort: release Tesseract's native buffers. The OS reclaims
        // them anyway on process exit, but this keeps the leak canary
        // quiet if the user backgrounds the app for hours.
        com.example.receipttracker.ocr.HandwritingOcr.get().shutdown();

        super.onTerminate();
    }
}
