package dev.otake.rmssdh10n;

import android.content.Context;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * Embeds a tiny HTTP + WebSocket server (NanoHTTPD/NanoWSD) so a PC on the same
 * Wi-Fi can open the dashboard in a browser at http://<phone-ip>:<port>.
 *
 * The phone app is the master: it streams live status/point objects over the
 * WebSocket (broadcast) and exposes the current localStorage snapshot
 * (history/trend/activities/baseline) at GET /api/snapshot for the initial sync.
 * Static dashboard assets are served straight from assets/public (the same
 * www/ bundle the WebView uses), so the remote page is byte-identical.
 */
@CapacitorPlugin(name = "LocalServer")
public class LocalServerPlugin extends Plugin {
    private Server server;
    private volatile String snapshot = "{}";
    private final Set<NanoWSD.WebSocket> clients = new CopyOnWriteArraySet<>();

    @PluginMethod
    public void start(PluginCall call) {
        int port = call.getInt("port", 8080);
        try {
            if (server != null) server.stop();
            server = new Server(port, getContext(), this);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            JSObject ret = new JSObject();
            ret.put("running", true);
            ret.put("port", port);
            ret.put("url", urlFor(port));
            call.resolve(ret);
        } catch (IOException e) {
            call.reject("server start failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (server != null) { server.stop(); server = null; }
        clients.clear();
        JSObject ret = new JSObject();
        ret.put("running", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        boolean running = server != null && server.isAlive();
        int port = server != null ? server.getListeningPort() : 0;
        JSObject ret = new JSObject();
        ret.put("running", running);
        ret.put("port", port);
        ret.put("url", running ? urlFor(port) : "");
        call.resolve(ret);
    }

    /** Replace the snapshot returned by GET /api/snapshot (called from JS). */
    @PluginMethod
    public void setSnapshot(PluginCall call) {
        String data = call.getString("data");
        if (data != null) this.snapshot = data;
        call.resolve();
    }

    /** Push a frame (JSON string) to every connected WebSocket client. */
    @PluginMethod
    public void broadcast(PluginCall call) {
        String data = call.getString("data");
        if (data != null) {
            for (NanoWSD.WebSocket c : clients) {
                try { c.send(data); } catch (IOException e) { clients.remove(c); }
            }
        }
        call.resolve();
    }

    String getSnapshot() { return snapshot; }
    void register(NanoWSD.WebSocket c) { clients.add(c); }
    void unregister(NanoWSD.WebSocket c) { clients.remove(c); }

    private String urlFor(int port) {
        String ip = localIp();
        return ip != null ? "http://" + ip + ":" + port : "";
    }

    /** First non-loopback IPv4 address (the LAN address on Wi-Fi). */
    private String localIp() {
        try {
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** NanoWSD server: WebSocket upgrades go to WsClient; everything else is HTTP. */
    static class Server extends NanoWSD {
        private final Context ctx;
        private final LocalServerPlugin plugin;

        Server(int port, Context ctx, LocalServerPlugin plugin) {
            super(port);
            this.ctx = ctx;
            this.plugin = plugin;
        }

        @Override
        protected WebSocket openWebSocket(IHTTPSession handshake) {
            return new WsClient(handshake, plugin);
        }

        @Override
        protected Response serveHttp(IHTTPSession session) {
            String uri = session.getUri();
            if (uri == null || uri.equals("/")) uri = "/index.html";

            if (uri.equals("/api/snapshot")) {
                Response r = newFixedLengthResponse(Response.Status.OK, "application/json", plugin.getSnapshot());
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            }

            // Strip a query string and serve the matching file from assets/public.
            int q = uri.indexOf('?');
            if (q >= 0) uri = uri.substring(0, q);
            String assetPath = "public" + uri;
            try {
                InputStream is = ctx.getAssets().open(assetPath);
                byte[] bytes = readAll(is);
                Response r = newFixedLengthResponse(Response.Status.OK, mimeFor(uri),
                        new ByteArrayInputStream(bytes), bytes.length);
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            } catch (IOException e) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
            }
        }

        private static byte[] readAll(InputStream is) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            is.close();
            return out.toByteArray();
        }

        private static String mimeFor(String uri) {
            String u = uri.toLowerCase();
            if (u.endsWith(".html")) return "text/html; charset=utf-8";
            if (u.endsWith(".js") || u.endsWith(".mjs")) return "application/javascript; charset=utf-8";
            if (u.endsWith(".css")) return "text/css; charset=utf-8";
            if (u.endsWith(".json") || u.endsWith(".map")) return "application/json; charset=utf-8";
            if (u.endsWith(".png")) return "image/png";
            if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
            if (u.endsWith(".svg")) return "image/svg+xml";
            if (u.endsWith(".ico")) return "image/x-icon";
            if (u.endsWith(".woff2")) return "font/woff2";
            return "application/octet-stream";
        }
    }

    /** A connected browser. Registers/unregisters with the plugin's client set. */
    static class WsClient extends NanoWSD.WebSocket {
        private final LocalServerPlugin plugin;

        WsClient(NanoHTTPD.IHTTPSession handshake, LocalServerPlugin plugin) {
            super(handshake);
            this.plugin = plugin;
        }

        @Override protected void onOpen() { plugin.register(this); }
        @Override protected void onClose(NanoWSD.WebSocketFrame.CloseCode code, String reason, boolean remote) { plugin.unregister(this); }
        @Override protected void onMessage(NanoWSD.WebSocketFrame message) { /* remote is view-only */ }
        @Override protected void onPong(NanoWSD.WebSocketFrame pong) { }
        @Override protected void onException(IOException exception) { plugin.unregister(this); }
    }
}
