package com.tvhome.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Network availability plus a TCP latency probe, used only for the launcher's
 * own header/settings indicator. Never draws over third-party apps.
 */
public final class NetMonitor {

    public static final class Status {
        public final boolean online;
        public final int latencyMs; // -1 = unknown
        public final String type;

        Status(boolean online, int latencyMs, String type) {
            this.online = online;
            this.latencyMs = latencyMs;
            this.type = type;
        }
    }

    private static final String PROBE_HOST = "8.8.8.8";
    private static final int PROBE_PORT = 443;

    private NetMonitor() {
    }

    /** Blocking — run off the main thread. */
    public static Status probe(Context context) {
        boolean online = false;
        String type = "Offline";
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null) {
                    online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        type = "Wi-Fi";
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        type = "Ethernet";
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        type = "Mobile";
                    } else {
                        type = "Network";
                    }
                }
            }
        } catch (Exception ignored) {
            // stays offline
        }
        int latency = online ? measureLatency() : -1;
        return new Status(online, latency, type);
    }

    /** TCP connect round-trip to a public resolver; nothing is sent. */
    private static int measureLatency() {
        try (Socket socket = new Socket()) {
            long start = android.os.SystemClock.uptimeMillis();
            socket.connect(new InetSocketAddress(PROBE_HOST, PROBE_PORT), 3000);
            return Math.max(1, (int) (android.os.SystemClock.uptimeMillis() - start));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Green <= 80 ms, amber <= 200 ms, red above or offline. */
    public static int dotColor(Status status) {
        if (!status.online || status.latencyMs < 0) {
            return 0xFFFF5252;
        }
        if (status.latencyMs <= 80) {
            return 0xFF00E676;
        }
        if (status.latencyMs <= 200) {
            return 0xFFFFB74D;
        }
        return 0xFFFF5252;
    }
}
