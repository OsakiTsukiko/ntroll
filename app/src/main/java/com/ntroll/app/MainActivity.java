package com.ntroll.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import java.io.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int VPN_REQUEST = 100, NOTIFICATION_REQUEST = 101, EXPORT = 102, IMPORT = 103;
    private static final int WHITE = Color.rgb(239, 245, 247), MUTED = Color.rgb(156, 176, 188);
    // The UI loader releases its worker after a short idle period; no permanent
    // icon thread, polling timer, wake lock, or battery-optimization exemption.
    private final ThreadPoolExecutor loader = new ThreadPoolExecutor(0, 1, 5,
        TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>(), visible = new ArrayList<>();
    private final Map<Integer, List<String>> groups = new HashMap<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(32);
    private final Set<String> pendingIcons = new HashSet<>();
    private final Set<String> expanded = new HashSet<>();
    private Map<String, Boolean> ruleSnapshot = new HashMap<>();
    private AppAdapter adapter;
    private TextView status, count, empty;
    private int BG = 0xff0b1018, SURFACE = 0xff141e2b, BORDER = 0xff263649, ACCENT = 0xff72f5bd;
    private LinearLayout screen, profileInfo, profileSection;
    private ProfileCard profileCard;
    private TextView profileTitle, profilePosition, artwork;
    private Button previousProfile, nextProfile;
    private int profileDirection = 1;
    private final Map<View, Integer> themedSurfaces = new WeakHashMap<>();
    private final Map<Button, Boolean> themedButtons = new WeakHashMap<>();
    private final Set<TextView> accentTexts = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<CompoundButton> themedControls = Collections.newSetFromMap(new WeakHashMap<>());
    private Button power;
    private String currentProfile;
    private EditText search;
    private CheckBox system, blockedOnly, internetOnly;
    private ListView list;
    private boolean blockedFirst, loading, rulesRefreshQueued;
    private volatile boolean resumed;
    private Parcelable pendingScroll;
    private final Runnable stateChanged = this::updateStatus;
    private final SharedPreferences.OnSharedPreferenceChangeListener rulesChanged = (prefs, key) -> {
        if (rulesRefreshQueued) return;
        rulesRefreshQueued = true;
        main.post(() -> {
            rulesRefreshQueued = false;
            if (!isDestroyed()) { refreshProfile(); ruleSnapshot = Rules.snapshot(this); filter(); }
        });
    };

    private void updateStatus() {
        boolean requested = getSharedPreferences("service", MODE_PRIVATE).getBoolean("enabled", false);
        power.setText(requested ? "■  Stop" : "▶  Start");
        status.setText(FirewallService.status);
        status.setTextColor(FirewallService.active ? ACCENT : MUTED);
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ruleSnapshot = Rules.snapshot(this);
        currentProfile = Rules.activeProfile(this);
        setPalette(Rules.profileColor(this, currentProfile));
        blockedFirst = getPreferences(MODE_PRIVATE).getBoolean("blocked_first", false);
        if (saved != null) {
            pendingScroll = saved.getParcelable("scroll");
            ArrayList<String> open = saved.getStringArrayList("expanded"); if (open != null) expanded.addAll(open);

        }
        LinearLayout root = new LinearLayout(this); screen = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        root.setBackgroundColor(BG);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("> ntroll_", 29, WHITE); title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button more = new Button(this); more.setText("⋮"); more.setTextSize(24); more.setContentDescription("More actions");
        heading.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        styleButton(more, false); more.setPadding(0, 0, 0, 0);
        more.setOnClickListener(this::showMenu);
        root.addView(heading);
        TextView subtitle = text("Per-app Wi-Fi & mobile data controls", 12, MUTED);
        subtitle.setPadding(0, dp(6), 0, dp(16)); root.addView(subtitle);
        LinearLayout console = new LinearLayout(this); console.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout firewallInfo = new LinearLayout(this); firewallInfo.setOrientation(LinearLayout.VERTICAL);
        console.addView(firewallInfo,new LinearLayout.LayoutParams(0,-2,1));
        console.setPadding(dp(16), dp(12), dp(16), dp(12)); bindSurface(console, 20);
        TextView consoleTitle = text("●  LOCAL FIREWALL", 11, ACCENT); consoleTitle.setTypeface(Typeface.MONOSPACE); firewallInfo.addView(consoleTitle);
        root.addView(console);
        profileCard = new ProfileCard(); profileCard.setGravity(Gravity.CENTER_VERTICAL);
        profileCard.setPadding(dp(10),dp(10),dp(4),dp(10)); bindSurface(profileCard, 12);
        artwork = text(">_", 24, BG); artwork.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); artwork.setGravity(Gravity.CENTER);
        artwork.setClickable(true); artwork.setFocusable(true); artwork.setContentDescription("Change profile color");
        artwork.setOnClickListener(v -> chooseProfileColor());
        profileCard.addView(artwork, new LinearLayout.LayoutParams(dp(52),dp(52)));
        profileInfo = new LinearLayout(this); profileInfo.setOrientation(LinearLayout.VERTICAL); profileInfo.setGravity(Gravity.CENTER_VERTICAL);
        profileInfo.setPadding(dp(12),0,dp(4),0); profileInfo.setMinimumHeight(dp(52)); profileInfo.setClickable(true); profileInfo.setFocusable(true);
        profilePosition = text("", 9, MUTED); profilePosition.setTypeface(Typeface.MONOSPACE);
        profileTitle = text("", 21, WHITE); profileTitle.setTypeface(null,Typeface.BOLD); profileTitle.setMaxLines(1); profileTitle.setEllipsize(TextUtils.TruncateAt.END);
        profileInfo.addView(profilePosition); profileInfo.addView(profileTitle); profileInfo.setOnClickListener(v -> showProfiles());
        profileCard.addView(profileInfo, new LinearLayout.LayoutParams(0,-2,1));
        previousProfile = playerButton(false); nextProfile = playerButton(true);
        LinearLayout.LayoutParams previousParams = new LinearLayout.LayoutParams(dp(48),dp(48)); previousParams.rightMargin = dp(3);
        profileCard.addView(previousProfile,previousParams);
        profileCard.addView(nextProfile,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(-1,-2); profileParams.topMargin = dp(10);
        profileSection = new LinearLayout(this); profileSection.setOrientation(LinearLayout.VERTICAL);
        profileSection.setPadding(dp(12),dp(4),dp(12),dp(12)); bindSurface(profileSection,24);
        root.addView(profileSection,profileParams);
        profileSection.addView(profileCard); themedSurfaces.remove(profileCard); profileCard.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout profileActions = new LinearLayout(this);
        action(profileActions,"+ Add",false,() -> nameProfile(null,false));
        action(profileActions,"Edit",false,() -> nameProfile(currentProfile,false));
        action(profileActions,"Change",false,this::showProfiles);
        profileSection.addView(profileActions); refreshProfile();
        status = text("Firewall is off", 12, MUTED); status.setPadding(0, dp(6), dp(10), 0); firewallInfo.addView(status);
        power = new Button(this); power.setText("Start firewall");
        styleButton(power, true);
        console.addView(power, new LinearLayout.LayoutParams(dp(100), dp(48)));
        power.setContentDescription("Start or stop firewall");
        power.setOnClickListener(v -> {
            if (getSharedPreferences("service", MODE_PRIVATE).getBoolean("enabled", false)) {
                getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
                stopService(new Intent(this, FirewallService.class));
                FirewallService.status = "Firewall is off";
            } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            } else prepareVpn();
            updateStatus();
        });
        TextView hint = text("ON = blocked · tap an app for advanced rules", 12, MUTED);
        hint.setPadding(0, dp(14), 0, dp(10)); root.addView(hint);
        search = new EditText(this); search.setSingleLine(true); search.setTextColor(WHITE);
        search.setHintTextColor(MUTED); search.setHint("Find app or package…"); search.setTextSize(15);
        LinearLayout searchBar = new LinearLayout(this); searchBar.setGravity(Gravity.CENTER_VERTICAL);
        bindSurface(searchBar, 12);
        search.setBackgroundColor(Color.TRANSPARENT); search.setPadding(dp(12), 0, 0, 0);
        searchBar.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button clear = new Button(this); clear.setText("×"); clear.setTextSize(22); clear.setContentDescription("Clear search");
        styleButton(clear, false); clear.setPadding(0,0,0,0); searchBar.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        clear.setOnClickListener(v -> search.setText("")); root.addView(searchBar);
        LinearLayout filters = new LinearLayout(this);
        system = check("System", "show_system", false);
        blockedOnly = check("Blocked", "blocked_only", false);
        internetOnly = check("Internet", "internet_only", true);
        system.setContentDescription("Show system apps");
        blockedOnly.setContentDescription("Show only apps with saved block rules");
        internetOnly.setContentDescription("Show only apps with internet permission");
        for (CheckBox check : new CheckBox[]{system, blockedOnly, internetOnly}) filters.addView(check, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(filters);
        count = text("Loading installed apps…", 11, ACCENT); count.setTypeface(Typeface.MONOSPACE); root.addView(count);
        LinearLayout labels = new LinearLayout(this);
        labels.addView(text("APPLICATION", 11, MUTED), new LinearLayout.LayoutParams(0, dp(30), 1));
        for (String label : new String[]{"BLOCK WI-FI", "BLOCK DATA"}) {
            TextView view = text(label, 10, MUTED); view.setGravity(Gravity.CENTER);
            labels.addView(view, new LinearLayout.LayoutParams(dp(68), dp(30)));
        }
        root.addView(labels);
        list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(BG)); list.setDividerHeight(dp(6));
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new AppAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((p, view, pos, id) -> {
            String name = visible.get(pos).packageName;
            if (!expanded.remove(name)) expanded.add(name);
            adapter.notifyDataSetChanged();
        });
        list.setOnItemLongClickListener((p, view, pos, id) -> { appSettings(visible.get(pos)); return true; });
        FrameLayout content = new FrameLayout(this); content.addView(list, new FrameLayout.LayoutParams(-1,-1));
        empty = text("Loading installed apps…", 15, MUTED); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(24),dp(24),dp(24),dp(24));
        content.addView(empty, new FrameLayout.LayoutParams(-1,-1)); list.setEmptyView(empty);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        if (motionEnabled()) { console.setAlpha(0); console.setTranslationY(dp(8)); console.animate().alpha(1).translationY(0).setDuration(220).start(); }
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int c, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int c) { filter(); }
            public void afterTextChanged(Editable e) {}
        });
        if (saved != null) search.setText(saved.getString("search", ""));
        root.requestFocus();
        applyPalette();
        loadApps();
    }
    private CheckBox check(String label, String key, boolean fallback) {
        CheckBox view = new CheckBox(this); view.setText(label); view.setTextSize(12); view.setTextColor(MUTED);
        view.setChecked(getPreferences(MODE_PRIVATE).getBoolean(key, fallback));
        themedControls.add(view); paintControl(view);
        view.setOnCheckedChangeListener((button, value) -> { getPreferences(MODE_PRIVATE).edit().putBoolean(key, value).apply(); filter(); });
        return view;
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putStringArrayList("expanded", new ArrayList<>(expanded));
        out.putString("search", search.getText().toString()); out.putParcelable("scroll", list.onSaveInstanceState());
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        FirewallService.observe(stateChanged);
        Rules.prefs(this).registerOnSharedPreferenceChangeListener(rulesChanged);
        refreshProfile(); ruleSnapshot = Rules.snapshot(this); filter();
        if (getSharedPreferences("service", MODE_PRIVATE).getBoolean("enabled", false) && !FirewallService.active) {
            if (VpnService.prepare(this) == null) startFirewall();
            else getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        }
        updateStatus();
    }
    @Override protected void onPause() {
        resumed = false; FirewallService.unobserve(stateChanged);
        Rules.prefs(this).unregisterOnSharedPreferenceChangeListener(rulesChanged); super.onPause();
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) icons.evictAll();
    }
    @Override protected void onDestroy() { loader.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private void prepareVpn() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, VPN_REQUEST); else startFirewall();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATION_REQUEST) prepareVpn();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request == EXPORT || request == IMPORT) && result == RESULT_OK && data != null && data.getData() != null) {
            transferBackup(request, data.getData());
        } else if (request == VPN_REQUEST) {
            if (result == RESULT_OK) startFirewall();
            else Toast.makeText(this, "VPN approval is needed to block apps", Toast.LENGTH_LONG).show();
        }
    }
    private void startFirewall() {
        try {
            getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
            FirewallService.status = "Starting local firewall…";
            Intent intent = new Intent(this, FirewallService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        } catch (RuntimeException error) {
            getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            FirewallService.status = "Could not start firewall: " + error.getMessage();
        }
        updateStatus();
    }
    private void loadApps() {
        if (loading) return;
        loading = true; count.setText("Loading installed apps…");
        loader.execute(() -> {
            try {
            PackageManager pm = getPackageManager(); List<App> loaded = new ArrayList<>();
            Map<Integer, List<String>> loadedGroups = new HashMap<>();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (Thread.currentThread().isInterrupted()) return;
                if (info.uid == android.os.Process.myUid()) continue;
                App app = new App(); app.info = info; app.uid = info.uid; app.packageName = info.packageName;
                app.label = info.loadLabel(pm).toString();
                app.searchKey = (app.label + "\n" + app.packageName).toLowerCase(Locale.ROOT);
                app.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                app.internet = pm.checkPermission(Manifest.permission.INTERNET, info.packageName) == PackageManager.PERMISSION_GRANTED;
                loaded.add(app);
                List<String> names = loadedGroups.get(app.uid);
                if (names == null) { names = new ArrayList<>(); loadedGroups.put(app.uid, names); }
                names.add(app.packageName);
            }
            Collections.sort(loaded, (a, b) -> a.label.compareToIgnoreCase(b.label));
            main.post(() -> {
                if (isDestroyed()) return;
                loading = false; groups.clear(); groups.putAll(loadedGroups); apps.clear(); apps.addAll(loaded);
                for (App app : apps) app.linked = groups.get(app.uid);
                icons.evictAll(); ruleSnapshot = Rules.snapshot(this); filter();
                if (pendingScroll != null) { list.onRestoreInstanceState(pendingScroll); pendingScroll = null; }
            });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    loading = false; filter();
                    empty.setText("Could not load apps\nUse Refresh installed apps to retry.");
                    Toast.makeText(this, "Could not load apps. Try refreshing.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    private int restrictions(App app) { return FirewallPlan.restrictions(app.linked, ruleSnapshot); }
    private void filter() {
        if (adapter == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT); visible.clear();
        int restricted = 0;
        for (App app : apps) {
            app.restrictions = restrictions(app);
            boolean hasRules = FirewallPlan.hasRules(app.linked, ruleSnapshot);
            if (hasRules) restricted++;
            if (!system.isChecked() && app.system) continue;
            if (blockedOnly.isChecked() && !hasRules) continue;
            if (internetOnly.isChecked() && !app.internet) continue;
            if (app.searchKey.contains(query)) visible.add(app);
        }
        if (blockedFirst) Collections.sort(visible, (a, b) -> Boolean.compare(FirewallPlan.hasRules(b.linked, ruleSnapshot), FirewallPlan.hasRules(a.linked, ruleSnapshot)));
        count.setText(loading ? "Reading installed packages…" : visible.size() + " apps / " + restricted + " with saved blocks");
        empty.setText(loading ? "Reading installed packages…" : "No matching apps\n\nTry another search or adjust the filters.");
        adapter.notifyDataSetChanged();
    }
    private void change(Set<Integer> uids, int mask, boolean block) {
        Rules.apply(this, FirewallPlan.edits(groups, uids, mask, block));
    }
    private void changeOption(App app, String option, boolean enabled) {
        Rules.apply(this, FirewallPlan.optionEdits(app.linked, option, enabled));
    }
    private void bulk(int mask, boolean block) {
        Set<Integer> uids = new HashSet<>(); int affected = 0;
        for (App app : visible) if (app.internet) { uids.add(app.uid); affected++; }
        if (uids.isEmpty()) { Toast.makeText(this, "No eligible apps in this view", Toast.LENGTH_SHORT).show(); return; }
        String network = mask == 1 ? "Wi-Fi" : mask == 2 ? "mobile data" : "Wi-Fi and mobile data";
        new AlertDialog.Builder(this).setTitle((block ? "Block " : "Allow ") + network)
            .setMessage("Apply to " + affected + " visible apps with internet permission? Linked apps sharing their UID are affected too. Advanced rules (including Lockdown and roaming) remain in effect.")
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", (d, which) -> change(uids, mask, block)).show();
    }
    private void choices(String title, String[] labels, Runnable[] actions) {
        LinearLayout body=sheetBody(); Dialog[] dialog=new Dialog[1];
        for(int i=0;i<labels.length;i++) {
            final int index=i; LinearLayout row=new LinearLayout(this); body.addView(row);
            Button button=action(row,labels[i],false,() -> { dialog[0].dismiss(); actions[index].run(); });
            button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); button.setPadding(dp(16),0,dp(16),0);
        }
        dialog[0]=sheet(title,body);
    }
    private void showMenu(View anchor) {
        boolean reduced=getPreferences(MODE_PRIVATE).getBoolean("reduce_motion",false);
        choices("Tools & settings",new String[]{"Profiles", "Bulk rules", (blockedFirst ? "✓  " : "")+"Blocked apps first",
            "Refresh installed apps", "Export profile rules", "Import profile rules", (reduced ? "✓  " : "")+"Reduce motion", "Battery & help"},
            new Runnable[]{this::showProfiles, () -> choices("Visible apps only",
                new String[]{"Block Wi-Fi","Allow Wi-Fi","Block mobile data","Allow mobile data","Block both","Allow both"},
                new Runnable[]{() -> bulk(1,true),() -> bulk(1,false),() -> bulk(2,true),() -> bulk(2,false),() -> bulk(3,true),() -> bulk(3,false)}),
                () -> { blockedFirst=!blockedFirst; getPreferences(MODE_PRIVATE).edit().putBoolean("blocked_first",blockedFirst).apply(); filter(); },
                this::loadApps,() -> pickBackup(true),() -> pickBackup(false),
                () -> { getPreferences(MODE_PRIVATE).edit().putBoolean("reduce_motion",!reduced).apply(); adapter.notifyDataSetChanged(); },
                () -> { LinearLayout body=sheetBody(); body.addView(text("No wake locks, periodic polling, traffic counters, or remote server. Allowed traffic uses Android's normal connection.\n\nWhen no apps are blocked on the current network, the VPN interface closes automatically; the firewall keeps monitoring saved rules. The VPN key returns when blocking is needed.\n\nON means blocked. Filters and sorting are saved. Tap an app to expand screen-on exceptions, roaming blocking, and per-app Lockdown. Screen-on means the device is interactive, including its lock screen. Roaming blocks override screen exceptions; Lockdown overrides all exceptions while the firewall runs. Hold an app for Android settings.\n\nWi-Fi/data rules follow the active connection. Network handovers are asynchronous. Do not enable Android's ‘Block connections without VPN’ for this firewall. A blocked app may still retry or wake itself; total battery use depends on those apps too.",14,MUTED)); sheet("Battery & local VPN",body); }});
    }
    private void showAppMenu(View anchor, App app) {
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        if(app.internet) {
            labels.add("Block both networks"); actions.add(() -> change(Collections.singleton(app.uid),3,true));
            labels.add("Clear Wi-Fi & mobile base blocks"); actions.add(() -> change(Collections.singleton(app.uid),3,false));
            labels.add("Reset all rules"); actions.add(() -> confirmAction("Reset all rules?",
                "Clear blocks, screen exceptions, roaming blocking, and Lockdown for "+app.label+" and any apps sharing its UID?","Reset",() -> {
                    Map<String,Boolean> edits=new HashMap<>();
                    for(String key:new String[]{"wifi","mobile","screen_wifi","screen_mobile","roaming","lockdown"})
                        edits.putAll(FirewallPlan.optionEdits(app.linked,key,false));
                    Rules.apply(this,edits);
                }));
        }
        labels.add("Android app settings"); actions.add(() -> appSettings(app));
        labels.add("Copy package name"); actions.add(() -> {
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Package name",app.packageName));
            Toast.makeText(this,"Package name copied",Toast.LENGTH_SHORT).show();
        });
        choices(app.label,labels.toArray(new String[0]),actions.toArray(new Runnable[0]));
    }
    private void confirmAction(String title,String message,String label,Runnable accepted) {
        LinearLayout body=sheetBody(); body.addView(text(message,15,MUTED));
        LinearLayout row=new LinearLayout(this); body.addView(row); Dialog[] dialog=new Dialog[1];
        action(row,"Cancel",false,() -> dialog[0].dismiss());
        action(row,label,true,() -> { dialog[0].dismiss(); accepted.run(); });
        dialog[0]=sheet(title,body);
    }
    private void appSettings(App app) {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName))); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "App settings unavailable", Toast.LENGTH_SHORT).show(); }
    }
    private final class ProfileCard extends LinearLayout {
        private float startX, startY;
        private boolean dragging;
        ProfileCard() { super(MainActivity.this); setClickable(true); setOnClickListener(v -> showProfiles()); }
        @Override public boolean performClick() { return super.performClick(); }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { startX = event.getX(); startY = event.getY(); dragging = false; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && Math.abs(event.getX()-startX) > dp(40) && Math.abs(event.getX()-startX) > Math.abs(event.getY()-startY)*1.5f) {
                dragging = true; return true;
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (dragging) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { cycleProfile(event.getX() < startX ? 1 : -1); dragging = false; }
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = false;
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
    private Button playerButton(boolean next) {
        Button button = new Button(this); styleButton(button, false); button.setPadding(0,0,0,0);
        button.setText(next ? "⏭︎" : "⏮︎"); button.setTextSize(21);
        button.setContentDescription(next ? "Next profile" : "Previous profile");
        button.setOnClickListener(v -> cycleProfile(next ? 1 : -1)); return button;
    }
    private void cycleProfile(int direction) {
        List<Map.Entry<String, String>> entries = profileEntries(false);
        if (entries.size() < 2) return;
        int position = 0;
        for (int i=0;i<entries.size();i++) if (entries.get(i).getKey().equals(Rules.activeProfile(this))) position = i;
        profileDirection = direction;
        Rules.switchProfile(this, entries.get((position+direction+entries.size())%entries.size()).getKey());
        refreshProfile(); ruleSnapshot = Rules.snapshot(this); filter();
    }
    private void refreshProfile() {
        String selected = Rules.activeProfile(this);
        boolean changed = !selected.equals(currentProfile);
        boolean recolor = ACCENT != Rules.profileColor(this, selected);
        currentProfile = selected;
        if (recolor) { setPalette(Rules.profileColor(this, selected)); applyPalette(); }
        if (profileTitle != null) {
            profileTitle.setText(Rules.profileName(this));
            List<Map.Entry<String, String>> entries = profileEntries(false); int position = 0;
            for (int i=0;i<entries.size();i++) if (entries.get(i).getKey().equals(selected)) position = i;
            profilePosition.setText("PROFILE  " + (position+1) + " / " + entries.size());
            profileInfo.setContentDescription("Current profile: " + Rules.profileName(this) + ". Choose a profile");
            if (previousProfile != null) { previousProfile.setEnabled(entries.size()>1); nextProfile.setEnabled(entries.size()>1); }
            paintArtwork();
            if (changed || recolor) {
                for (View view : new View[]{profileInfo, artwork}) {
                    view.animate().cancel(); view.setAlpha(1); view.setTranslationX(0); view.setScaleX(1); view.setScaleY(1);
                    if (motionEnabled()) {
                        view.setAlpha(0); view.setTranslationX(dp(12)*profileDirection);
                        view.animate().alpha(1).translationX(0).setDuration(180).start();
                    }
                }
            }
        }
    }
    private void chooseProfileColor() { nameProfile(currentProfile, false); }
    private CharSequence coloredLabel(String name, int color) {
        SpannableString label = new SpannableString("●  " + name);
        label.setSpan(new ForegroundColorSpan(color),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); return label;
    }
    private List<Map.Entry<String, String>> profileEntries(boolean inactiveOnly) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(Rules.profiles(this).entrySet());
        if (inactiveOnly) {
            String active = Rules.activeProfile(this);
            for (Iterator<Map.Entry<String, String>> it = entries.iterator(); it.hasNext();) if (it.next().getKey().equals(active)) it.remove();
        }
        Collections.sort(entries, (a,b) -> a.getValue().compareToIgnoreCase(b.getValue())); return entries;
    }
    // Custom sheets keep profile actions visible and avoid nested platform menus.
    private Dialog sheet(String title, LinearLayout body) {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(12),dp(20),dp(24)); bindSurface(content,28);
        TextView handle = text("────",16,MUTED); handle.setGravity(Gravity.CENTER); content.addView(handle);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(title,22,WHITE); label.setTypeface(null,Typeface.BOLD);
        heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button close = new Button(this); close.setText("×"); styleButton(close,false); close.setTextSize(22);
        close.setContentDescription("Close"); close.setPadding(0,0,0,0);
        heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48))); close.setOnClickListener(v -> dialog.dismiss());
        content.addView(heading);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(body);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        dialog.setContentView(content); Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.65f); window.setGravity(Gravity.BOTTOM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show(); window.setLayout(-1,-2);
        content.post(() -> {
            int max = (int)(getResources().getDisplayMetrics().heightPixels * 0.8f);
            if (content.getHeight()>max) window.setLayout(-1,max);
        });
        return dialog;
    }
    private LinearLayout sheetBody() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0,dp(16),0,0); return body;
    }
    private Button action(LinearLayout row,String label,boolean primary,Runnable click) {
        Button button = new Button(this); button.setText(label); styleButton(button,primary);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(6),0,dp(6),0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,dp(48),1);
        params.setMargins(dp(3),dp(6),dp(3),0); row.addView(button,params);
        button.setOnClickListener(v -> click.run()); return button;
    }
    private void showProfiles() {
        LinearLayout body = sheetBody();
        TextView hint = text("Tap a profile to activate its saved rules. Switch away from a profile before removing it.",13,MUTED);
        body.addView(hint);
        Dialog[] dialog = new Dialog[1];
        for (Map.Entry<String,String> entry : profileEntries(false)) {
            boolean active = entry.getKey().equals(currentProfile);
            LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(10),dp(8),dp(10),dp(10));
            int color = Rules.profileColor(this,entry.getKey());
            tile.setBackground(surface(blend(SURFACE,color,0.08f),active ? color : BORDER,20));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(12); body.addView(tile,params);
            Button select = new Button(this); select.setAllCaps(false); select.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            select.setText(coloredLabel(entry.getValue() + (active ? "  ·  Active" : ""),color));
            select.setSingleLine(true); select.setEllipsize(TextUtils.TruncateAt.END);
            select.setTextColor(WHITE); select.setTextSize(17); select.setBackgroundColor(Color.TRANSPARENT);
            tile.addView(select,new LinearLayout.LayoutParams(-1,dp(52)));
            select.setOnClickListener(v -> { Rules.switchProfile(this,entry.getKey()); refreshProfile(); ruleSnapshot=Rules.snapshot(this); filter(); dialog[0].dismiss(); });
            LinearLayout row = new LinearLayout(this); tile.addView(row);
            action(row,"Edit",false,() -> { dialog[0].dismiss(); nameProfile(entry.getKey(),false); });
            Button remove = action(row,"Remove",false,() -> { dialog[0].dismiss(); confirmDelete(entry.getKey(),entry.getValue()); });
            remove.setEnabled(!active);
        }
        LinearLayout actions = new LinearLayout(this); body.addView(actions);
        action(actions,"+ New profile",true,() -> { dialog[0].dismiss(); nameProfile(null,false); });
        action(actions,"Duplicate",false,() -> { dialog[0].dismiss(); nameProfile(null,true); });
        dialog[0] = sheet("Profiles",body);
    }
    private void manageProfiles() { showProfiles(); }
    private void nameProfile(String renameId, boolean duplicate) {
        LinearLayout body = sheetBody();
        TextView description = text(renameId != null ? "Edit the name and accent color. Rules save automatically." : duplicate ? "Copy the active profile’s rules into a new profile." : "Start with no blocks. The new profile becomes active.",13,MUTED);
        body.addView(description);
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(18); input.setTextColor(WHITE); input.setHintTextColor(MUTED);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)}); input.setHint("Profile name");
        input.setText(renameId == null ? "" : Rules.profiles(this).get(renameId));
        input.setPadding(dp(16),0,dp(16),0); bindSurface(input,16);
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(-1,dp(56)); field.topMargin=dp(20); field.bottomMargin=dp(20); body.addView(input,field);
        body.addView(text("ACCENT COLOR",11,MUTED));
        int[] selected = {renameId == null ? Rules.profiles(this).size()%ProfileColors.COLORS.length : Rules.profileColorIndex(this,renameId)};
        Button[] swatches = new Button[ProfileColors.COLORS.length];
        Runnable paint = () -> { for(int i=0;i<swatches.length;i++) if(swatches[i]!=null) {
            swatches[i].setText((selected[0]==i ? "✓ " : "")+ProfileColors.NAMES[i]);
            swatches[i].setSelected(selected[0]==i);
            swatches[i].setBackground(surface(blend(SURFACE,ProfileColors.COLORS[i],selected[0]==i ? 0.22f : 0.04f),selected[0]==i ? ProfileColors.COLORS[i] : BORDER,16));
        }};
        for(int r=0;r<4;r++) {
            LinearLayout row = new LinearLayout(this); body.addView(row);
            for(int c=0;c<2;c++) {
                final int index=r*2+c;
                Button swatch=action(row,ProfileColors.NAMES[index],false,() -> { selected[0]=index; paint.run(); });
                themedButtons.remove(swatch); swatch.setTextColor(ProfileColors.COLORS[index]); swatches[index]=swatch;
            }
        }
        paint.run();
        LinearLayout actions = new LinearLayout(this); body.addView(actions);
        Dialog[] dialog = new Dialog[1];
        action(actions,"Cancel",false,() -> dialog[0].dismiss());
        action(actions,renameId == null ? "Create profile" : "Save changes",true,() -> {
            try {
                String id=renameId;
                if(id!=null) Rules.renameProfile(this,id,input.getText().toString());
                else id=Rules.createProfile(this,input.getText().toString(),duplicate);
                Rules.setProfileColor(this,id,selected[0]); refreshProfile(); ruleSnapshot=Rules.snapshot(this); filter(); dialog[0].dismiss();
            } catch(IllegalArgumentException error) { input.setError(error.getMessage()); }
        });
        dialog[0]=sheet(renameId != null ? "Edit profile" : duplicate ? "Duplicate profile" : "New profile",body);
    }
    private void confirmDelete(String id,String name) {
        LinearLayout body=sheetBody(); body.addView(text("Remove ‘"+name+"’ and its saved rules? This cannot be undone.",15,MUTED));
        LinearLayout actions=new LinearLayout(this); body.addView(actions); Dialog[] dialog=new Dialog[1];
        action(actions,"Keep profile",false,() -> dialog[0].dismiss());
        action(actions,"Remove",true,() -> {
            try { Rules.deleteProfile(this,id); refreshProfile(); dialog[0].dismiss(); showProfiles(); }
            catch(IllegalArgumentException error) { Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show(); }
        });
        dialog[0]=sheet("Remove profile",body);
    }
    private boolean motionEnabled() {
        return !getPreferences(MODE_PRIVATE).getBoolean("reduce_motion", false)
            && Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }
    private GradientDrawable surface(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), stroke); return drawable;
    }
    private static int blend(int first, int second, float amount) {
        return Color.rgb(Math.round(Color.red(first)*(1-amount)+Color.red(second)*amount),
            Math.round(Color.green(first)*(1-amount)+Color.green(second)*amount),
            Math.round(Color.blue(first)*(1-amount)+Color.blue(second)*amount));
    }
    private int alpha(int color,int opacity) { return (color & 0x00ffffff) | (opacity << 24); }
    private void setPalette(int accent) {
        int[] styles = {R.style.AccentMint,R.style.AccentCyan,R.style.AccentBlue,R.style.AccentLavender,
            R.style.AccentRose,R.style.AccentOrange,R.style.AccentAmber,R.style.AccentLime};
        for (int i=0;i<ProfileColors.COLORS.length;i++) if (accent == ProfileColors.COLORS[i]) { getTheme().applyStyle(styles[i],true); break; }
        ACCENT = accent; BG = blend(0xff0b1018,accent,0.025f);
        SURFACE = blend(0xff141e2b,accent,0.045f); BORDER = blend(0xff263649,accent,0.18f);
    }
    private void bindSurface(View view,int radius) {
        themedSurfaces.put(view,radius); paintSurface(view,radius);
    }
    private void paintSurface(View view,int radius) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(ACCENT,28)),surface(SURFACE,BORDER,radius),null));
    }
    private void paintArtwork() {
        if (artwork == null) return;
        GradientDrawable cover = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{blend(ACCENT,WHITE,0.15f),blend(ACCENT,0xff25314f,0.3f)});
        cover.setCornerRadius(dp(16)); artwork.setBackground(cover); artwork.setTextColor(BG);
    }
    private void applyPalette() {
        if (screen == null) return;
        screen.setBackgroundColor(BG); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        for (Map.Entry<View,Integer> entry : themedSurfaces.entrySet()) paintSurface(entry.getKey(),entry.getValue());
        for (Map.Entry<Button,Boolean> entry : themedButtons.entrySet()) paintButton(entry.getKey(),entry.getValue());
        for (TextView text : accentTexts) text.setTextColor(ACCENT);
        for (CompoundButton control : themedControls) paintControl(control);
        if (search != null) search.setHighlightColor(alpha(ACCENT,70));
        if (list != null) { list.setDivider(new android.graphics.drawable.ColorDrawable(BG)); list.setDividerHeight(dp(6)); }
        if (status != null && power != null) updateStatus();
        paintArtwork();
        if (profileSection != null) {
            GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(SURFACE,ACCENT,0.14f),SURFACE});
            panel.setCornerRadius(dp(24)); panel.setStroke(dp(1),blend(BORDER,ACCENT,0.25f));
            profileSection.setBackground(panel);
        }
    }
    private void paintControl(CompoundButton control) {
        int[][] states = {new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}};
        ColorStateList tint = new ColorStateList(states,new int[]{alpha(MUTED,90),ACCENT,MUTED});
        if (control instanceof Switch) {
            ((Switch)control).setThumbTintList(tint);
            ((Switch)control).setTrackTintList(new ColorStateList(states,new int[]{alpha(MUTED,35),alpha(ACCENT,100),alpha(MUTED,75)}));
        } else control.setButtonTintList(tint);
    }
    private void styleButton(Button button, boolean primary) {
        button.setAllCaps(false); button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); button.setTextSize(13);
        themedButtons.put(button,primary); paintButton(button,primary);
    }
    private void paintButton(Button button, boolean primary) {
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
            new int[]{MUTED, primary ? BG : ACCENT}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(ACCENT,50)),
            surface(primary ? ACCENT : BG, primary ? ACCENT : BORDER, 16), null));
    }
    private void pickBackup(boolean export) {
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE).setType(export ? "text/plain" : "*/*");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "ntroll-rules.txt");
        try { startActivityForResult(intent, export ? EXPORT : IMPORT); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "No document picker available", Toast.LENGTH_LONG).show(); }
    }
    private void transferBackup(int request, Uri uri) {
        Map<String, Boolean> snapshot = Rules.snapshot(this);
        String importProfile = Rules.activeProfile(this);
        loader.execute(() -> {
            try {
                if (request == EXPORT) {
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IOException("Cannot open destination");
                        output.write(RuleBackup.encode(snapshot));
                    }
                    main.post(() -> { if (!isDestroyed()) Toast.makeText(this, "Rules exported", Toast.LENGTH_SHORT).show(); });
                } else {
                    Map<String, Boolean> imported;
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("Cannot open backup");
                        imported = RuleBackup.decode(input);
                    }
                    main.post(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        new AlertDialog.Builder(this).setTitle("Import " + imported.size() + " rule settings?")
                            .setMessage("Apply to profile ‘" + Rules.profileName(this) + "’. Merge keeps existing settings and adds enabled settings from the backup. Replace clears this profile’s current rules first. Rules for missing apps are kept; shared-UID apps inherit their group's blocks.")
                            .setNegativeButton("Cancel", null)
                            .setNeutralButton("Merge", (d,w) -> importRules(imported, false, importProfile))
                            .setPositiveButton("Replace", (d,w) -> importRules(imported, true, importProfile)).show();
                    });
                }
            } catch (Exception error) {
                main.post(() -> { if (!isDestroyed()) Toast.makeText(this, "Backup failed: " + error.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }
    private void importRules(Map<String, Boolean> imported, boolean replace, String importProfile) {
        if (!importProfile.equals(Rules.activeProfile(this))) {
            Toast.makeText(this, "Profile changed. Import again into the desired profile.", Toast.LENGTH_LONG).show(); return;
        }
        Map<String, Boolean> next = replace ? new HashMap<>() : Rules.snapshot(this);
        next.putAll(imported); Rules.restore(this, next);
        ruleSnapshot = Rules.snapshot(this); filter();
        Toast.makeText(this, "Rules imported", Toast.LENGTH_SHORT).show();
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (color == ACCENT) accentTexts.add(view); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static final class App {
        String label, packageName, searchKey; int uid, restrictions; boolean system, internet;
        ApplicationInfo info; List<String> linked;
    }
    private final class Row {
        LinearLayout root, panel; ImageView icon; TextView label, detail, disclosure, explanation;
        Switch wifi, mobile, screenWifi, screenMobile, roaming, lockdown;
        Button actions;
        Row() {
            root = new LinearLayout(MainActivity.this); root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(10), dp(8), 0, dp(8));
            bindSurface(root,12);
            LinearLayout header = new LinearLayout(MainActivity.this); header.setGravity(Gravity.CENTER_VERTICAL);
            icon = new ImageView(MainActivity.this);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(32), dp(32)); iconParams.rightMargin = dp(10);
            header.addView(icon, iconParams);
            LinearLayout names = new LinearLayout(MainActivity.this); names.setOrientation(LinearLayout.VERTICAL);
            label = text("", 14, WHITE); detail = text("", 10, MUTED); detail.setMaxLines(3);
            detail.setEllipsize(TextUtils.TruncateAt.END); detail.setTypeface(Typeface.MONOSPACE);
            label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END);
            disclosure = text("", 11, ACCENT); disclosure.setPadding(0, dp(5), 0, 0);
            names.addView(label); names.addView(detail); names.addView(disclosure);
            header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            wifi = toggle(); mobile = toggle();
            header.addView(wifi, new LinearLayout.LayoutParams(dp(68), dp(56)));
            header.addView(mobile, new LinearLayout.LayoutParams(dp(68), dp(56)));
            root.addView(header);
            panel = new LinearLayout(MainActivity.this); panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(4), dp(10), dp(14), dp(4));
            screenWifi = option("Allow Wi-Fi when screen is on");
            screenMobile = option("Allow mobile data when screen is on");
            roaming = option("Block mobile data while roaming");
            lockdown = option("Lockdown · block both networks");
            explanation = text("", 12, MUTED); explanation.setPadding(0, dp(8), 0, dp(8)); panel.addView(explanation);
            actions = new Button(MainActivity.this); actions.setText("App actions"); actions.setFocusable(false);
            styleButton(actions, false); panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
            root.addView(panel); root.setTag(this);
        }
        Switch option(String label) {
            Switch s = toggle(); s.setText(label); s.setTextSize(13); s.setTextColor(WHITE);
            s.setGravity(Gravity.CENTER_VERTICAL); s.setSwitchPadding(dp(12)); s.setMinHeight(dp(52));
            panel.addView(s, new LinearLayout.LayoutParams(-1, -2)); return s;
        }
        Switch toggle() { Switch s = new Switch(MainActivity.this); s.setFocusable(false); s.setGravity(Gravity.CENTER); s.setShowText(false); s.setPadding(dp(8), 0, dp(8), 0); themedControls.add(s); paintControl(s); return s; }
    }
    private void loadIcon(App app, ImageView target) {
        target.setTag(app.packageName);
        Drawable cached = icons.get(app.packageName);
        target.setImageDrawable(cached);
        if (cached != null || !resumed || !pendingIcons.add(app.packageName)) return;
        loader.execute(() -> {
            Drawable loadedIcon = null;
            try { if (resumed) loadedIcon = app.info.loadIcon(getPackageManager()); } catch (RuntimeException ignored) { /* App may have been removed. */ }
            final Drawable icon = loadedIcon;
            main.post(() -> {
                pendingIcons.remove(app.packageName);
                if (isDestroyed() || !resumed || icon == null) return;
                icons.put(app.packageName, icon);
                for (int i = 0; i < list.getChildCount(); i++) {
                    Row row = (Row) list.getChildAt(i).getTag();
                    if (app.packageName.equals(row.icon.getTag())) row.icon.setImageDrawable(icon);
                }
            });
        });
    }
    private final class AppAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public App getItem(int pos) { return visible.get(pos); }
        public long getItemId(int pos) { return pos; }
        public View getView(int pos, View recycled, android.view.ViewGroup parent) {
            Row row = recycled == null ? new Row() : (Row) recycled.getTag(); App app = getItem(pos);
            boolean reused = !app.packageName.equals(row.icon.getTag());
            loadIcon(app, row.icon); row.label.setText(app.label);
            row.detail.setText(app.packageName + (app.linked.size() > 1 ? "\nShared UID · linked rules" : "") + (!app.internet ? "\nNo internet permission" : ""));
            bind(row.wifi, app, 1); bind(row.mobile, app, 2);
            boolean open = expanded.contains(app.packageName);
            boolean locked = FirewallPlan.option(app.linked, ruleSnapshot, "lockdown");
            row.panel.setVisibility(open ? View.VISIBLE : View.GONE);
            row.disclosure.setText((open ? "▴ Hide rules" : "▾ Advanced rules") + (locked ? " · Lockdown" : ""));
            bindOption(row.screenWifi, app, "screen_wifi", app.internet && !locked && (app.restrictions & 1) != 0);
            bindOption(row.screenMobile, app, "screen_mobile", app.internet && !locked && (app.restrictions & 2) != 0);
            bindOption(row.roaming, app, "roaming", app.internet && !locked && (app.restrictions & 2) == 0);
            bindOption(row.lockdown, app, "lockdown", app.internet);
            row.wifi.setEnabled(app.internet && !locked); row.mobile.setEnabled(app.internet && !locked);
            row.explanation.setText(locked ? "Lockdown blocks both networks while the firewall is running. Turn it off to restore the rules above."
                : "Screen-on exceptions require the corresponding block switch above. They also allow access on the lock screen. Roaming blocking takes priority."
                    + (Build.VERSION.SDK_INT < 28 ? " Roaming status is unavailable on this Android version; enabling roaming blocking blocks all mobile data." : " If roaming status is unknown, roaming blocking also blocks mobile data.")
                    + (app.linked.size() > 1 ? " Shared UID: changes apply to all linked apps; conflicting imported rules use the stricter block." : ""));
            row.actions.setOnClickListener(v -> showAppMenu(v, app));
            if (reused || !motionEnabled()) {
                for (Switch toggle : new Switch[]{row.wifi, row.mobile, row.screenWifi, row.screenMobile, row.roaming, row.lockdown}) toggle.jumpDrawablesToCurrentState();
            }
            return row.root;
        }
        private void bindOption(Switch view, App app, String key, boolean enabled) {
            view.setOnCheckedChangeListener(null); view.setChecked(FirewallPlan.option(app.linked, ruleSnapshot, key));
            view.setEnabled(enabled); view.setAlpha(enabled ? 1f : 0.5f);
            view.setContentDescription(view.getText() + " for " + app.label);
            view.setOnCheckedChangeListener((button, checked) -> changeOption(app, key, checked));
        }
        private void bind(Switch view, App app, int mask) {
            view.setOnCheckedChangeListener(null); view.setChecked((app.restrictions & mask) != 0);
            view.setEnabled(app.internet);
            view.setContentDescription("Block " + (mask == 1 ? "Wi-Fi" : "mobile data") + " for " + app.label);
            view.setOnCheckedChangeListener((button, checked) -> change(Collections.singleton(app.uid), mask, checked));
        }
    }
}
