package dev.otake.rmssdh10n;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LocalServerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
