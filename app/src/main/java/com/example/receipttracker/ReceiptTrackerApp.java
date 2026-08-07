package com.example.receipttracker;

import android.app.Application;

import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.log.Logger;

/** Holds the singleton Room database reference for the process. */
public class ReceiptTrackerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Start the logger first so even an early crash (e.g. Room failure) is recorded.
        Logger.init(this);
        // Touch the database eagerly so the first screen open is snappy.
        AppDatabase.get(this);
        Logger.i("App", "ReceiptTrackerApp.onCreate complete");
    }
}
