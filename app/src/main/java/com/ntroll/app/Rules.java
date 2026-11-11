package com.ntroll.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** Active rules and their profile archive share a file so switching is one transaction. */
final class Rules {
    private static final String ACTIVE = "active_profile", NAME = "profile_name:", ARCHIVE = "profile_rules:";
    static boolean isRuleKey(String key) {
        if (key == null) return false;
        int colon = key.indexOf(':');
        if (colon < 0) return false;
        switch (key.substring(0, colon)) {
            case "wifi": case "mobile": case "screen_wifi": case "screen_mobile": case "roaming": case "lockdown": return true;
            default: return false;
        }
    }
    static synchronized SharedPreferences prefs(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("firewall_rules", Context.MODE_PRIVATE);
        if (!prefs.contains(ACTIVE)) {
            // Upgrade existing installations without changing any effective rules.
            SharedPreferences.Editor editor = prefs.edit().putString(ACTIVE, "default").putString(NAME + "default", "Default");
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (isRuleKey(entry.getKey()) && Boolean.TRUE.equals(entry.getValue()))
                    editor.putBoolean(ARCHIVE + "default:" + entry.getKey(), true);
            }
            editor.apply();
        }
        return prefs;
    }
    static synchronized String activeProfile(Context c) { return prefs(c).getString(ACTIVE, "default"); }
    static synchronized String profileName(Context c) { return prefs(c).getString(NAME + activeProfile(c), "Default"); }
    static synchronized Map<String, String> profiles(Context c) {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs(c).getAll().entrySet()) {
            if (entry.getKey().startsWith(NAME) && entry.getValue() instanceof String)
                result.put(entry.getKey().substring(NAME.length()), (String) entry.getValue());
        }
        return result;
    }
    static synchronized int profileColorIndex(Context c, String id) {
        int fallback = id.equals("default") ? 0 : (id.hashCode() & Integer.MAX_VALUE) % ProfileColors.COLORS.length;
        int index = prefs(c).getInt("profile_color:" + id, fallback);
        return index >= 0 && index < ProfileColors.COLORS.length ? index : fallback;
    }
    static synchronized int profileColor(Context c, String id) { return ProfileColors.COLORS[profileColorIndex(c, id)]; }
    static synchronized void setProfileColor(Context c, String id, int index) {
        if (!profiles(c).containsKey(id)) throw new IllegalArgumentException("Profile no longer exists");
        if (index < 0 || index >= ProfileColors.COLORS.length) throw new IllegalArgumentException("Choose a profile color");
        prefs(c).edit().putInt("profile_color:" + id, index).apply();
    }
    private static String checkedName(Context c, String name, String exceptId) {
        name = name.trim();
        if (name.isEmpty() || name.length() > 40)
            throw new IllegalArgumentException("Use a name with 1–40 characters");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("Use a single-line profile name");
        for (Map.Entry<String, String> entry : profiles(c).entrySet()) {
            if (!entry.getKey().equals(exceptId) && entry.getValue().equalsIgnoreCase(name))
                throw new IllegalArgumentException("A profile with that name already exists");
        }
        return name;
    }
    /** Edits are immediately saved to the active profile as well as the live rule set. */
    static synchronized boolean apply(Context c, Map<String, Boolean> edits) {
        SharedPreferences prefs = prefs(c);
        Map<String, ?> before = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        String archive = ARCHIVE + activeProfile(c) + ":";
        boolean changed = false;
        for (Map.Entry<String, Boolean> entry : edits.entrySet()) {
            if (!isRuleKey(entry.getKey())) throw new IllegalArgumentException("Invalid rule key");
            if (Boolean.TRUE.equals(before.get(entry.getKey())) == entry.getValue()) continue;
            if (entry.getValue()) { editor.putBoolean(entry.getKey(), true); editor.putBoolean(archive + entry.getKey(), true); }
            else { editor.remove(entry.getKey()); editor.remove(archive + entry.getKey()); }
            changed = true;
        }
        if (changed) editor.apply();
        return changed;
    }
    private static Map<String, Boolean> live(Map<String, ?> values) {
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (isRuleKey(entry.getKey()) && Boolean.TRUE.equals(entry.getValue())) result.put(entry.getKey(), true);
        }
        return result;
    }
    static synchronized void restore(Context c, Map<String, Boolean> snapshot) {
        SharedPreferences prefs = prefs(c);
        Map<String, ?> values = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        String archive = ARCHIVE + activeProfile(c) + ":";
        for (String key : values.keySet()) if (isRuleKey(key) || key.startsWith(archive)) editor.remove(key);
        for (Map.Entry<String, Boolean> entry : snapshot.entrySet()) {
            if (!isRuleKey(entry.getKey())) throw new IllegalArgumentException("Invalid rule key");
            if (Boolean.TRUE.equals(entry.getValue())) { editor.putBoolean(entry.getKey(), true); editor.putBoolean(archive + entry.getKey(), true); }
        }
        editor.apply();
    }
    static synchronized Map<String, Boolean> snapshot(Context c) { return live(prefs(c).getAll()); }
    private static void activate(SharedPreferences.Editor editor, Map<String, ?> values, String id, Map<String, Boolean> rules) {
        for (String key : values.keySet()) if (isRuleKey(key)) editor.remove(key);
        for (String key : rules.keySet()) editor.putBoolean(key, true);
        editor.putString(ACTIVE, id);
    }
    static synchronized void switchProfile(Context c, String id) {
        SharedPreferences prefs = prefs(c);
        if (!profiles(c).containsKey(id)) throw new IllegalArgumentException("Profile no longer exists");
        if (id.equals(activeProfile(c))) return;
        Map<String, ?> values = prefs.getAll();
        Map<String, Boolean> rules = new HashMap<>();
        String prefix = ARCHIVE + id + ":";
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix) && Boolean.TRUE.equals(entry.getValue())) rules.put(entry.getKey().substring(prefix.length()), true);
        }
        SharedPreferences.Editor editor = prefs.edit(); activate(editor, values, id, rules); editor.apply();
    }
    /** Create and activate an empty profile, or a copy of the currently active profile. */
    static synchronized String createProfile(Context c, String name, boolean duplicate) {
        name = checkedName(c, name, null);
        if (profiles(c).size() >= 32) throw new IllegalArgumentException("You can save up to 32 profiles");
        SharedPreferences prefs = prefs(c); Map<String, ?> values = prefs.getAll();
        String id = UUID.randomUUID().toString();
        Map<String, Boolean> rules = duplicate ? live(values) : Collections.emptyMap();
        SharedPreferences.Editor editor = prefs.edit().putString(NAME + id, name)
            .putInt("profile_color:" + id, profiles(c).size() % ProfileColors.COLORS.length);
        for (String key : rules.keySet()) editor.putBoolean(ARCHIVE + id + ":" + key, true);
        activate(editor, values, id, rules); editor.apply(); return id;
    }
    static synchronized void renameProfile(Context c, String id, String name) {
        if (!profiles(c).containsKey(id)) throw new IllegalArgumentException("Profile no longer exists");
        prefs(c).edit().putString(NAME + id, checkedName(c, name, id)).apply();
    }
    static synchronized void deleteProfile(Context c, String id) {
        if (id.equals(activeProfile(c))) throw new IllegalArgumentException("Switch to another profile before deleting this one");
        SharedPreferences prefs = prefs(c); SharedPreferences.Editor editor = prefs.edit().remove(NAME + id).remove("profile_color:" + id);
        for (String key : prefs.getAll().keySet()) if (key.startsWith(ARCHIVE + id + ":")) editor.remove(key);
        editor.apply();
    }
    private Rules() {}
}
