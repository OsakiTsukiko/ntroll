package com.ntroll.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.*;

/** Runs against real Android preferences in an isolated file; never edits user rules. */
public final class ProfilesTest extends Instrumentation {
    private int checks;
    private void expect(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private void rejects(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        expect(rejected, message);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        String suffix = UUID.randomUUID().toString();
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("profile_test_" + suffix + "_" + name, mode);
            }
        };
        Bundle result = new Bundle();
        try {
            Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> { try { runTests(context); } catch (Throwable error) { failure[0] = error; } });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            result.putString("stream", "\nProfiles: " + checks + " device checks passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAILED: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            context.getSharedPreferences("firewall_rules", Context.MODE_PRIVATE).edit().clear().commit();
            if (android.os.Build.VERSION.SDK_INT >= 24) getTargetContext().deleteSharedPreferences("profile_test_" + suffix + "_firewall_rules");
        }
    }
    private void runTests(Context c) {
        SharedPreferences raw = c.getSharedPreferences("firewall_rules", Context.MODE_PRIVATE);
        Map<String, Boolean> original = new HashMap<>();
        original.put("wifi:com.example.app", true); original.put("screen_wifi:com.example.app", true);
        original.put("roaming:com.example.app", true); original.put("lockdown:com.example.other", true);
        SharedPreferences.Editor legacy = raw.edit();
        for (String key : original.keySet()) legacy.putBoolean(key, true);
        legacy.putBoolean("mobile:com.allowed", false).commit();
        expect(Rules.snapshot(c).equals(original), "Upgrade preserves all existing rules");
        String first = Rules.activeProfile(c);
        expect(Rules.profileName(c).equals("Default"), "Legacy rules become Default");
        expect(Rules.profileColor(c, first) == ProfileColors.COLORS[0], "Default keeps mint theme");
        expect(Rules.profiles(c).size() == 1, "Migration is idempotent");
        String copy = Rules.createProfile(c, "Travel", true);
        expect(copy.equals(Rules.activeProfile(c)), "Duplicate becomes active");
        expect(Rules.snapshot(c).equals(original), "Duplicate includes advanced flags");
        expect(Rules.profileColor(c, copy) != Rules.profileColor(c, first), "New profile starts with a different accent");
        Rules.setProfileColor(c, copy, 4);
        expect(Rules.profileColor(c, copy) == ProfileColors.COLORS[4], "Custom color is saved");
        expect(Rules.snapshot(c).equals(original), "Color edit cannot alter firewall rules");
        rejects(() -> Rules.setProfileColor(c, copy, -1), "Negative palette index rejected");
        rejects(() -> Rules.setProfileColor(c, copy, ProfileColors.COLORS.length), "Invalid palette index rejected");
        rejects(() -> Rules.setProfileColor(c, "missing", 0), "Cannot save color for nonexistent profile");
        Rules.apply(c, Collections.singletonMap("mobile:com.example.app", true));
        Map<String, Boolean> travel = Rules.snapshot(c);
        Rules.switchProfile(c, first);
        expect(Rules.snapshot(c).equals(original), "Editing a duplicate does not change source");
        expect(Rules.profileColor(c, first) == ProfileColors.COLORS[0], "Color edits do not leak to source profile");
        Rules.switchProfile(c, copy);
        expect(Rules.snapshot(c).equals(travel), "Edits persist across switches");
        expect(Rules.profileColor(c, copy) == ProfileColors.COLORS[4], "Custom color survives switches");
        String empty = Rules.createProfile(c, "Home", false);
        expect(Rules.snapshot(c).isEmpty(), "Empty profile has no inherited restrictions");
        Rules.restore(c, original);
        expect(Rules.profiles(c).size() == 3, "Restore/import does not delete profiles");
        Rules.restore(c, Collections.emptyMap());
        Rules.switchProfile(c, first); Rules.switchProfile(c, empty);
        expect(Rules.snapshot(c).isEmpty(), "Undo/replace persists in its own profile");
        Rules.setProfileColor(c, empty, 7);
        Rules.renameProfile(c, empty, "  Personal  ");
        expect(Rules.profileName(c).equals("Personal"), "Names are trimmed and saved");
        expect(Rules.profileColor(c, empty) == ProfileColors.COLORS[7], "Rename preserves color");
        rejects(() -> Rules.renameProfile(c, empty, " travel "), "Case-insensitive duplicate names rejected");
        rejects(() -> Rules.createProfile(c, "   ", false), "Empty name rejected");
        rejects(() -> Rules.createProfile(c, "a\nb", false), "Multiline name rejected");
        rejects(() -> Rules.createProfile(c, new String(new char[41]).replace('\0', 'x'), false), "Long name rejected");
        rejects(() -> Rules.deleteProfile(c, empty), "Active profile cannot be deleted");
        rejects(() -> Rules.switchProfile(c, "missing"), "Missing profile cannot erase rules");
        expect(empty.equals(Rules.activeProfile(c)), "Rejected operations preserve selection");
        Rules.deleteProfile(c, copy);
        expect(!Rules.profiles(c).containsKey(copy), "Inactive profile deleted");
        expect(!raw.contains("profile_color:" + copy), "Deleting profile removes its color");
        expect(Rules.snapshot(c).isEmpty(), "Delete leaves active rules intact");
        boolean archiveRemoved = true;
        for (String key : raw.getAll().keySet()) if (key.startsWith("profile_rules:" + copy + ":")) archiveRemoved = false;
        expect(archiveRemoved, "Deleted archive removed");
        // Every listener callback must see one complete profile and its matching rule set.
        boolean[] consistent = {true};
        SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
            Map<String, Boolean> expected = Rules.activeProfile(c).equals(first) ? original : Collections.emptyMap();
            if (!Rules.snapshot(c).equals(expected)) consistent[0] = false;
        };
        raw.registerOnSharedPreferenceChangeListener(listener);
        Rules.switchProfile(c, first); Rules.switchProfile(c, empty);
        raw.unregisterOnSharedPreferenceChangeListener(listener);
        expect(consistent[0], "Callbacks never see a partial profile switch");
        Rules.switchProfile(c, first);
        expect(Rules.snapshot(c).equals(original), "Source survives other profile deletion and resets");
        expect(raw.edit().commit(), "Saved profiles flush to disk");
        expect(raw.getString("active_profile", "").equals(first), "Active selection persisted");
        boolean metadataExcluded = true;
        for (String key : Rules.snapshot(c).keySet()) if (key.startsWith("profile_")) metadataExcluded = false;
        expect(metadataExcluded, "Metadata never leaks into rule backup/planner");
    }
}
