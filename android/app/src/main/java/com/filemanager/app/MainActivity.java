package com.filemanager.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Registra il plugin custom PRIMA di super.onCreate
        registerPlugin(FileReaderPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
