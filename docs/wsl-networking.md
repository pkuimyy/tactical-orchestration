# Windows → WSL2 访问诊断（M1.1）

2026-09-27 本机实测：WSL 2.4.13.0，Linux 5.15.167.4，WSL 网卡 IPv4 为
`172.19.118.135`，默认路由走 eth0 / `172.19.112.1`。Windows 用户目录没有 `.wslconfig`。
地址可能在 WSL 重启后变化，不应长期硬编码。

## 两个独立问题

1. **服务只绑定回环。** `application.properties` 配置 `server.address=127.0.0.1`，
   `ss -ltnp` 显示 Java 服务为 `[::ffff:127.0.0.1]:8080`。
   WSL 内 `http://127.0.0.1:8080/health` 返回 200，而 WSL 内通过
   `172.19.118.135:8080` 立即拒绝连接。因此 Windows 直接访问 WSL IP 也不可能成功，
   这一步尚未涉及 Bearer Token、CORS 或前端代码。
2. **本机 localhost 转发存在 IPv6 路径异常。** Windows 的端口 8080 仅有
   `wslrelay` 在 `::1` 上监听；Windows `127.0.0.1:8080` 拒绝连接，
   `[::1]:8080` 和 `curl.exe -6 localhost:8080` 则连接被中止。
   结合 JVM 的 IPv4-mapped IPv6 监听，推断异常位于此处的地址族转发；没有证据表明是防火墙拦截。

用同一 JAR 启动两个临时实例（独立端口与令牌，测试后关闭），结果如下：

| JVM / 绑定 | Windows 127.0.0.1 | Windows WSL IP |
|---|---|---|
| `-Djava.net.preferIPv4Stack=true`，绑定 127.0.0.1 | HTTP 200 | 连接拒绝（符合绑定范围） |
| 同一 JVM 参数，绑定 172.19.118.135 | 连接拒绝 | HTTP 200 |

第一种实例在 Linux 上显示为真正的 `127.0.0.1:端口` IPv4 socket。
该对照验证了本机可用的两种访问方式；没有改动已有 8080 服务、Windows 防火墙或 WSL 全局设置。

## 推荐：Windows 本机通过回环访问

在原服务终端正常停止旧进程后，以以下命令启动（重启会清空 M1 内存场景，先导出需要的部署）：

```sh
java -Djava.net.preferIPv4Stack=true \
  -jar tactical-server/target/tactical-server-0.1.0-SNAPSHOT.jar
```

Windows 浏览器打开 `http://127.0.0.1:8080/`，从新的 `.runtime/session.token` 读取令牌。
如暂时不能重启旧服务，可以追加 `--server.port=8081` 和
`--tactical.token-file=.runtime/session-8081.token` 启动独立实例验证。
参数位置重要：`-D...` 属于 JVM，必须放在 `-jar` 前。

## 如果确实需要通过 WSL IP 访问

在 WSL 中先运行 `hostname -I` 获取当前网卡 IP，再显式绑定该地址，例如：

```sh
java -Djava.net.preferIPv4Stack=true \
  -jar tactical-server/target/tactical-server-0.1.0-SNAPSHOT.jar \
  --server.address=172.19.118.135
```

Windows 打开 `http://172.19.118.135:8080/`。此时服务绑定的是网卡，API 仍要求 Bearer Token；
不再具有默认仅回环可达的范围。没有必要为了本机访问自动添加端口转发或开放所有防火墙。
若以后切换 mirrored 模式或希望让局域网其他机器访问，需要按当时的网络环境另行验证。

## 快速复核

WSL：

```sh
hostname -I
ss -ltnp | rg ':8080'
curl --noproxy '*' -i http://127.0.0.1:8080/health
```

Windows PowerShell：

```powershell
Get-NetTCPConnection -LocalPort 8080
curl.exe --noproxy "*" -i http://127.0.0.1:8080/health
```

`/health` 无需认证，应返回 200；页面能打开但 API 返回 401 则是令牌问题，不是网络不通。

微软说明了 Windows 通过 localhost 访问 WSL 服务，以及使用网卡地址时需相应绑定：
[WSL 网络访问](https://learn.microsoft.com/en-us/windows/wsl/networking)。
localhost relay 的实现说明见 [WSL localhost](https://github.com/microsoft/WSL/blob/master/doc/docs/technical-documentation/localhost.md)。
