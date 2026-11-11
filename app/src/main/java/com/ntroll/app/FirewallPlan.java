package com.ntroll.app;

import java.util.*;

/** Pure configuration planner: no Android calls, JNI, or work on the packet path. */
final class FirewallPlan {
    static int restrictions(List<String> names, Map<String, ?> preferences) {
        int result = 0;
        for (String name : names) {
            if (Boolean.TRUE.equals(preferences.get("wifi:" + name))) result |= 1;
            if (Boolean.TRUE.equals(preferences.get("mobile:" + name))) result |= 2;
        }
        return result;
    }

    static boolean option(List<String> names, Map<String, ?> preferences, String option) {
        for (String name : names) if (Boolean.TRUE.equals(preferences.get(option + ":" + name))) return true;
        return false;
    }

    static boolean hasRules(List<String> names, Map<String, ?> preferences) {
        return restrictions(names, preferences) != 0 || option(names, preferences, "roaming")
            || option(names, preferences, "lockdown");
    }

    static Map<String, Boolean> optionEdits(List<String> names, String option, boolean enabled) {
        Map<String, Boolean> edits = new HashMap<>();
        for (String name : names) edits.put(option + ":" + name, enabled);
        return edits;
    }

    static int effectiveRestrictions(List<String> names, Map<String, ?> preferences,
            boolean screenOn, boolean roamingOrUnknown) {
        int result = 0;
        for (String name : names) {
            if (Boolean.TRUE.equals(preferences.get("lockdown:" + name))) return 3;
            int blocked = restrictions(Collections.singletonList(name), preferences);
            if (screenOn) {
                if (Boolean.TRUE.equals(preferences.get("screen_wifi:" + name))) blocked &= ~1;
                if (Boolean.TRUE.equals(preferences.get("screen_mobile:" + name))) blocked &= ~2;
            }
            // Roaming blocks override screen-on exceptions. Unknown roaming is
            // treated conservatively when a roaming restriction was requested.
            if (roamingOrUnknown && Boolean.TRUE.equals(preferences.get("roaming:" + name))) blocked |= 2;
            result |= blocked;
        }
        return result;
    }

    static Map<String, Boolean> edits(Map<Integer, List<String>> groups,
            Set<Integer> selectedUids, int mask, boolean block) {
        Map<String, Boolean> result = new HashMap<>();
        for (int uid : selectedUids) {
            List<String> names = groups.get(uid);
            if (names == null) continue;
            for (String name : names) {
                if ((mask & 1) != 0) result.put("wifi:" + name, block);
                if ((mask & 2) != 0) result.put("mobile:" + name, block);
            }
        }
        return result;
    }

    static Map<String, Integer> blockedPackages(Map<Integer, List<String>> groups,
            Map<String, ?> preferences, int mask) {
        return blockedPackages(groups, preferences, mask, false, false);
    }

    static Map<String, Integer> blockedPackages(Map<Integer, List<String>> groups,
            Map<String, ?> preferences, int mask, boolean screenOn, boolean roamingOrUnknown) {
        Map<String, Integer> blocked = new TreeMap<>();
        for (Map.Entry<Integer, List<String>> group : groups.entrySet()) {
            int restrictions = effectiveRestrictions(group.getValue(), preferences, screenOn, roamingOrUnknown);
            if ((restrictions & mask) != 0) {
                for (String name : group.getValue()) blocked.put(name, group.getKey());
            }
        }
        return blocked;
    }
    private FirewallPlan() {}
}
