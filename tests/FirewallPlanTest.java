package com.ntroll.app;
import java.util.*;

public final class FirewallPlanTest {
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Map<Integer, List<String>> groups = new HashMap<>();
        groups.put(10001, Arrays.asList("app.one", "app.shared"));
        groups.put(10002, Collections.singletonList("app.two"));
        Map<String, Object> rules = new HashMap<>();
        expect(FirewallPlan.blockedPackages(groups, rules, 3).isEmpty(), "Empty rules must leave all user apps out of VPN");
        rules.put("wifi:app.one", true);
        Map<String, Integer> wifi = FirewallPlan.blockedPackages(groups, rules, 1);
        expect(wifi.size() == 2 && wifi.containsKey("app.shared"), "Shared UID must block the whole group");
        expect(!wifi.containsKey("app.two"), "Allowed UID must not enter the VPN");
        expect(FirewallPlan.blockedPackages(groups, rules, 2).isEmpty(), "Wi-Fi rules must not block mobile");
        rules.put("mobile:app.two", true);
        expect(wifi.equals(FirewallPlan.blockedPackages(groups, rules, 1)), "Inactive mobile edit must not change Wi-Fi routing");
        expect(FirewallPlan.blockedPackages(groups, rules, 3).size() == 3, "Unknown networks apply union");
        groups.remove(10001);
        expect(FirewallPlan.blockedPackages(groups, rules, 1).isEmpty(), "Uninstalled apps must leave the routing plan");
        groups.put(10005, Collections.singletonList("app.one"));
        expect(!wifi.equals(FirewallPlan.blockedPackages(groups, rules, 1)), "UID replacement must invalidate the routing plan");
        rules.put("wifi:app.one", false);
        expect(FirewallPlan.blockedPackages(groups, rules, 1).isEmpty(), "Unblocking must remove the route");
        Map<String, Boolean> edits = FirewallPlan.edits(groups, Collections.singleton(10005), 1, true);
        expect(edits.size() == 1 && Boolean.TRUE.equals(edits.get("wifi:app.one")), "Bulk Wi-Fi changes must touch only the selected UID and transport");
        expect(!edits.containsKey("mobile:app.two"), "Hidden/unselected apps must retain their mobile rule");
        rules.putAll(edits);
        expect(FirewallPlan.blockedPackages(groups, rules, 1).containsKey("app.one"), "Bulk edit must take effect in the planner");
        expect(FirewallPlan.blockedPackages(groups, rules, 2).containsKey("app.two"), "Bulk edit must preserve other rules");
        groups.put(10005, Arrays.asList("app.one", "app.shared"));
        edits = FirewallPlan.edits(groups, Collections.singleton(10005), 3, false);
        expect(edits.size() == 4, "Allow both must include all packages in a shared UID");
        rules.putAll(edits);
        expect(!FirewallPlan.blockedPackages(groups, rules, 3).containsKey("app.shared"), "Allow both must clear shared-UID restrictions");
        expect(FirewallPlan.edits(groups, Collections.singleton(99999), 3, true).isEmpty(), "Stale package selections must be harmless");
        List<String> single = Collections.singletonList("app.one");
        int cases = 0;
        for (int config = 0; config < 64; config++) {
            Map<String, Boolean> conditional = new HashMap<>();
            String[] keys = {"wifi", "mobile", "screen_wifi", "screen_mobile", "roaming", "lockdown"};
            for (int k = 0; k < keys.length; k++) conditional.put(keys[k] + ":app.one", (config & (1 << k)) != 0);
            for (boolean screenOn : new boolean[]{false, true}) for (boolean roaming : new boolean[]{false, true}) {
                boolean lockdown = (config & 32) != 0;
                boolean wifiBlocked = lockdown || ((config & 1) != 0 && !(screenOn && (config & 4) != 0));
                boolean mobileBlocked = lockdown || (roaming && (config & 16) != 0)
                    || ((config & 2) != 0 && !(screenOn && (config & 8) != 0));
                int expected = (wifiBlocked ? 1 : 0) | (mobileBlocked ? 2 : 0);
                expect(FirewallPlan.effectiveRestrictions(single, conditional, screenOn, roaming) == expected,
                    "Conditional priority mismatch: " + config + "/" + screenOn + "/" + roaming);
                for (int mask = 1; mask <= 3; mask++) {
                    Map<Integer, List<String>> oneGroup = Collections.singletonMap(1, single);
                    expect(FirewallPlan.blockedPackages(oneGroup, conditional, mask, screenOn, roaming).isEmpty() == ((expected & mask) == 0), "Transport selection");
                }
                cases++;
            }
        }
        rules.clear(); rules.put("wifi:app.one", true); rules.put("wifi:app.shared", true);
        rules.put("screen_wifi:app.one", true);
        List<String> shared = Arrays.asList("app.one", "app.shared");
        expect(FirewallPlan.effectiveRestrictions(shared, rules, true, false) == 1, "An imported exception cannot weaken another shared UID package's block");
        rules.putAll(FirewallPlan.optionEdits(shared, "screen_wifi", true));
        expect(FirewallPlan.effectiveRestrictions(shared, rules, true, false) == 0, "UI screen exception applies to whole UID");
        expect(FirewallPlan.effectiveRestrictions(shared, rules, false, false) == 1, "Screen-off restores base block");
        rules.clear(); rules.put("roaming:app.one", true);
        expect(FirewallPlan.hasRules(single, rules), "Roaming-only rule must appear in blocked filter");
        rules.clear(); rules.put("lockdown:app.one", true);
        expect(FirewallPlan.hasRules(single, rules), "Lockdown-only rule must appear in blocked filter");
        expect(FirewallPlan.effectiveRestrictions(shared, rules, true, false) == 3, "Lockdown protects shared UID");
        rules.clear(); rules.put("screen_wifi:app.one", true);
        expect(FirewallPlan.effectiveRestrictions(single, rules, false, false) == 0, "Exception alone does not block");
        System.out.println("FirewallPlan: base/bulk and shared-UID checks passed; " + cases + " conditional scenarios with all transport masks passed");
    }
}
