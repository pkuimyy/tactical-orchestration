# 战术编排 · Tactical Orchestration

M2：单屏游戏工作台，支持独立战场管理、冻结实验、双方命令锁定和确定性的一日移动／接敌结算。
最慢有效连决定团机动，运行清单与 JSONL 可导出复现；战损和学说将在 M3 加入。

## 环境与构建

当前验证平台为 Linux（令牌文件依赖 POSIX 权限）。使用完整 JDK 25 和全局
Maven 3.9.x，已验证 Maven 3.9.9；不提供 Maven Wrapper。
Spring Boot 4.0.8、JUnit 6.0.3、springdoc 3.0.2 以及构建插件版本由 POM 管理。

```sh
mvn clean verify
```

构建包括 JUnit、真实 HTTP 测试、Spotless 格式检查和 Maven Enforcer 引擎依赖边界检查。
调整格式用 `mvn spotless:apply`。测试结果在各模块 `target/surefire-reports/`。

首次构建需联网获取 Maven 插件和 Maven Central 依赖。
准备离线构建机时，归档同平台 JDK 25、全局 Maven、源码以及完整构建后的
Maven 本地仓库（默认 `~/.m2/repository`）；恢复后执行 `mvn -o clean verify`。
单独拿到源码不保证首次离线构建。可执行 JAR 含运行依赖，运行无需网络或 Maven。

## 独立启动

在仓库根目录执行：

```sh
java -Djava.net.preferIPv4Stack=true -jar tactical-server/target/tactical-server-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:8080`，使用 `--server.port=端口` 更改端口。
上面的 IPv4 JVM 参数修复本机 WSL2 的 localhost 转发兼容问题；Windows 优先打开
`http://127.0.0.1:8080/`。通过 WSL 网卡 IP 访问需显式修改绑定地址，详见
[Windows / WSL2 网络诊断](docs/wsl-networking.md)。
每次启动生成新的 256-bit 随机令牌，原子写入工作目录下
`.runtime/session.token`，权限为 0600。令牌不打印到启动日志。
可用 `TACTICAL_TOKEN_FILE` 指定独立文件路径；多实例必须使用不同端口和令牌文件。
重启后客户端需要重新读取令牌。不要把 token 文件提交到仓库。

```sh
curl -i http://127.0.0.1:8080/health
curl -i http://127.0.0.1:8080/api/v1/system
# 从文件读取认证头，避免令牌出现在 curl 的命令行参数中
{ printf 'Authorization: Bearer '; cat .runtime/session.token; printf '\n'; } |
  curl --header @- http://127.0.0.1:8080/api/v1/system
{ printf 'Authorization: Bearer '; cat .runtime/session.token; printf '\n'; } |
  curl --header @- -H 'Content-Type: application/json' \
    -d '{"schemaVersion":1,"kind":"SCENARIO","id":"river-crossing"}' \
    http://127.0.0.1:8080/api/v1/contracts/validate
```

依次预期 200、401、200、200。完整 HTTP 回归由 `scripts/smoke-test.py` 自动执行；
接口结构从运行服务的 OpenAPI 获取，不再维护重复的手填 `.http` 样例。
契约检查不创建场景、不执行命令；版本与错误约定见 [contracts.md](docs/contracts.md)。

## 游戏工作台（M2）

启动后打开 `http://127.0.0.1:8080/`，将 `.runtime/session.token` 的内容粘贴到页面并连接。

1. 载入“河谷突破”预置，或通过“新建战场”创建 2–16 格宽高的空白地图。
2. 点击六角格，在右侧“地形 / 工程”“团连编制”“补给设施”标签中编辑。滚轮缩放、拖动平移，“适应战场”恢复全图。
3. 编制面板逐连分页，满编六连也无需向下滚动；编辑装备与 HP 后保存。
4. 双方城市师部齐备后冻结；从同一冻结版本点击两次“启动独立实验”，得到不同实例 ID。
5. 点击实验卡片「进入推演」，为双方选择团并逐格规划路线，各自提交、确认锁定后结算当日。
6. 查看位置更新和分页事件，导出运行清单及 JSONL；继续修改草稿不会改变已有实验输入。

内置「追击验证」场景演示侦察 6 对装甲 5；具体操作、测试数值、冲突与幂等语义见
[M2 WEGO 与机动](docs/m2-wego.md)。当前数据保存在进程内存，服务重启前请导出重要输入和结果。

主界面以地图为中心，部署面板、实验控制和指挥记录同屏；适配 1980×1080，
同时验证了 1920×1080 和 1980×960 浏览器可用区域无页面/主面板滚动条。
连队、实验和记录通过标签/分页浏览；仅主动打开的原始档案允许内部滚动。
连接、新建场景使用弹窗；全屏按钮或 F11 可进一步增加战场显示区域。

每个表单保存都经过服务端校验；错误显示在底部状态栏，弹窗内也会显示错误。刷新后重新输入令牌，会从服务端
载入上次选择的草稿。令牌不写入浏览器持久存储。草稿采用版本检查，冲突时请重新载入。

**当前使用内存仓库**：服务重启会清空草稿、冻结版本、实验及事件；重要输入与结果请导出。
师部只能在草稿中重新部署，运行中的师部不可移动。详细约束见
[场景契约](docs/m1-scenarios.md)及 [WEGO 规则](docs/m2-wego.md)。

## 模块

- `tactical-core`：纯 Java 领域与契约不变量。
- `tactical-simulation`：依赖 core，后续承载确定性引擎。
- `tactical-application`：依赖 simulation，承载用例。
- `tactical-server`：依赖 application，HTTP、认证、参数校验与资源适配。

MVP 客户端以实施计划为准，M1 开始使用 Spring Boot 同源原生 Web。
不引入独立 CLI、JavaFX、前端框架或客户端规则引擎。
验收记录见 [MVP-acceptance.md](docs/MVP-acceptance.md)。

独立产物与 M1／M2 HTTP 流程验收（Linux，另需 Python 3 和 cURL）：

```sh
python3 scripts/smoke-test.py
```

脚本临时启动 JAR，验证 HTTP、监听地址、非回环连接拒绝和日志凭据隔离后关闭进程。
版本兼容依据：[Spring Boot 4.0 系统要求](https://docs.spring.io/spring-boot/4.0/system-requirements.html)、
[springdoc 兼容矩阵](https://springdoc.org/faq.html#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)。

## OpenAPI 与设计文档

`GET /api/v1/openapi` 使用 springdoc 从控制器路由、请求/响应 DTO 和 Bean Validation
实时生成 OpenAPI 3.1；认证和通用错误在代码配置中补充。不维护第二份静态接口定义。
该接口要求 Bearer Token。若需导出，在服务启动后执行：

```sh
mkdir -p tactical-server/target
{ printf 'Authorization: Bearer '; cat .runtime/session.token; printf '\n'; } |
  curl --fail --header @- http://127.0.0.1:8080/api/v1/openapi \
    -o tactical-server/target/openapi.json
```

生成的 OpenAPI JSON/YAML 和 `target/` 均被 Git 忽略，不纳入版本管理。
设计基线位于 [.codex/design](.codex/design/)，TECH 与 PLAN 已统一为原生 Web、全局 Maven 和代码生成 OpenAPI。

可选浏览器验收（仅开发测试需要 Node.js + Playwright/Chromium，产品没有 Node 构建依赖）：

```sh
PLAYWRIGHT_MODULE=/path/to/playwright node scripts/browser-smoke.mjs
```

脚本从空白地图完成编辑、冻结、双实验、刷新、错误展示和导入导出，并验证六连分页、
多实验分页、战场管理、M2 双方下令及 HTTP 重放一致性、日志导出和目标分辨率布局。
截图输出到 `tactical-server/target/` 的 `m1.1-game.png`、`m1.2-library.png` 和 `m2-command.png`。
可设置 `BROWSER_BIND_ADDRESS` 为本机 WSL IP，验证 HTTP IP 来源的完整流程。

顶部「战场管理」独立页签提供搜索、重命名、复制、归档／恢复和删除。复制仅复制部署，
不继承冻结版本或实验；已有冻结版本的战场只能归档，保留实验来源。列表六项分页。
