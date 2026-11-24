# 蜂窝桥 · Cellular Proxy Bridge

[English](README.en.md)

蜂窝桥让电脑通过局域网访问 Android 手机上运行的 HTTP 代理。手机由蜂窝桥临时接入现有 Wi-Fi，电脑继续用原来的局域网地址访问手机。临时 Wi-Fi 只参与局域网通信，普通 App 的默认网络仍由 Android 管理。在普通 Wi-Fi 已断开、移动数据开启时，默认公网流量走蜂窝网络。代理也把每条 HTTP/1.1 CONNECT 隧道的 DNS 查询和 TCP 连接单独绑定到蜂窝网络。

## 安装和配置

1. 在 Android 13 或更高版本上安装 [Releases](https://github.com/wulliwui/cellular-proxy-bridge/releases/latest) 页面下载的蜂窝桥 APK。
2. 在手机系统设置里关闭目标 Wi-Fi 的自动连接，并断开普通 Wi-Fi 连接。保持手机的 WLAN 总开关和 SIM 移动数据开启。电脑继续接入原局域网。
3. 打开蜂窝桥，填写原 Wi-Fi 的名称、加密方式和密码。选择「WPA2 / WPA2-WPA3 混合」可连接使用 WPA2 的网络。重新打开 App 后需要重新填写 Wi-Fi 密码。
4. 点「启动代理」。首次允许附近设备权限，并在 Android 的临时 WLAN 提示中点「连接」。允许通知后，通知中也可以查看代理地址和停止代理。
5. 在电脑配置 **HTTP 代理**，填写 App 显示的 Wi-Fi IP 和端口。首次打开蜂窝桥会生成随机代理密码，可在「代理设置」内用「显示密码」查看。默认代理账号是 `bridge`，监听端口是 `8899`，目标端口只允许 `443`。

临时连接可能取得新的局域网 IP，按 App 显示的地址配置客户端。如果系统普通 Wi-Fi 成为默认网络，蜂窝桥会释放临时连接并关闭监听，等待普通 Wi-Fi 断开。

运行时无需 root、USB 或 ADB。在受信任的局域网内使用，因为 HTTP Basic 认证头在电脑到手机这一段使用可解码的 Base64。代理支持 CONNECT 隧道，适用于 HTTPS 及显式放行端口的 TCP 服务。公网出站只使用蜂窝网络，目标地址会排除环回、私网、链路本地、共享地址和组播等地址。

## 用 curl 验证

替换下面的 Wi-Fi IP、端口和账号，curl 会提示输入密码。成功时会输出公网出口 IP，并显示 CONNECT 和 HTTPS 均为 `200`。

```bash
curl --show-error --max-time 20 --noproxy "" \
  --proxy http://192.168.1.50:8899 --proxy-user bridge \
  --write-out '\nCONNECT %{http_connect} · HTTPS %{http_code}\n' \
  https://api64.ipify.org
```

| 验证操作 | 预期结果 |
| --- | --- |
| 临时 Wi-Fi 已连接，移动数据开启 | CONNECT `200`，HTTPS 成功。 |
| 省略密码或使用错误密码 | CONNECT `407`，响应携带 Basic 认证提示。 |
| 关闭移动数据，保留 Wi-Fi | 新 CONNECT 返回 `503`，已建立的隧道关闭。 |
| 恢复移动数据 | 原代理地址恢复转发。 |
| 关闭 Wi-Fi，再重新连接 | 关闭时监听与隧道断开，重新开启 WLAN 后按提示重连并使用显示的地址。 |
| 同时发起多个 HTTPS 请求 | 连接独立收发。单次运行最多处理 16 条连接，含握手中的连接。 |
| 停止代理 | 监听端口和当前隧道关闭，释放临时 Wi-Fi 连接。 |

遇到 `403` 时检查允许的目标端口和目标地址。目标无法解析或连接时返回 `502`，建连超时返回 `504`。代理握手最多等待 7 秒，头部上限 16 KiB，目标解析和建连合计最多 12 秒，双向都无流量的隧道在约 120 秒后关闭。

开发时可用 `adb logcat -s CellularBridge` 查看 Wi-Fi 监听、蜂窝网络编号、出站套接字本地地址和连接计数。日志不含凭据、目标主机名或隧道内容。

## 真机验收结果

使用 Redmi M2012K10C（Android 13）和局域网中的 Mac 验证。独立探针先验证了电脑可以主动连入手机的临时 Wi-Fi 地址。正式 APK 启动后，Mac 通过临时 Wi-Fi 地址收到 CONNECT 200 和 HTTPS 200，同时另一款独立测试 App 通过 Android 的默认蜂窝网络完成了 HTTPS 200。

关闭移动数据后，临时 Wi-Fi 地址仍可访问，新 CONNECT 返回 503，已有隧道关闭。恢复数据后，同一代理地址重新可用。从通知停止代理后，临时 Wi-Fi 和监听端口释放，手机默认网络仍是蜂窝。重新开启 WLAN 后，临时连接自动恢复，6 路并发 HTTPS 请求全部成功。Gradle 构建和 lint 均已通过。

## 从源码构建

需要 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0。将 `ANDROID_HOME` 指向 SDK 目录，或在 `local.properties` 里设置 `sdk.dir`。

```bash
./gradlew :app:assembleDebug :app:lintRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如需自行签名发布，运行 `./gradlew :app:assembleRelease`，得到 `app/build/outputs/apk/release/app-release-unsigned.apk`，再用 Android SDK 的 `zipalign` 和 `apksigner` 对齐并签名。GitHub Releases 中的 APK 已签名。覆盖安装时，Android 要求两版 APK 的签名一致。

应用源码位于 [app/src/main](app/src/main)。临时 Wi-Fi 采用 Android 的 `WifiNetworkSpecifier`，实现了[原始实施方案](蜂窝桥%20Android%20局域网%20HTTP%20代理实施方案.md)的仅局域网模式。

## 开源协议

项目采用 [MIT 许可证](LICENSE)。随附的 Gradle Wrapper 保留其原有的 Apache 2.0 许可证。
