package com.example.receipttracker;


import android.app.Application;


import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ocr.MerchantClassifier;
import com.example.receipttracker.util.AppExecutors;


/** Holds the singleton Room database reference for the process. */
public class ReceiptTrackerApp extends Application {


    @Override
    public void onCreate() {
        super.onCreate();

        // Start the logger first so even an early crash (e.g. Room failure) is recorded.
        Logger.init(this);

        // Touch the database eagerly so the first screen open is snappy.
        AppDatabase.get(this);

        // Pre-load the merchant JSON on a background thread so the first
        // OCR scan doesn't stall on asset I/O.
        AppExecutors.get().diskIO().execute(() -> MerchantClassifier.load(this));

        Logger.i("App", "ReceiptTrackerApp.onCreate complete");
    }
}
