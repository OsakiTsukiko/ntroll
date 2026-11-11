package com.ntroll.app;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.ConnectivityManager.NetworkCallback;
import android.os.*;
import android.util.Log;
import java.io.IOException;
import java.util.*;

public final class FirewallService extends VpnService {
    static volatile boolean active;
    static volatile String status = "Firewall is off";
    static final String STOP = "com.ntroll.app.STOP";
    private static final String CHANNEL = "firewall";
    private HandlerThread manager;
    private Handler handler;
    private ConnectivityManager connectivity;
    private NetworkCallback callback;
    private ParcelFileDescriptor tunnel;
    private Network network;
    private Network appliedNetwork;
    private boolean refreshQueued;
    private String statusProfile;
    private int transport;
    private boolean roamingOrUnknown = true;
    private volatile boolean screenRules;
    private Map<String, Integer> appliedPackages;
    private final Map<Integer, List<String>> installedGroups = new HashMap<>();
    private boolean inventoryDirty = true;
    private static final Set<Runnable> observers = new java.util.concurrent.CopyOnWriteArraySet<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    static void observe(Runnable observer) { observers.add(observer); }
    static void unobserve(Runnable observer) { observers.remove(observer); }
    private volatile boolean destroyed;
    private final Map<Network, NetworkCapabilities> candidates = new HashMap<>();
    private final Runnable refresh = () -> {
        synchronized (this) { refreshQueued = false; }
        applyRules();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener rulesChanged = (prefs, key) -> {
        if (key == null || Rules.isRuleKey(key) || key.equals("active_profile")
                || key.equals("profile_name:" + Rules.activeProfile(this))) schedule();
    };
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            handler.post(() -> { inventoryDirty = true; schedule(); });
        }
    };

    private final BroadcastReceiver screenChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (screenRules) schedule(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        manager = new HandlerThread("firewall-manager");
        manager.start();
        handler = new Handler(manager.getLooper());
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Local firewall", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        startForeground(1, notification("Starting local firewall…"));
        Rules.prefs(this).registerOnSharedPreferenceChangeListener(rulesChanged);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packagesChanged, filter);
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenChanged, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenChanged, screenFilter);
        callback = new NetworkCallback() {
            @Override public void onAvailable(Network n) { /* Wait for capabilities. */ }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                dispatch(() -> { candidates.put(n, caps); selectNetwork(n, caps); });
            }
            @Override public void onLost(Network n) {
                dispatch(() -> {
                    candidates.remove(n);
                    if (n.equals(network)) { network = null; transport = 0; chooseFallback(); schedule(); }
                });
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
        // Best matching gives the actual preferred physical connection, even while
        // our own UID uses the VPN sentinel. Earlier APIs use a conservative fallback.
        if (Build.VERSION.SDK_INT >= 31) connectivity.registerBestMatchingNetworkCallback(request, callback, handler);
        else connectivity.registerNetworkCallback(request, callback);
    }

    private static int kind(NetworkCapabilities caps) {
        boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        if (wifi && !mobile) return 1;
        if (mobile && !wifi) return 2;
        return 0;
    }
    private void selectNetwork(Network n, NetworkCapabilities caps) {
        Network previous = network;
        int previousTransport = transport;
        boolean previousRoaming = roamingOrUnknown;
        if (Build.VERSION.SDK_INT >= 31) {
            network = n;
            transport = kind(caps);
            roamingOrUnknown = roaming(caps);
        } else chooseFallback();
        // Signal strength/bandwidth/capability updates must not wake the planner.
        if (!Objects.equals(previous, network) || previousTransport != transport || previousRoaming != roamingOrUnknown) schedule();
    }
    private static boolean roaming(NetworkCapabilities caps) {
        return caps == null || Build.VERSION.SDK_INT < 28
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
    }
    private void chooseFallback() {
        Network best = null;
        int score = -1;
        for (Map.Entry<Network, NetworkCapabilities> entry : candidates.entrySet()) {
            NetworkCapabilities caps = entry.getValue();
            int rank = (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0)
                + (kind(caps) == 1 ? 2 : 1);
            if (rank > score) { best = entry.getKey(); score = rank; }
        }
        network = best;
        transport = best == null ? 0 : kind(candidates.get(best));
        roamingOrUnknown = roaming(best == null ? null : candidates.get(best));
    }
    private void dispatch(Runnable action) {
        if (destroyed) return;
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(() -> { if (!destroyed) action.run(); });
    }
    private synchronized void schedule() {
        if (handler == null || destroyed || refreshQueued) return;
        refreshQueued = true;
        handler.post(refresh);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean enabled = getSharedPreferences("service", MODE_PRIVATE).getBoolean("enabled", false);
        if (!enabled || prepare(this) != null) { stopSelf(); return START_NOT_STICKY; }
        schedule();
        return START_STICKY;
    }

    private void applyRules() {
        if (destroyed) return;
        try {
            if (inventoryDirty) {
                installedGroups.clear();
                for (ApplicationInfo app : getPackageManager().getInstalledApplications(0)) {
                    if (app.uid == android.os.Process.myUid()) continue;
                    List<String> names = installedGroups.get(app.uid);
                    if (names == null) { names = new ArrayList<>(); installedGroups.put(app.uid, names); }
                    names.add(app.packageName);
                }
                inventoryDirty = false;
            }
            Map<String, ?> preferences = Rules.snapshot(this);
            screenRules = false;
            for (Map.Entry<String, ?> entry : preferences.entrySet()) {
                if (entry.getKey().startsWith("screen_") && Boolean.TRUE.equals(entry.getValue())) { screenRules = true; break; }
            }
            boolean screenOn = getSystemService(PowerManager.class).isInteractive();
            Map<String, Integer> blocked = FirewallPlan.blockedPackages(installedGroups,
                preferences, NativeFirewall.blockMask(transport), screenOn, roamingOrUnknown);
            if (blocked.isEmpty()) {
                // No restricted apps on this transport: release the TUN and its
                // queue. Keep only the event-driven rule/network monitor alive.
                if (tunnel != null) { tunnel.close(); tunnel = null; }
                appliedPackages = blocked;
                appliedNetwork = null;
                active = true;
                publishStatus(0);
                return;
            }
            if (tunnel != null && blocked.equals(appliedPackages)) {
                // Moving networks or changing an inactive rule does not need a new
                // VPN interface or reset existing app connections.
                if (!Objects.equals(appliedNetwork, network)) {
                    if (!setUnderlyingNetworks(network == null ? new Network[0] : new Network[]{network})) {
                        throw new IOException("Could not update the underlying network");
                    }
                    appliedNetwork = network;
                }
                publishStatus(blocked.size());
                return;
            }
            Builder builder = new Builder().setSession("ntroll local firewall")
                .setMtu(1500).setBlocking(false)
                .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd42:6e74:726f::1", 128).addRoute("::", 0)
                .addDnsServer("192.0.2.53")
                .setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            // An empty allowed-app list means ALL apps. Always add our own UID as
            // a sentinel so that zero blocked apps never captures the whole phone.
            builder.addAllowedApplication(getPackageName());
            for (String name : blocked.keySet()) {
                try { builder.addAllowedApplication(name); }
                catch (PackageManager.NameNotFoundException removed) { Log.i("ntroll", "Package removed: " + name); }
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            builder.setUnderlyingNetworks(network == null ? new Network[0] : new Network[]{network});
            ParcelFileDescriptor next = builder.establish();
            if (next == null) throw new IOException("VPN permission was revoked");
            ParcelFileDescriptor previous = tunnel;
            tunnel = next;
            if (previous != null) previous.close();
            // Intentionally do not read the TUN. The bounded kernel queue retains
            // then drops blocked packets. No userspace copies, packet inspection,
            // worker wakeups, counters, or relay sockets are needed for a blackhole.
            appliedPackages = blocked;
            appliedNetwork = network;
            active = true;
            publishStatus(blocked.size());
        } catch (Exception error) {
            Log.e("ntroll", "Could not apply firewall rules", error);
            status = "Could not apply rules: " + error.getMessage();
            // establish() may already have replaced the previous interface. Never
            // report protection as active after a partially completed replacement.
            active = false;
            getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            notifyActivity();
            stopSelf();
        }
    }

    private void publishStatus(int blockedCount) {
        String label = transport == 1 ? "Wi-Fi" : transport == 2 ? "Mobile data" : "Unknown / other network";
        String next = label + (blockedCount == 0
            ? " · no apps blocked · VPN idle"
            : " · " + blockedCount + " apps blocked · VPN active");
        String profile = Rules.profileName(this);
        if (!next.equals(status) || !profile.equals(statusProfile)) {
            statusProfile = profile;
            status = next;
            getSystemService(NotificationManager.class).notify(1, notification(status));
            notifyActivity();
        }
    }
    private void notifyActivity() {
        for (Runnable observer : observers) ui.post(() -> {
            if (observers.contains(observer)) observer.run();
        });
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FirewallService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_shield).setContentTitle("ntroll · " + Rules.profileName(this))
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
    @Override public void onRevoke() {
        getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        stopSelf();
    }
    @Override public void onDestroy() {
        destroyed = true;
        active = false;
        if (!status.startsWith("Could not")) status = "Firewall is off";
        Rules.prefs(this).unregisterOnSharedPreferenceChangeListener(rulesChanged);
        if (callback != null) connectivity.unregisterNetworkCallback(callback);
        unregisterReceiver(packagesChanged);
        unregisterReceiver(screenChanged);
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            if (tunnel != null) { try { tunnel.close(); } catch (IOException ignored) { } tunnel = null; }
            manager.quitSafely();
        });
        notifyActivity();
        stopForeground(true);
        super.onDestroy();
    }

}
