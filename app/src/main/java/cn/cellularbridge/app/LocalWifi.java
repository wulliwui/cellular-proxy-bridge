package cn.cellularbridge.app;

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;

import java.nio.charset.StandardCharsets;

final class LocalWifi {
    private static final int WPA2 = 0;
    private static final int WPA3 = 1;
    private static final int OPEN = 2;

    private LocalWifi() {
    }

    static boolean needsPassword(int security) {
        return security != OPEN;
    }

    static boolean validSsid(String ssid) {
        return !ssid.isEmpty() && ssid.getBytes(StandardCharsets.UTF_8).length <= 32
                && ssid.chars().noneMatch(Character::isISOControl);
    }

    static boolean validPassword(String password) {
        return password.length() >= 8 && password.length() <= 63
                && password.chars().allMatch(value -> value >= 32 && value <= 126);
    }

    static NetworkRequest request(String ssid, int security, String password) {
        var builder = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        switch (security) {
            case WPA2 -> builder.setWpa2Passphrase(password);
            case WPA3 -> builder.setWpa3Passphrase(password);
            case OPEN -> { }
            default -> throw new IllegalArgumentException("Unknown Wi-Fi security");
        }
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build();
    }

    static boolean isInternetWifi(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
