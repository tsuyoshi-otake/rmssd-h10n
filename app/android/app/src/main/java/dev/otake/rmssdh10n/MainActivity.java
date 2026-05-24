package dev.otake.rmssdh10n;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

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

    // On some devices the WebView returns to the foreground after screen-off
    // showing a stale frame (only the page background paints; the dashboard
    // content is missing) until something invalidates it — a tap used to "fix"
    // it. The activity lifecycle callbacks always fire on return, so we resume the
    // WebView and drive the JS-side forceRepaint (a display toggle that forces a
    // full relayout + raster), which is proven to push the real content frame.
    private void kickRepaint() {
        try {
            final WebView wv = (this.bridge != null) ? this.bridge.getWebView() : null;
            Log.i("RepaintKick", "kickRepaint wv=" + (wv != null));
            if (wv == null) return;
            wv.onResume();
            wv.resumeTimers();
            wv.invalidate();
            wv.evaluateJavascript("window.__forceRepaint && window.__forceRepaint()", null);
            // The renderer can be a beat behind on wake; repaint once more shortly.
            wv.postDelayed(() -> {
                wv.invalidate();
                wv.evaluateJavascript("window.__forceRepaint && window.__forceRepaint()", null);
            }, 250);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        kickRepaint();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) kickRepaint();
    }
}
