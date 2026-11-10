package tv.study.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;

public final class WebAdminService extends Service {
    private static final String CHANNEL = "lan_admin";
    private static final int NOTIFICATION_ID = 8787;
    private static final long RETRY_MILLIS = 5000;
    private static WebAdminService active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshListener;
    private final ConnectivityManager.NetworkCallback networkChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            main.post(refresh);
        }

        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            main.post(refresh);
        }

        @Override public void onLost(Network network) {
            main.post(refresh);
        }
    };
    private Store store;
    private ConnectivityManager networks;
    private WebAdmin server;
    private Inet4Address address;
    private String unavailable = "正在启动局域网控制台…";

    static void startIfConfigured(Context context, Store store) {
        if (active != null || !store.hasPin()) return;
        Intent intent = new Intent(context, WebAdminService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (IllegalStateException e) {
            // A system background-start restriction can defer this until Home resumes.
            Log.w("StudyTV", "Could not start LAN admin service", e);
        }
    }

    static WebAdmin server() {
        WebAdmin server = active == null ? null : active.server;
        return server != null && server.isOpen() ? server : null;
    }

    static String unavailableMessage() {
        return active == null ? "正在启动局域网控制台…" : active.unavailable;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (active == this) return START_STICKY;
        String title = "学习电视 · 局域网控制台";
        Notification.Builder notification;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW));
            notification = new Notification.Builder(this, CHANNEL);
        } else {
            notification = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        startForeground(NOTIFICATION_ID, notification.setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true).build());
        store = Store.get(this);
        if (!store.hasPin()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        networks = getSystemService(ConnectivityManager.class);
        networks.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), networkChanges);
        active = this;
        refreshListener();
        return START_STICKY;
    }

    private void refreshListener() {
        if (active != this) return;
        main.removeCallbacks(refresh);
        Inet4Address current = WebAdmin.findLanAddress(networks);
        if (server != null && server.isOpen() && current != null && current.equals(address)) return;
        closeListener();
        if (current == null) {
            unavailable = "请先将电视连接到家庭局域网。";
            return;
        }
        try {
            server = new WebAdmin(this, store, current, () -> main.postDelayed(refresh, RETRY_MILLIS));
            address = current;
        } catch (IOException e) {
            unavailable = "暂时无法监听端口 " + LanHttpServer.PORT + "，正在重试。";
            main.postDelayed(refresh, RETRY_MILLIS);
        }
    }

    private void closeListener() {
        if (server != null) server.close();
        server = null;
        address = null;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        if (active == this) {
            active = null;
            networks.unregisterNetworkCallback(networkChanges);
            main.removeCallbacks(refresh);
            closeListener();
        }
        super.onDestroy();
    }
}
