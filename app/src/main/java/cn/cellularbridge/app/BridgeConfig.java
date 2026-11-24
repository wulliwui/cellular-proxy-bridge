package cn.cellularbridge.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

final class BridgeConfig {
    final String username;
    final String password;
    final int listenPort;
    final Set<Integer> targetPorts;
    private final byte[] credentials;

    BridgeConfig(String username, String password, int listenPort, Set<Integer> targetPorts) {
        this.username = username;
        this.password = password;
        this.listenPort = listenPort;
        this.targetPorts = Set.copyOf(targetPorts);
        credentials = (username + ":" + password).getBytes(StandardCharsets.US_ASCII);
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("bridge_settings", Context.MODE_PRIVATE);
    }

    static BridgeConfig load(Context context) {
        SharedPreferences preferences = preferences(context);
        String password = preferences.getString("password", "");
        if (password.isEmpty()) {
            byte[] random = new byte[18];
            new SecureRandom().nextBytes(random);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            preferences.edit().putString("password", password).apply();
        }
        return new BridgeConfig(preferences.getString("username", "bridge"), password,
                preferences.getInt("listen_port", 8899),
                parseTargetPorts(preferences.getString("target_ports", "443")));
    }

    void save(Context context, String portList) {
        preferences(context).edit()
                .putString("username", username)
                .putString("password", password)
                .putInt("listen_port", listenPort)
                .putString("target_ports", portList)
                .apply();
    }

    boolean authenticates(String value) {
        if (value == null || !value.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.substring(6).trim());
        } catch (IllegalArgumentException error) {
            return false;
        }
        return MessageDigest.isEqual(credentials, decoded);
    }

    static boolean validCredential(String text) {
        return !text.isEmpty() && text.length() <= 128
                && text.chars().allMatch(value -> value >= 33 && value <= 126);
    }

    static int parsePort(String text, int minimum) {
        if (text.isEmpty() || text.length() > 5
                || !text.chars().allMatch(value -> value >= '0' && value <= '9')) {
            throw new IllegalArgumentException("Invalid port");
        }
        int port = Integer.parseInt(text);
        if (port < minimum || port > 65535) {
            throw new IllegalArgumentException("Port out of range");
        }
        return port;
    }

    static Set<Integer> parseTargetPorts(String text) {
        Set<Integer> ports = new LinkedHashSet<>();
        for (String part : text.split("[,，]", -1)) {
            ports.add(parsePort(part.trim(), 1));
        }
        return ports;
    }
}
