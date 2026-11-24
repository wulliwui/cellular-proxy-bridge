# 蜂窝桥 Android 局域网 HTTP 代理实施方案

**项目名**　蜂窝桥

**仓库名**　`cellular-proxy-bridge`

## 项目目标

让 Android 手机本身成为局域网 HTTP 代理服务器。电脑通过 Wi-Fi 访问手机上的代理端口，手机用 SIM 卡的移动数据连接公网目标，再把响应沿原连接传给电脑。电脑上的浏览器、curl 或业务程序直接配置标准 HTTP 代理。首版以 Android 13 真机为验收基线，运行时无需 root 或 USB 调试。

~~~text
电脑客户端 ── 局域网 HTTP CONNECT ── 手机 APK ── 4G/5G TCP ── 公网目标
~~~

## 客户端怎样填写代理

假设 APK 显示手机的 Wi-Fi 地址为 `192.168.1.50`，监听端口为 `8899`。电脑能通过局域网访问该地址。启动代理后，客户端这样配置。

| 设置项 | 示例值 |
| --- | --- |
| 类型 | HTTP |
| 服务器 | `192.168.1.50` |
| 端口 | `8899` |
| 代理 URL | `http://192.168.1.50:8899` |

客户端访问 HTTPS 网站时，会向手机发送 `CONNECT 目标域名:443 HTTP/1.1`。手机连通目标后回复 `200 Connection Established`，随后双向转发字节。客户端和目标网站之间继续使用原有的 TLS 加密。首版协议范围是 HTTP/1.1 CONNECT 转发 TCP，默认允许目标端口 443，其他端口按用途配置。 [HTTP CONNECT 规范](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.6)

## Android 实现

| 部分 | 实施方式 |
| --- | --- |
| Wi-Fi 入站 | 先使用手机在系统设置里正常接入的 Wi-Fi。通过 `ConnectivityManager` 跟踪 Wi-Fi 和 `LinkProperties`，取该接口的 IPv4 地址。将 `ServerSocket` 绑定到这个地址和设置端口。手机 Wi-Fi 地址变化时，重建监听并更新 App 显示的代理地址。 |
| 移动数据出站 | 通过 `requestNetwork()` 请求具有 `NET_CAPABILITY_INTERNET` 的 `TRANSPORT_CELLULAR` 网络。用返回的 `Network.getAllByName()` 解析目标域名，用该网络的 `socketFactory` 创建 TCP 套接字，再连接目标地址。每条隧道单独绑定移动网络。 |
| CONNECT 处理 | 解析目标主机和端口，按配置校验访问密码和目标。成功连接目标后才回 200，并把已读到的隧道数据一并转发。设置握手超时、空闲超时和并发上限。任一端关闭时，尽量发完已缓冲的数据，再释放该隧道的两端连接。 |
| 断线行为 | 移动网络不可用时，新请求返回 `503`。目标建连失败返回 `502`，超时返回 `504`。移动网络丢失时，清理依赖该网络的现有隧道。蜂窝网络恢复后，用新的 `Network` 处理新请求。 |
| 界面与服务 | 界面提供启动、停止、代理地址、移动数据状态和活动连接数。从界面启动与局域网电脑通信的 `connectedDevice` 前台服务，通知中提供停止入口。声明 `INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`、`FOREGROUND_SERVICE` 以及面向 Android 14 及以上时对应的 `FOREGROUND_SERVICE_CONNECTED_DEVICE`。 |

局域网代理账号和密码由用户在 APK 内设置。缺少有效凭据时回复 `407 Proxy Authentication Required`，通过 `Proxy-Authenticate: Basic realm="Cellular Bridge"` 提示客户端认证。日志记录连接状态、错误类型和统计计数，省略代理凭据及隧道内容。 [HTTP 代理认证](https://www.rfc-editor.org/rfc/rfc9110.html#section-11.7) · [ConnectivityManager 文档](https://developer.android.com/reference/android/net/ConnectivityManager) · [Network 的 DNS 和套接字接口](https://developer.android.com/reference/android/net/Network) · [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)

## 实现顺序

1. **先做一条端到端隧道。** 手机显示可达的 Wi-Fi 地址，接收一个 CONNECT 请求，从移动数据建立 TCP 连接并完成双向转发。先用 curl 查询公网出口。
2. **完成可日常使用的 APK。** 加入前台运行、访问认证、多个并发隧道、停止清理和网络改变后的恢复。交付 APK、源码和局域网部署说明。
3. **按需验证仅局域网 Wi-Fi 模式。** 如果还希望手机上的普通 App 也默认走移动数据，在首版通过后，再评估用 `WifiNetworkSpecifier` 建立临时 Wi-Fi。先单独验证电脑能主动连接手机临时 Wi-Fi 地址上的监听端口，再把它接入代理服务。已有 Android 13 测试证实手机可以通过临时 Wi-Fi 主动连电脑，同时普通 App 使用蜂窝公网。电脑主动连入手机是本项目需要另外验证的方向。 [Android 局域网 Wi-Fi 文档](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)

## 真机验收

将示例 IP 换成 APK 显示的地址。启用代理认证时，给 curl 加上 `--proxy-user 用户名`，按提示输入密码。

~~~bash
curl -v --max-time 20 --noproxy "" --proxy http://192.168.1.50:8899 https://api64.ipify.org
~~~

| 场景 | 通过标准 |
| --- | --- |
| 手机 Wi-Fi 和移动数据同时开启 | 电脑收到 CONNECT 200，随后完成 HTTPS 请求并获得公网 IP。APK 记录其公网套接字使用蜂窝网络。 |
| 手机关闭移动数据，保持 Wi-Fi 连通 | 电脑仍能连上手机代理端口，但新的 CONNECT 返回失败。公网目标没有收到经 Wi-Fi 补发的请求。 |
| 恢复移动数据 | 同一代理地址再次完成 CONNECT 和 HTTPS 请求。 |
| 手机关闭 Wi-Fi | 电脑与手机的代理连接断开。重新接入 Wi-Fi 后，可按 APK 显示的当前地址重新连接。 |
| 连续和并发使用 | 多个 HTTPS 连接能各自收发数据。停止代理后，端口和现有隧道都关闭。 |

出口 IP 用于辅助比对。分流以蜂窝套接字的绑定状态和关闭移动数据后的对照结果为准。 [curl 代理参数](https://curl.se/docs/manpage.html#-x)
