# Cellular Proxy Bridge

[简体中文](README.md)

Cellular Proxy Bridge runs an HTTP proxy on an Android phone. The computer stays on its existing LAN, and the phone joins the same Wi-Fi router through a temporary, local-only connection. When the phone's normal Wi-Fi connection is disconnected and mobile data is on, Android continues to use cellular as the default network for other apps. The proxy separately binds each HTTP/1.1 CONNECT tunnel's DNS and TCP connections to cellular.

## Install and connect

1. Download the signed APK from [Releases](https://github.com/wulliwui/cellular-proxy-bridge/releases/latest) and install it on Android 13 or later.
2. In the phone's Wi-Fi settings, disable auto-join for the target network and disconnect its normal Wi-Fi connection. Keep the Wi-Fi radio and mobile data on. Keep the computer on its existing LAN.
3. Open Cellular Proxy Bridge and enter the Wi-Fi network name, security mode and password. Choose the WPA2/mixed option for networks that support WPA2. Enter the Wi-Fi password again after reopening the app.
4. Tap the start button (启动代理). Grant nearby-device access when prompted and confirm Android's temporary Wi-Fi connection. If you allow notifications, the notification displays the proxy address and offers a stop button.
5. Configure the computer's **HTTP proxy** with the address and port shown in the app. The default proxy username is `bridge`, and the app generates a random proxy password on first launch. Use the show-password checkbox (显示密码) in proxy settings to view it. The default listen port is `8899`, and the default destination port is `443`.

The temporary Wi-Fi connection can receive a different LAN IP. Use the address shown in the app. If normal Wi-Fi becomes the system default network, the app releases the temporary connection and closes the listener until normal Wi-Fi disconnects.

The app runs without root, USB or ADB. Use it on a trusted LAN because HTTP Basic proxy credentials are Base64-encoded on the computer-to-phone hop. CONNECT supports HTTPS and TCP services on explicitly allowed destination ports. The proxy excludes loopback, private, link-local, carrier-shared and multicast destinations.

## Verify with curl

Replace the example address, port and username. curl will prompt for the proxy password. A successful request prints the public egress IP and reports `200` for both CONNECT and HTTPS.

```bash
curl --show-error --max-time 20 --noproxy "" \
  --proxy http://192.168.1.50:8899 --proxy-user bridge \
  --write-out '\nCONNECT %{http_connect} · HTTPS %{http_code}\n' \
  https://api64.ipify.org
```

| Test | Expected result |
| --- | --- |
| Temporary Wi-Fi connected, mobile data on | CONNECT `200` and a successful HTTPS response. |
| Missing or incorrect password | CONNECT `407` with a Basic authentication challenge. |
| Disable mobile data and keep Wi-Fi on | New CONNECT requests receive `503`. Existing tunnels close. |
| Re-enable mobile data | Forwarding resumes at the same proxy address. |
| Turn Wi-Fi off and back on | The listener and tunnels close while Wi-Fi is off. Reconnect and use the address shown in the app. |
| Start several HTTPS requests | Connections are relayed independently. Up to 16 sessions, including handshakes, can run concurrently. |
| Stop the proxy | The listener and tunnels close, and the temporary Wi-Fi connection is released. |

A `403` response means the destination port or address is disallowed. A resolution or connection failure returns `502`. An upstream timeout returns `504`. The proxy allows 7 seconds and 16 KiB for the initial request, 12 seconds for upstream DNS and connection setup, and about 120 seconds of inactivity in both directions before closing a tunnel.

During development, `adb logcat -s CellularBridge` shows the listener, cellular network ID, local egress address and connection counters. Logs omit credentials, destination host names and tunnel contents.

## Device verification

Tested with a Redmi M2012K10C running Android 13 and a Mac on the same LAN. The Mac obtained CONNECT 200 and HTTPS 200 through the proxy. At the same time, a separate Android test app obtained HTTPS 200 through the system's default cellular network.

With mobile data off, the proxy remained reachable over the temporary Wi-Fi connection, returned 503 for new tunnels and closed existing ones. Restoring mobile data restored the proxy at the same address. Stopping from the notification released the temporary network and listener. Wi-Fi reconnection restored service, and all six concurrent HTTPS requests succeeded.

## Build from source

Use JDK 17, Android SDK Platform 34 and Build Tools 34.0.0. Set `ANDROID_HOME` to the SDK directory or set `sdk.dir` in `local.properties`.

```bash
./gradlew :app:assembleDebug :app:lintRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To sign your own release, run `./gradlew :app:assembleRelease` and use the SDK's `zipalign` and `apksigner` on `app/build/outputs/apk/release/app-release-unsigned.apk`. Android requires matching signing keys to install an update over an existing copy.

The source is in [app/src/main](app/src/main). The temporary Wi-Fi connection uses Android's `WifiNetworkSpecifier`.

## License

This project is released under the [MIT License](LICENSE). The included Gradle Wrapper retains its Apache 2.0 license.
