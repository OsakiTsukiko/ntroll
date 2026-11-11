package com.ntroll.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class RuleBackupTest {
    private static Map<String,Boolean> read(String text) throws IOException {
        return RuleBackup.decode(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void rejects(String text) throws Exception {
        try { read(text); throw new AssertionError("Accepted invalid backup"); }
        catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        Map<String,Boolean> rules = new HashMap<>();
        rules.put("wifi:com.example.app", true); rules.put("mobile:android", true);
        if (!RuleBackup.decode(new ByteArrayInputStream(RuleBackup.encode(rules))).equals(rules)) throw new AssertionError("round trip");
        if (!read("ntroll-rules-v1\r\nwifi:com.example.app\r\n").containsKey("wifi:com.example.app")) throw new AssertionError("CRLF");
        if (!read("ntroll-rules-v1\n").isEmpty()) throw new AssertionError("empty");
        rejects("ntroll-rules-v3\n"); rejects("ntroll-rules-v1\nwifi:bad/name\n");
        rejects("ntroll-rules-v1\nservice:enabled\n");
        rejects("ntroll-rules-v1\nwifi:android\nwifi:android\n");
        rejects("ntroll-rules-v1\n\nwifi:android\n");
        rejects("ntroll-rules-v1\n" + "a".repeat(1024 * 1024));
        rules.put("screen_wifi:com.example.app", true); rules.put("screen_mobile:com.example.app", true);
        rules.put("roaming:com.example.app", true); rules.put("lockdown:com.example.app", true);
        if (!RuleBackup.decode(new ByteArrayInputStream(RuleBackup.encode(rules))).equals(rules)) throw new AssertionError("advanced rules round trip");
        rejects("ntroll-rules-v1\nlockdown:android\n");
        rules.put("wifi:com.allowed", false);
        if (RuleBackup.decode(new ByteArrayInputStream(RuleBackup.encode(rules))).containsKey("wifi:com.allowed")) throw new AssertionError("false serialized");
        System.out.println("Backup tests passed (12 checks)");
    }
}
