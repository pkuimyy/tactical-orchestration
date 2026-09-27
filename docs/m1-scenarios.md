# M1 场景与冻结契约

本页说明领域约束和使用方式。接口、请求/响应字段的 OpenAPI 由代码生成，
认证后读取 `/api/v1/openapi`，不另维护 JSON/YAML 文档。

## 生命周期

`ScenarioDraft → ScenarioRevision → Game`。创建草稿返回 ID、version 和 scenario；
`PUT /api/v1/scenarios/{id}` 的请求为 `{"expectedVersion":1,"scenario":{...}}`。
只在 expectedVersion 等于当前版本时整体验证和替换草稿，成功后版本递增。
校验失败不修改草稿，旧版本返回 409，避免多窗口覆盖。

冻结请求 `POST /api/v1/scenarios/{id}/revisions` 使用 `{"expectedVersion":1}`。
只有双方均有合法的城市师部才能冻结；同草稿的相同内容重复冻结返回同一冻结版本。
冻结版本不可变，重复内容的其他草稿有独立版本 ID，但 contentHash 相同。
冻结不锁死草稿；之后可以继续编辑并生成新版本。

创建实验 `POST /api/v1/games` 使用 `{"revisionId":"...","seed":42}`。
每次请求创建一个不同 ID 的实例，初始状态取自不可变冻结版本。
初始已完成天数 day 为 0、status 为 PLANNING，当前规划第 1 天；没有直接写实例 HP 或位置的接口。
不可变输入允许安全共享；每个实例的生命周期与移动状态独立，详见 [M2](m2-wego.md)。

草稿、冻结版本和实验都可 GET 读取。冻结版本列表位于
`/scenarios/{id}/revisions`，实验列表位于 `/revisions/{id}/games`。
`/scenarios/{id}/export` 和 `/revisions/{id}/export` 返回纯 Scenario JSON，
`POST /scenarios/import` 接收同格式并创建新草稿。导入只读取请求内容，不能指定服务端路径。
预置输入 `GET /presets/river-valley` 来自唯一资产 `scenarios/river-valley.json`。
以上路径均以 `/api/v1` 开头且要求 Bearer Token。

## 地图与编制约束

- 地图为矩形轴向坐标 q/r，各轴范围从 0 到 width/height − 1；宽高 2–16，必须完整覆盖且无重复格。
- 地形 PLAIN/FOREST/HILL/MOUNTAIN/CITY，工事等级 0–3（测试参数）。
- 格边两端必须相邻，反向重复同样非法。河流与道路独立；桥梁 NONE/INTACT/DESTROYED，非 NONE 必须有河流。
- 每格至多一个团，最多 32 个团；每团 1–6 个正式连位，此上限是 MVP 测试参数。
- 连 ID 在场景中唯一；每连 maxHp 为 1–1000，hp 在 0–maxHp 之间，团至少有一个存活连。
- 师部必须位于城市，每方至多一个，至少一个存活通信连。旅部至少两个存活通信连，占正式连位。
- 普通团可混编和不满编；可选 brigadeId 必须指向同阵营旅部。删除已被引用的旅部前需先解除所属关系。
- 装备形态为 FOOT/MOTORIZED/MECHANIZED/TRACKED/TOWED。通行和速度由 [M2 规则](m2-wego.md) 定义。
- 补给点最多 64 个，ID 与位置分别唯一，可与团同格；库存范围 0–1000000。师部和团不是补给设施。
- ID 为 1–64 位 ASCII 字母、数字、下划线或短横线，场景和团名称最多 80 个字符。

空白草稿可暂时缺少师部，但不能冻结。师部在草稿中可重新部署；实验中禁止师部移动，
不能把部署编辑当作运行时移动规则。客户端只编辑初始数据并展示服务端返回结果。

## 哈希与操作记录

`contentHash` 为 SHA-256，`hashVersion` 为 canonical-v1。
服务端先校验并规范化：按坐标/ID 排序格子、团、连、补给点，规范化格边方向并排序，
去掉不带河流和道路的空边，把空的所属旅部统一为空字符串。
使用固定顺序与长度界定的二进制字段编码（`ScenarioHash`），包含全部 M1 场景字段。
因此 JSON 空白、对象键顺序、列表顺序和格边朝向不影响哈希；地形、HP、名称等有效输入变化会影响哈希。

`GET /scenarios/{id}/events` 返回 DRAFT_CREATED、DRAFT_UPDATED、SCENARIO_FROZEN、GAME_CREATED，
包含递增序号、场景/实体 ID 和内容哈希。仓库保留最近 1000 条操作事件。
这是编辑生命周期日志，与 M2 战场事件分开保存。
实际 HTTP 验证的 JSONL 样例见 `m1-event-example.jsonl`。

## 存储与错误

M1 为进程内有界存储：32 份草稿、128 个冻结版本、128 个实验。
服务重启清空；长期保留通过导出/导入。达到资源上限返回 429。
HTTP 请求体上限仍为 64 KiB（包括导入）；使用紧凑 JSON 可减少体积。
输入有错返回 400 INVALID_SCENARIO/INVALID_REQUEST，并给出可操作原因；
资源不存在 404，草稿版本冲突或写冻结版本 409，未认证 401。
浏览器刷新只保存上次草稿 ID，重新认证后从服务器读取；不在本地存储令牌或权威场景状态。

## M1.2 管理

顶部独立战场管理页签支持搜索、分页、重命名、复制和归档／恢复。管理写入都检查草稿版本。
复制只复制部署，不继承冻结版本或实验；删除仅适用于无冻结版本的草稿，已有实验来源请归档。
归档只是列表整理，不改变已有冻结内容和运行实例，也不是长期持久化。
