package cn.cellularbridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class BridgeService extends Service {
    private static final String CHANNEL = "proxy_status";
    private static final String STOP = "cn.cellularbridge.app.STOP";
    private static final int NOTIFICATION_ID = 1;
    private static final State STOPPED = new State(false, null, R.string.state_stopped, 0, 0);

    private static State state = STOPPED;
    private static NetworkRequest pendingWifiRequest;
    private static final Set<Consumer<State>> observers = new LinkedHashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeConfig config;
    private ProxyServer server;
    private NetworkMonitor networks;
    private HandlerThread networkThread;
    private Handler networkHandler;
    private NotificationManager notifications;

    record State(boolean running, String address, int wifiText, int cellularText, int activeCount) {
    }

    static void observe(Consumer<State> observer) {
        observers.add(observer);
        observer.accept(state);
    }

    static void removeObserver(Consumer<State> observer) {
        observers.remove(observer);
    }

    private static void publish(State newState) {
        if (newState.equals(state)) {
            return;
        }
        state = newState;
        for (Consumer<State> observer : new ArrayList<>(observers)) {
            observer.accept(state);
        }
    }

    static boolean start(Context context, NetworkRequest wifiRequest) {
        pendingWifiRequest = wifiRequest;
        publish(new State(true, null, R.string.state_starting, R.string.state_cell_wait, 0));
        try {
            context.startForegroundService(new Intent(context, BridgeService.class));
            return true;
        } catch (RuntimeException error) {
            pendingWifiRequest = null;
            publish(STOPPED);
            Log.w(ProxyServer.TAG, "Service start failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    static void stop(Context context) {
        if (!context.stopService(new Intent(context, BridgeService.class))) {
            pendingWifiRequest = null;
            publish(STOPPED);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null) {
            return START_NOT_STICKY;
        }
        NetworkRequest wifiRequest = pendingWifiRequest;
        pendingWifiRequest = null;
        if (wifiRequest == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        config = BridgeConfig.load(this);
        networkThread = new HandlerThread("bridge-networks");
        networkThread.start();
        networkHandler = new Handler(networkThread.getLooper());
        server = new ProxyServer(config, this::scheduleRefresh);
        networks = new NetworkMonitor(this, networkHandler, server, wifiRequest, this::scheduleRefresh);
        networkHandler.post(networks::start);
        refresh();
        return START_NOT_STICKY;
    }

    private void scheduleRefresh() {
        main.post(this::refresh);
    }

    private void refresh() {
        if (server == null) {
            return;
        }
        Inet4Address address = networks.listeningAddress();
        String proxy = address == null ? null : "http://" + address.getHostAddress() + ":" + config.listenPort;
        int wifi = networks.wifiStatus();
        int cell = networks.cellularError() ? R.string.state_cell_error
                : networks.cellularReady() ? R.string.state_cell_ready : R.string.state_cell_wait;
        int active = server.activeCount();
        State newState = new State(true, proxy, wifi, cell, active);
        if (!newState.equals(state)) {
            publish(newState);
            String endpoint = proxy == null ? getString(wifi) : proxy;
            notifications.notify(NOTIFICATION_ID,
                    notification(getString(R.string.notification_status, endpoint, active)));
        }
    }

    private Notification notification(String description) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BridgeService.class).setAction(STOP), flags);
        RemoteViews compact = new RemoteViews(getPackageName(), R.layout.notification_proxy);
        compact.setTextViewText(R.id.notification_description, description);
        compact.setOnClickPendingIntent(R.id.notification_stop, stop);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(description)
                .setSmallIcon(R.drawable.ic_status)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCustomContentView(compact)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .build();
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (networks != null) {
            networkHandler.post(() -> {
                try {
                    networks.close();
                } finally {
                    networkThread.quitSafely();
                }
            });
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        publish(STOPPED);
        super.onDestroy();
    }
}
