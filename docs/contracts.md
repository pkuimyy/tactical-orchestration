# M0 接口与数据版本策略

HTTP 主版本前缀为 `/api/v1`，场景、命令、事件的 JSON 包络均使用整数
`schemaVersion: 1`。不兼容的字段或语义改变必须提升主版本；新增可选字段保持兼容。
未知 schemaVersion 返回 400，不推测或自动迁移。规则版本独立于接口版本，
当前 `m0-contract-1` 仅标识契约基线，尚不提供模拟。

M0 只校验包络元信息：`schemaVersion`、`kind`（SCENARIO/COMMAND/EVENT）、
`id`（1–64 位 ASCII 字母、数字、下划线或短横线）。这是无副作用的契约检查，
不是场景创建或命令提交。M1/M2 将定义具体领域载荷与生命周期。
未来事件需带稳定 ID、天数、顺序、原因事件与任务关联；命令 ID 用于幂等。

统一应用错误为 `{"schemaVersion":1,"code":"INVALID_REQUEST","message":"..."}`。
401 认证失败、400 载荷/版本不合法、404 路径不存在、405 方法不支持、
413 请求体超限、415 内容类型/编码不支持、500 未预期内部错误。
错误不回显请求体、凭据、异常堆栈或服务器路径。
HTTP 解析之前被容器拒绝的畸形报文/过大请求头不保证应用 JSON 格式。

除 GET /health 外，所有路由均要求启动会话 Bearer Token（包括 OpenAPI）。
请求体上限 65536 字节，包含分块传输；不接受压缩请求体。不使用 Cookie、
无开放 CORS，也不提供 HTTP 取令牌接口。静态 Web 的公开路由在 M1 单独设计。

服务端 OpenAPI：`GET /api/v1/openapi`，由 springdoc 根据控制器路由、DTO 和校验注解生成，
认证与通用错误由 `OpenApiConfiguration` 补充。新增字段与路由以代码为唯一来源，
不维护静态 JSON/YAML。需要离线使用时导出到 `target/openapi.json`；生成文件被 Git 忽略。
