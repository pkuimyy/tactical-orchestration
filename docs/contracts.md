# 接口与数据版本策略

HTTP 主版本前缀为 `/api/v1`，场景、运行清单和 JSONL 事件使用整数
`schemaVersion: 1`；命令批次结构由 HTTP 主版本和清单中的规则版本约束。不兼容的字段或语义改变必须提升主版本；新增可选字段保持兼容。
未知 schemaVersion 返回 400，不推测或自动迁移。规则版本独立于接口版本，
当前规则版本为 `m3-combat-1`。

`/contracts/validate` 仍只校验包络元信息：`schemaVersion`、`kind`（SCENARIO/COMMAND/EVENT）、
`id`（1–64 位 ASCII 字母、数字、下划线或短横线）。这是无副作用的契约检查，
不是场景创建或命令提交。M1 的场景、版本和实验资源见 [m1-scenarios.md](m1-scenarios.md)，命令生命周期见 [m2-wego.md](m2-wego.md)，M3 行动／学说及战损扩展见 [m3-combat.md](m3-combat.md)。
命令批次用日期、乐观版本和内容保证提交重试幂等；结算按实例与日期幂等。

统一应用错误为 `{"schemaVersion":1,"code":"INVALID_REQUEST","message":"..."}`。
401 认证失败、400 载荷/版本不合法、404 路径不存在、405 方法不支持、
409 生命周期／版本冲突、429 资源限额、413 请求体超限、415 内容类型/编码不支持、500 未预期内部错误。
错误不回显请求体、凭据、异常堆栈或服务器路径。
HTTP 解析之前被容器拒绝的畸形报文/过大请求头不保证应用 JSON 格式。

GET /health 和列入白名单的 Web 静态入口（/、/index.html、/app.js、/style.css）无需认证；
所有 API 路由均要求启动会话 Bearer Token（包括 OpenAPI），不提供 HTTP 取令牌接口。
请求体上限 65536 字节，包含分块传输；不接受压缩请求体。不使用 Cookie、
无开放 CORS，也不提供 HTTP 取令牌接口。静态入口只展示空壳页面，场景数据通过认证后的 API 读取。

服务端 OpenAPI：`GET /api/v1/openapi`，由 springdoc 根据控制器路由、DTO 和校验注解生成，
认证与通用错误由 `OpenApiConfiguration` 补充。新增字段与路由以代码为唯一来源，
不维护静态 JSON/YAML。需要离线使用时导出到 `target/openapi.json`；生成文件被 Git 忽略。
