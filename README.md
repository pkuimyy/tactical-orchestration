# 战术编排 · Tactical Orchestration

M1：原生 Web 六角地图编辑、双方团连与补给部署、场景冻结及独立实验实例。
M0 的 HTTP 认证、输入边界、自动 OpenAPI 和 Maven 构建继续生效。战斗结算从 M2 开始。

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
java -jar tactical-server/target/tactical-server-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:8080`，使用 `--server.port=端口` 更改端口。
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

依次预期 200、401、200、200。Bruno/IDE 示例见 [api-examples.http](docs/api-examples.http)。
契约检查不创建场景、不执行命令；版本与错误约定见 [contracts.md](docs/contracts.md)。

## 浏览器编辑器（M1）

启动后打开 `http://127.0.0.1:8080/`，将 `.runtime/session.token` 的内容粘贴到页面并连接。

1. 载入“河谷突破”预置，或创建 2–16 格宽高的空白地图。
2. 选择六角格，编辑地形、基础工事、相邻格边的河流／桥梁／道路、补给点。
3. 在团编制表单设置阵营、角色和连队，编辑装备与 HP，点击对应保存按钮。
4. 双方城市师部齐备后冻结；从同一冻结版本点击两次“启动独立实验”，得到不同实例 ID。
5. 可继续修改草稿；已有冻结版本和实验保持原输入。导出 JSON 可在其他服务会话导入。

每个表单保存都经过服务端校验；错误显示在顶部。刷新后重新输入令牌，会从服务端
载入上次选择的草稿。令牌不写入浏览器持久存储。草稿采用版本检查，冲突时请重新载入。

**M1 使用内存仓库**：服务重启会清空草稿、冻结版本、实验及事件；重要场景请导出。
此阶段实验状态为 READY／第 0 天，不包含战斗或移动。编辑草稿的初始师部位置是部署操作，
运行中的师部没有移动／直接写位置接口。详细 API 与限制见 [M1 场景契约](docs/m1-scenarios.md)。

## 模块

- `tactical-core`：纯 Java 领域与契约不变量。
- `tactical-simulation`：依赖 core，后续承载确定性引擎。
- `tactical-application`：依赖 simulation，承载用例。
- `tactical-server`：依赖 application，HTTP、认证、参数校验与资源适配。

MVP 客户端以实施计划为准，M1 开始使用 Spring Boot 同源原生 Web。
不引入独立 CLI、JavaFX、前端框架或客户端规则引擎。
验收记录见 [MVP-acceptance.md](docs/MVP-acceptance.md)。

独立产物与 M1 HTTP 流程验收（Linux，另需 Python 3 和 cURL）：

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

脚本从空白地图完成编辑、冻结、双实验、刷新、错误展示和导入导出，截图输出到
`tactical-server/target/m1-editor.png` 与 `m1-mobile.png`。
