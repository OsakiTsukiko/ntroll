# ntroll

A local Android firewall with a searchable installed-app list and independent
**Block Wi-Fi** and **Block data** switches. ON means blocked; OFF means allowed.
Rules persist across launches and update while the firewall runs. System apps can
be shown with the System checkbox. Apps sharing an Android UID share their rules.

## Run

```sh
./gradlew installDebug
"$ANDROID_HOME/platform-tools/adb" shell am start -n com.ntroll.app/.MainActivity
```

Tap **Start firewall**, optionally allow notifications, and accept Android's VPN
connection dialog. Choose the apps to block. The status shows the selected physical
connection, blocked app count, and whether the VPN is active or idle. **Stop firewall**, including
the notification action, restores normal networking without deleting saved rules.

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Supports ARM64 phones and
x86_64 emulators, Android 6.0/API 23 or newer. Build without a device using
`./gradlew assembleDebug`.

## Automated release builds

Publishing a GitHub release runs [the release workflow](.github/workflows/release.yml)
and attaches signed ARM32, ARM64, x86, x86_64, and universal APKs plus SHA-256
checksums. Configure the four signing secrets first; see [release setup and local
build instructions](docs/releases.md). Manual workflow runs build downloadable
artifacts without publishing a release.

## Everyday controls

- **System**, **Blocked**, and **Internet** filter the list. Internet shows only apps
  with internet permission; Blocked shows apps with either saved restriction,
  including restrictions on an inactive connection. Filter choices persist.
- The **⋮** menu contains **Blocked apps first**, **Refresh installed apps**, bulk
  controls, and a short battery/VPN help dialog. Search matches names and packages.
- Tap an app for **Block both networks**, **Allow both networks**, or Android app
  settings. Holding a row opens its Android settings directly.
- Bulk controls apply to the currently visible internet-capable apps, with a
  confirmation showing the scope. Shared-UID packages outside the view are also
  affected because Android routes by UID. One batch writes all changes together.
- **Undo** restores the most recent successful rule edit or bulk action. It remains
  available through activity rotation, but is not a persistent edit history.
- Search and scroll position survive activity recreation; filters and sorting
  persist between launches. Refresh the installed-app list after installing or
  uninstalling apps while this screen is open.

## How it works

Rust implements the network-type policy, evaluated once per configuration change.
Java provides Android's native app list, preferences, VPN consent, and foreground
`VpnService`. No remote VPN, proxy, root access, analytics, or packet-content logging
is used. This firewall does not hide your IP address or encrypt allowed traffic.

When no apps are blocked on the current transport, the service closes the VPN
interface and stays enabled as an event-driven rule monitor. The VPN key disappears
in this idle state and returns automatically when a blocked app needs routing.
The foreground notification remains so the monitor can respond to network changes.

When blocking is needed, the service uses Android's per-app VPN routing: only currently blocked packages
(and the firewall's own sentinel UID) enter a local TUN interface. IPv4, IPv6, and
DNS requests from those UIDs enter a local blackhole and are never forwarded. Allowed apps use Android's regular
networking directly. The sentinel prevents an empty app list from accidentally
routing every app into the sink. The service keeps the TUN descriptor open without reading it. The kernel's bounded
TUN queue retains blocked packets until full and drops subsequent packets; closing
the interface frees queued packets. There is no userspace packet loop or counter.

Android 12+ supplies the best matching non-VPN network. Android 6–11 use a fallback
that prefers validated networks and then Wi-Fi. Wi-Fi hotspots count as Wi-Fi even
if metered. Unknown, offline, mixed, and other transports use the union of both
block rules. Installed packages are cached and refreshed only on package events. Rules use one
preferences snapshot and one native policy call per evaluation. Only a changed
blocked package/UID map rebuilds the VPN; a change of physical network alone updates
the underlying network in place. Editing an inactive transport rule keeps the
current interface. The visible screen updates through in-process change callbacks,
with no periodic polling or packet statistics.
Package names persist rules; they are not keyed to recyclable numeric UIDs.

## Current limits

- Network rules follow the selected physical connection, rather than the transport
  of every individual socket. Apps explicitly choosing a secondary network, OEM
  dual-network features, and rapid handovers need additional device testing.
- Changes to the effective blocked package/UID list rebuild the VPN asynchronously. This is not a
  zero-gap, packet-level kill switch during transitions. Existing app connections
  may need reconnecting after a rule changes.
- This initial implementation does not support always-on/lockdown mode or auto-start
  on boot. Do not enable Android's **Block connections without VPN** for this app:
  allowed apps intentionally use normal networking outside the local sink.
- Android permits one active VPN per profile, so another VPN cannot run alongside it.
- The app controls packages in its own Android profile; it does not control tethered
  clients, other profiles, or traffic delegated to another app/system service.
- Stopping or force-stopping the firewall removes its blocking. If Android kills
  the process, sticky service restart is best effort and is not uninterrupted.

## Development

Requirements: Rust stable, JDK 17 or 21, SDK platform/build-tools 34, and NDK
26.1.10909125. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
sdkmanager "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125"
./tests/run-policy-tests.sh
cargo test
cargo fmt --check
cargo ndk -t arm64-v8a -t x86_64 --platform 23 clippy --locked -- -D warnings
./gradlew assembleDebug lintDebug
```

Gradle builds release-profile Rust libraries for both ABIs automatically. The native
boundary is `NativeFirewall.java`; the native policy is `src/lib.rs`. Android integration
and app UI live in `app/src/main/java/com/ntroll/app`.

### Device acceptance checks

1. With every switch off, enable the firewall and verify two apps can load fresh
   internet content (use uncached URLs). Status should say **VPN idle** and the
   VPN interface should be absent. The monitor and notification should remain.
2. On Wi-Fi, block one app's Wi-Fi switch. Fresh requests should fail while another
   allowed app works. Stop/reopen the blocked app if it has established connections.
3. Leave its data switch off, switch to mobile data, wait for the status to update,
   and confirm it can connect. Turn its data switch on and confirm blocking.
4. Repeat for IPv4/IPv6 destinations and DNS, and test rapid network changes.
5. Check changes persist across rotation/relaunch; check linked shared-UID apps,
   system-app filtering, and switching rules while the activity is closed.
6. Stop from the notification, revoke VPN consent, and restart. Confirm networking
   recovers and no stuck TUN interface or service remains.

Platform references: [Android VPN guide](https://developer.android.com/develop/connectivity/vpn),
[per-app VPN routing](https://developer.android.com/reference/android/net/VpnService.Builder#addAllowedApplication(java.lang.String)),
and [physical network callbacks](https://developer.android.com/reference/android/net/ConnectivityManager#registerBestMatchingNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager.NetworkCallback,android.os.Handler)).

## Performance and measurement

Allowed app payloads and DNS do not pass through this app: there is no proxy,
encryption, compression, payload copying, bandwidth limiter, or per-packet JNI.
Blocked traffic never reaches the internet through the local interface. Kernel
routing, blocked apps' retries, Android's VPN service, and network changes can still
consume CPU or affect latency; an exact zero-overhead guarantee is not possible.
No throughput or battery improvement is claimed without measurements.

To compare throughput, use a local `iperf3` server and a separate allowed client app
on the phone. Alternate firewall OFF/ON runs on the same Wi-Fi connection, with the
same endpoint and no unrelated transfers. Compare multiple runs in both directions,
and latency both idle and under load. Use the same approach on mobile data if you
want to measure the cellular path (those tests consume mobile data). Check allowed
DNS, IPv4, IPv6, and a simultaneously blocked app. A shell-UID transfer measures
only that UID's routing and is not a substitute for an allowed-app benchmark.

For idle CPU profiling, leave the service running, put the activity in the
background, and sample the process CPU ticks and threads. No thread should be
named `rust-vpn-sink`; there should be no recurring firewall timer. Configuration
work is event-driven, so runtime idle CPU can still include Android/ART work.

The queue behavior follows Linux's [TUN transmit implementation](https://github.com/torvalds/linux/blob/v6.6/drivers/net/tun.c),
which drops packets when its ring is full. On-device blocking and throughput must
still be checked on the target Android kernel and vendor networking stack.

### Earlier optimization verification (0.2.x)

The optimized debug APK was installed on a Pixel 10 running Android 17. With the
foreground firewall service active and no rule edits, an approximately 15-second
sample showed **0 additional process CPU ticks**. The `rust-vpn-sink` thread was
absent. This is an idle observation at the kernel accounting resolution, not a
claim of zero total system CPU or battery cost.

A TCP connection to `1.1.1.1:443` succeeded from Android's shell UID, which was
outside the VPN UID list. The same numeric-address connection attempted under the
firewall's own sentinel UID timed out. Android's VPN dump confirmed that only the
sentinel UID and its Android-managed sandbox UID were routed into the VPN when no
user apps were blocked. This checks the idle configuration and one IPv4 blocking
path; it does not replace per-app Wi-Fi/mobile, IPv6, and handover acceptance tests.

A LAN throughput attempt from the phone to the laptop timed out even with the VPN
service stopped. Consequently there is **no valid before/after throughput result**
from this session. No cellular speed test or bulk internet download was performed.

## Battery improvements in 0.3.0

The TUN interface and its queued packets are released whenever the current blocklist
is empty. The event-driven foreground monitor remains enabled; network changes can
reactivate blocking without polling. There are no periodic jobs, alarms, app-held wake locks,
Wi-Fi scans, battery-exemption requests, packet counters, or per-packet app threads.
A foreground notification is retained rather than weakening the service lifecycle.

Rapid preference events coalesce into pending planner work. Changes that do not
alter the effective package/UID blocklist retain the current interface, and the
underlying network is updated only when it actually changes. Notifications update
only on status changes and alert only once.

The app screen caches UID groups, rule state, and normalized search text instead of
performing package-manager calls while binding every row. Icons load on demand into
a 32-entry cache that is released when the UI is hidden; the UI loader's thread
expires after five idle seconds. Bulk changes skip unchanged values and use one
preferences transaction, while UI updates coalesce into one pending refresh.

A blocked app may continue retrying or holding its own wake locks. These changes
reduce work done by the firewall, but do not guarantee lower total device battery
use. A short idle CPU sample cannot quantify battery-life improvement; that requires
longer comparable unplugged tests with the same apps and network conditions.

Additional acceptance checks: exercise all filters/sort; confirm bulk scope and
shared-UID behavior; undo both single and bulk edits; rotate with pending undo and
scroll position; test the last-blocked-app transition to VPN idle and back; stop and
restart from the notification; check that monitoring does not keep a wake lock.

### 0.3.0 device verification

The updated APK was installed on the connected Pixel 10 (Android 17). With no apps
blocked, the foreground monitor remained running while Android reported VPN type
`-1` (no active tunnel), confirming the idle-interface path. No application crash
was reported in the scoped log check. The host policy suite includes 15 routing and
bulk-scope checks, and Rust policy tests, dual-ABI Clippy, and Android lint passed.

The short system power trace contained transient Android notification-post wake
locks attributed to the app, released immediately after posting. The app itself
does not request or hold a wake lock. This illustrates why the lack of explicit
wake locks cannot be described as zero total OS battery overhead.

After allowing the UI loader to expire, a 16.52-second 0.3.0 idle sample recorded
zero additional process CPU ticks, with neither a packet worker nor a UI pool
thread present. This does not measure long-term battery drain. The phone was
locked during the automated UI dump, so visual/touch acceptance of the new menus,
filters, and undo remains a manual check; no claim of full UI device testing is made.

## Interface and rule backups in Android app 0.4.0

The screen now uses charcoal panels, mint accents, a console-style heading,
monospace package labels, rounded app rows, and ripple feedback. Its short entrance
animation runs once per screen creation. Native switches keep their normal change
animation for the same app; recycled rows snap to the correct state so scrolling
does not suggest that rules changed. Reduce motion is available in the overflow
menu, and Android's animator duration setting is respected. There are no looping
animations or additional background service tasks.

Search has a clear button and an empty-results message. Loading failures offer a
refresh path. App menus include Copy package name; icons update visible matching
rows when asynchronous loading finishes, including after a row is recycled.

Use **Export rules…** and **Import rules…** in the overflow menu for backups via
[Android's system document picker](https://developer.android.com/training/data-storage/shared/documents-files).
No broad storage permission is requested. Backups are UTF-8 text: a
`ntroll-rules-v1` header followed by one `wifi:package.name` or
`mobile:package.name` block per line. Entries are sorted on export. An empty
backup is valid. Imports reject unknown versions, invalid package keys, duplicate
rules, and files over 1 MiB before modifying preferences. Export/import I/O runs on
the existing short-lived UI worker.

Import offers Merge (retain existing blocks) or Replace (clear current blocks),
with Undo available afterward. Package names are retained for apps not currently
installed. Shared-UID apps inherit the union of their saved restrictions as usual.
Backups contain rules only; they do not enable the firewall or change view settings.

Validation: 15 routing/bulk policy checks and 10 backup checks pass. Android debug
build and lint pass, and version 0.4.0 was installed on the connected Pixel 10.
The main layout was visually inspected on the phone; search, empty results, clear
search, and overflow menu were exercised. Full document-provider round trips,
rotation during a picker operation, large-font layouts, and animation timing remain
manual acceptance checks. The VPN service and native routing policy were unchanged.

## Per-app advanced rules (Android app 0.5.0)

Tap an app row to expand its controls. The two header switches remain the base
Wi-Fi/mobile blocks. Each app now has these additional saved settings:

- **Allow Wi-Fi when screen is on** / **Allow mobile data when screen is on**:
  exceptions to the corresponding base block. Enable that base block first.
  Screen-on means Android's interactive state, including the lit lock screen;
  ambient/always-on display does not normally count as interactive.
- **Block mobile data while roaming**: blocks mobile even when the screen-on
  exception is enabled. Wi-Fi is unaffected. If the network cannot confirm it is
  not roaming, the app conservatively applies this block. Android 6–8.1 cannot
  provide this network capability, so this option blocks all mobile data there.
- **Lockdown**: unconditionally blocks both transports for this app while the
  firewall runs. Other settings are retained and restored when Lockdown is off.
  This is not Android's system-wide VPN lockdown or a fail-closed boot guarantee.

Priority is Lockdown, then roaming block, then screen exceptions to base blocks.
All edits apply to the entire shared UID. Conflicting imported settings combine
per-package effective blocks conservatively, so one package's exception cannot
cancel a linked package's block. The Blocked filter includes roaming-only and
Lockdown-only rules. App actions includes a confirmed Reset all rules operation;
Undo also covers advanced edits and resets. Bulk base edits leave advanced rules
intact, as explained in their confirmation.

Screen on/off broadcasts trigger reevaluation only when screen exceptions exist.
The service reads the current interactive state when planning, including at
startup. Network capability callbacks detect roaming changes on the same network.
The VPN interface is rebuilt only if effective blocked UIDs/packages change.
No polling, per-packet processing, or new wake lock was added. State transitions
remain asynchronous; these conditions do not guarantee a zero-gap kill switch.

Backups now use `ntroll-rules-v2` and include `screen_wifi`, `screen_mobile`,
`roaming`, and `lockdown` keys in addition to `wifi` and `mobile`. Version 1 files
remain importable. Older app versions reject version 2 rather than silently
ignoring advanced protections.

Host tests cover 256 combinations of six rule flags, screen state, and roaming
state, each checked across all three transport masks, plus shared-UID conflicts,
screen-off restoration, filtering, existing bulk operations, and 12 backup checks.
Android build and lint passed. The expanded row was visually inspected on the
connected Pixel 10. Real carrier roaming transitions require separate device
acceptance testing.

Platform semantics: [interactive state](https://developer.android.com/reference/android/os/PowerManager#isInteractive())
and [not-roaming capability](https://developer.android.com/reference/android/net/NetworkCapabilities#NET_CAPABILITY_NOT_ROAMING).

Device interaction check: for the 8BitDo app, enabling its mobile base block
changed the service to VPN active; enabling its screen-on mobile exception while
the screen was interactive returned it to VPN idle. Enabling Lockdown restored
VPN active and disabled the exception controls. All three test settings were then
turned off again, restoring VPN idle. These checks verify routing-plan transitions,
not throughput or packet-level carrier roaming behavior. Screen-off broadcast
handling still needs a physical sleep/wake acceptance check.

## Rule profiles (Android app 0.6.0)

Tap **Profile: …** in the firewall card to switch between named rule sets. Changes
are saved automatically to the active profile. The switcher offers New and Manage;
Manage is also available from the overflow menu. You can create an empty profile,
duplicate the active profile, rename it, or delete an inactive profile after
confirmation. Switch away from a profile before deleting it. Names must be unique
ignoring case, contain 1–40 characters, and fit on one line. Up to 32 profiles can
be saved. Existing installations migrate their rules into **Default**.

Profiles contain all six per-app settings: Wi-Fi block, mobile block, screen-on
exceptions for each, roaming block, and Lockdown. The firewall's enabled state,
search, and display filters remain global. Switching does not start a stopped
firewall; a running firewall automatically reevaluates the selected profile.
The selected profile survives app restarts, and its name appears in the foreground
notification. No periodic profile tasks were added.

The active rule set, selected profile ID, profile names, and archived rules share
one preferences file. Each profile switch replaces the active rule map and profile
ID in one atomic in-memory transaction, with asynchronous persistence. The service
ignores inactive-profile archive changes. Switching between profiles with identical
effective restrictions retains the current VPN interface. VPN handovers remain
asynchronous, as with other rule edits.

Undo belongs to the current profile and is cleared when switching. Import, reset,
and undo update that profile's saved copy without deleting other profiles. Export
and import explicitly operate on the current profile's rules; the existing v2
backup format is unchanged and does not export the profile library or its names.

Validation: Android build and lint passed, existing policy/backup tests passed,
and 26 instrumentation checks passed on the connected Pixel 10 using an isolated
preferences file. These cover migration, automatic saving, duplication, empty
profiles, renaming, validation, switching, import/undo replacement isolation,
delete protection, archive cleanup, persistence, metadata exclusion, and consistent
listener snapshots during switches. The new profile control was visually inspected.

To run the profile checks on a connected test device:

```sh
./gradlew assembleDebug assembleDebugAndroidTest installDebug installDebugAndroidTest
adb shell am instrument -w com.ntroll.app.test/com.ntroll.app.ProfilesTest
```

The instrumentation runner is packaged only in the separate test APK.

## Profile colors and player card (Android app 0.7.0)

The profile selector is now a compact player-style card with colored artwork,
profile name, position, and previous/next controls. Tap the name to open the full
profile chooser; use the skip buttons or swipe horizontally across the card to
switch. Profiles cycle in alphabetical order and wrap at either end. Skip buttons
are disabled when there is only one profile.

Each profile has a saved accent selected from Mint, Cyan, Blue, Lavender, Rose,
Orange, Amber, or Lime. Tap its artwork or use Manage profiles → Change profile
color. New profiles receive a palette color automatically; existing profiles have
a stable default color. Changing profiles recolors buttons, switches, filter
checkboxes, labels, ripples, tinted panels, and system bars. Newly opened dialogs
also inherit the selected accent. Color edits do not change firewall rules or
trigger a service rebuild, and deleting a profile removes its saved color.

The artwork and name make a short directional slide/fade when changing profiles.
Animations cancel cleanly on rapid skips and respect Reduce motion and Android's
animator setting. There are no playback timers, looping effects, extra services,
or periodic work. Profile colors and names remain local metadata; current-profile
rule exports still contain rules only.

The Undo button, snapshots, and related UI promises were removed after 0.6.0;
changes continue to save automatically. Reset and profile deletion retain their
confirmation dialogs.

Validation: debug build and lint passed, routing/backup host tests passed, and all
37 isolated on-device profile checks passed, including color persistence,
validation, rename/delete behavior, and rule isolation.

The player card was visually inspected with the mint theme on the Pixel 10. The
phone locked before the final gesture/skip/color-picker interaction check; no user
profile was modified by that attempted check. Full on-screen gesture and alternate
color acceptance remains a manual check. The isolated profile color checks above
completed successfully while using separate test preferences.
