package dev.otake.rmssdh10n;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LocalServerPlugin.class);
        registerPlugin(HrvNativePlugin.class);
        super.onCreate(savedInstanceState);

        // Android 13+ requires runtime permission to show the foreground-service
        // notification that keeps background monitoring alive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
        // Android 12+ requires BLUETOOTH_CONNECT at runtime; the native engine
        // connects the H10 by address from inside the service, so this must be
        // granted before switching to the native engine.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 1002);
        }
    }
}
