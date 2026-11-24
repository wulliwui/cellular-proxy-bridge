package cn.cellularbridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.Objects;

final class NetworkMonitor implements AutoCloseable {
    private static final int RETRY_DELAY_MS = 2_000;
    private static final int WIFI_REQUEST_TIMEOUT_MS = 60_000;

    private final Context context;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final Handler worker;
    private final ProxyServer server;
    private final NetworkRequest wifiRequest;
    private final Runnable onChange;
    private final Runnable retryListener = this::updateListener;
    private final Runnable retryWifi = this::requestWifi;

    private Network defaultNetwork;
    private boolean ordinaryWifi;
    private boolean defaultRegistered;
    private boolean wifiSwitchRegistered;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private Network wifiNetwork;
    private boolean localOnly;
    private boolean wifiUnavailable;
    private Inet4Address wifiAddress;
    private Inet4Address boundAddress;
    private boolean wifiBindError;

    private Network cellularCandidate;
    private boolean cellularUsable;
    private boolean cellularBlocked;
    private boolean cellularRegistered;
    private volatile int wifiStatus = R.string.state_wait_wifi;
    private boolean stopped;
    private volatile boolean cellularError;
    private volatile Inet4Address listeningAddress;
    private volatile boolean cellularReady;

    NetworkMonitor(Context context, Handler worker, ProxyServer server,
                   NetworkRequest wifiRequest, Runnable onChange) {
        this.context = context;
        this.connectivity = context.getSystemService(ConnectivityManager.class);
        this.wifi = context.getSystemService(WifiManager.class);
        this.worker = worker;
        this.server = server;
        this.wifiRequest = wifiRequest;
        this.onChange = onChange;
    }

    private final BroadcastReceiver wifiSwitch = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
            if (state == WifiManager.WIFI_STATE_DISABLED) {
                releaseWifi();
                wifiStatus = R.string.state_wifi_off;
                onChange.run();
            } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                requestWifi();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback defaults =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    defaultNetwork = network;
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (network.equals(defaultNetwork)) {
                        updateDefault(LocalWifi.isInternetWifi(capabilities));
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (network.equals(defaultNetwork)) {
                        defaultNetwork = null;
                        updateDefault(false);
                    }
                }
            };

    private final ConnectivityManager.NetworkCallback cellularCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (!network.equals(cellularCandidate)) {
                        cellularCandidate = network;
                        cellularUsable = false;
                        cellularBlocked = true;
                        updateCellular();
                    }
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (network.equals(cellularCandidate)) {
                        cellularUsable = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
                        updateCellular();
                    }
                }

                @Override
                public void onBlockedStatusChanged(Network network, boolean blocked) {
                    if (network.equals(cellularCandidate)) {
                        cellularBlocked = blocked;
                        updateCellular();
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (network.equals(cellularCandidate)) {
                        cellularCandidate = null;
                        updateCellular();
                    }
                }
            };

    void start() {
        defaultNetwork = connectivity.getActiveNetwork();
        ordinaryWifi = LocalWifi.isInternetWifi(connectivity.getNetworkCapabilities(defaultNetwork));
        try {
            connectivity.registerDefaultNetworkCallback(defaults, worker);
            defaultRegistered = true;
            context.registerReceiver(wifiSwitch, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                    null, worker, Context.RECEIVER_NOT_EXPORTED);
            wifiSwitchRegistered = true;
            requestWifi();
        } catch (RuntimeException error) {
            wifiStatus = R.string.state_wifi_unavailable;
            Log.w(ProxyServer.TAG, "Wi-Fi monitor failed: " + error.getClass().getSimpleName());
        }

        NetworkRequest cellular = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            connectivity.requestNetwork(cellular, cellularCallback, worker);
            cellularRegistered = true;
        } catch (RuntimeException error) {
            cellularError = true;
            Log.w(ProxyServer.TAG, "Cellular request failed: " + error.getClass().getSimpleName());
        }
        onChange.run();
    }

    private void updateDefault(boolean newOrdinaryWifi) {
        if (ordinaryWifi == newOrdinaryWifi) {
            return;
        }
        ordinaryWifi = newOrdinaryWifi;
        if (ordinaryWifi) {
            releaseWifi();
        } else {
            requestWifi();
        }
        updateListener();
    }

    private void requestWifi() {
        worker.removeCallbacks(retryWifi);
        if (stopped || wifiCallback != null) {
            return;
        }
        if (ordinaryWifi || !wifi.isWifiEnabled()) {
            updateListener();
            return;
        }
        wifiUnavailable = false;
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (wifiCallback == this && !network.equals(wifiNetwork)) {
                    wifiNetwork = network;
                    localOnly = false;
                    wifiAddress = null;
                    updateListener();
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (wifiCallback == this && network.equals(wifiNetwork)) {
                    localOnly = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    updateListener();
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (wifiCallback == this && network.equals(wifiNetwork)) {
                    wifiAddress = ipv4Address(properties);
                    updateListener();
                }
            }

            @Override
            public void onLost(Network network) {
                if (wifiCallback == this && network.equals(wifiNetwork)) {
                    releaseWifi();
                    worker.postDelayed(retryWifi, RETRY_DELAY_MS);
                }
            }

            @Override
            public void onUnavailable() {
                if (wifiCallback == this) {
                    wifiUnavailable = true;
                    releaseWifi();
                }
            }
        };
        try {
            connectivity.requestNetwork(wifiRequest, wifiCallback, worker, WIFI_REQUEST_TIMEOUT_MS);
        } catch (RuntimeException error) {
            wifiUnavailable = true;
            releaseWifi();
            Log.w(ProxyServer.TAG, "Local Wi-Fi request failed: " + error.getClass().getSimpleName());
        }
        updateListener();
    }

    private static Inet4Address ipv4Address(LinkProperties properties) {
        for (LinkAddress link : properties.getLinkAddresses()) {
            if (link.getAddress() instanceof Inet4Address ipv4
                    && !ipv4.isLoopbackAddress() && !ipv4.isAnyLocalAddress()) {
                return ipv4;
            }
        }
        return null;
    }

    private void releaseWifi() {
        worker.removeCallbacks(retryWifi);
        var callback = wifiCallback;
        wifiCallback = null;
        unregister(callback);
        wifiNetwork = null;
        wifiAddress = null;
        localOnly = false;
        updateListener();
    }

    private void updateCellular() {
        Network usable = cellularUsable && !cellularBlocked ? cellularCandidate : null;
        cellularReady = usable != null;
        server.setCellular(usable);
        onChange.run();
    }

    private void updateListener() {
        worker.removeCallbacks(retryListener);
        if (stopped) {
            return;
        }
        Inet4Address address = localOnly && !ordinaryWifi ? wifiAddress : null;
        if (wifiBindError || !Objects.equals(address, boundAddress)) {
            try {
                server.listen(address, () -> worker.post(this::listenerFailed));
                boundAddress = address;
                listeningAddress = address;
                wifiBindError = false;
            } catch (IOException | RuntimeException error) {
                boundAddress = null;
                listeningAddress = null;
                wifiBindError = true;
                Log.w(ProxyServer.TAG, "Wi-Fi bind failed: " + error.getClass().getSimpleName());
                worker.postDelayed(retryListener, RETRY_DELAY_MS);
            }
        }
        if (ordinaryWifi) {
            wifiStatus = R.string.state_wifi_default;
        } else if (!wifi.isWifiEnabled()) {
            wifiStatus = R.string.state_wifi_off;
        } else if (wifiUnavailable) {
            wifiStatus = R.string.state_wifi_unavailable;
        } else {
            wifiStatus = wifiBindError ? R.string.state_wifi_error : R.string.state_wait_wifi;
        }
        onChange.run();
    }

    private void listenerFailed() {
        listeningAddress = null;
        wifiBindError = true;
        worker.removeCallbacks(retryListener);
        worker.postDelayed(retryListener, RETRY_DELAY_MS);
        wifiStatus = R.string.state_wifi_error;
        onChange.run();
    }

    Inet4Address listeningAddress() {
        return listeningAddress;
    }

    int wifiStatus() {
        return wifiStatus;
    }

    boolean cellularReady() {
        return cellularReady;
    }

    boolean cellularError() {
        return cellularError;
    }

    private void unregister(ConnectivityManager.NetworkCallback callback) {
        if (callback != null) {
            try {
                connectivity.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // A timed-out request has already been released by the system.
            }
        }
    }

    @Override
    public void close() {
        stopped = true;
        worker.removeCallbacks(retryListener);
        releaseWifi();
        if (wifiSwitchRegistered) {
            context.unregisterReceiver(wifiSwitch);
        }
        if (defaultRegistered) {
            unregister(defaults);
        }
        if (cellularRegistered) {
            unregister(cellularCallback);
        }
    }
}
