package com.ntroll.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned, deterministic text backup. Only enabled rule settings are persisted. */
final class RuleBackup {
    private static final String HEADER = "ntroll-rules-v2";
    private static final int LIMIT = 1024 * 1024;
    private static boolean valid(String key) {
        return key.length() <= 300 && key.matches("(wifi|mobile|screen_wifi|screen_mobile|roaming|lockdown):[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    }
    static byte[] encode(Map<String, Boolean> rules) throws IOException {
        StringBuilder result = new StringBuilder(HEADER).append('\n');
        for (String key : new TreeSet<>(rules.keySet())) {
            if (!valid(key)) throw new IOException("Invalid rule key");
            if (Boolean.TRUE.equals(rules.get(key))) result.append(key).append('\n');
        }
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("Backup exceeds 1 MiB");
        return bytes;
    }
    static Map<String, Boolean> decode(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int size;
        while ((size = input.read(buffer)) != -1) {
            if (bytes.size() + size > LIMIT) throw new IOException("Backup exceeds 1 MiB");
            bytes.write(buffer, 0, size);
        }
        String[] lines = new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\\r?\\n", -1);
        if (lines.length == 0 || (!HEADER.equals(lines[0]) && !"ntroll-rules-v1".equals(lines[0]))) throw new IOException("Unsupported backup format");
        Map<String, Boolean> rules = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) continue;
            if (!valid(lines[i]) || ("ntroll-rules-v1".equals(lines[0]) && !lines[i].startsWith("wifi:") && !lines[i].startsWith("mobile:"))) throw new IOException("Invalid rule on line " + (i + 1));
            if (rules.put(lines[i], true) != null) throw new IOException("Duplicate rule on line " + (i + 1));
        }
        return rules;
    }
}
