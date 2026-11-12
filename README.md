# CayleyWCS — 仓库控制系统

凯利智飞 WCS（Warehouse Control System），定位于 **WMS（上位业务）↔ 设备（下位 PLC/堆垛机/输送线等）** 之间，负责多协议设备通讯、连接治理、任务调度与实时监控。架构对齐 [CayleyWMS](../cayley_wms/)。

> 设计与里程碑详见计划：`C:\Users\<user>\.claude\plans\wcs-1-java-spring-wms-opc-mossy-hinton.md`（或团队内同步副本）。

2026-11-12 版本提交覆盖点位合同与开放接口、应用协议归属、统一监控看板、仙工导航和连续采集及资源删除保护。提交验证共 411 项后台测试（401 通过、10 项可选 PostgreSQL 用例跳过），前端类型检查、构建及 8 项点位编辑测试通过；历史验收中的现场设备地址已脱敏，部署配置和凭据保留在仓库外。源码注释仅精简过程标记，执行逻辑保持原样。

## 应用、协议与点位归属（2026-11-12）

仙工 AGV 新增应用级 IP 维护及站点导航下发：[`应用编辑页`](frontend/src/views/ApplicationView.vue) 维护 `conn_params.ip`，[`导航窗口`](frontend/src/components/AgvNavigationPanel.vue) 位于连接监控中。协议类型仍为 `tcp`，[`仙工连接设置`](backend/src/main/java/com/cayleywcs/adapter/seer/SeerConnectionSettings.java) 和 [`导航会话`](backend/src/main/java/com/cayleywcs/adapter/seer/SeerNavigationSession.java) 在应用适配器内处理地址及 API 3051 报文。V3 合同保留状态点位并增加导航目标、下发成功两点；[`导航服务`](backend/src/main/java/com/cayleywcs/device/agv/impl/AgvNavigationServiceImpl.java) 提供版本、租户、管理员权限、车辆空闲检查及持久请求去重。[`V27`](backend/src/main/resources/db/migration/V27__agv_navigation_command.sql) 仅新增下发记录表，历史导航引用同样禁止删除。下发成功不代表到站，结果未知不自动重发；后续下发需核对车辆并解除锁定。操作、接口、限制及测试入口见 [`仙工站点导航说明`](docs/seer-agv-navigation.md)。

当前层级为 **WCS 分配 AppID → 应用专属协议（可多份）→ 协议点位与发布版本**。应用先注册，随后维护自己的协议并选择固定发布版本；跨应用复用通过复制独立草稿完成，不能直接引用其他应用协议。AppID 沿用平台生成的应用 `id`，既有凭据及开放接口的 `app_id` 不变。[`ApplicationView`](frontend/src/views/ApplicationView.vue) 展示 AppID 和所属协议入口；[`ProtocolView`](frontend/src/views/ProtocolView.vue) 按应用筛选、创建、复制与维护点位。

[`V25`](backend/src/main/java/db/migration/V25__application_owned_protocols.java) 增加协议 `app_id` 和应用、协议、点位、发布版本的复合外键。共享旧协议按应用拆分，保持原生配置并同步固定的任务、链和授权引用；无明确引用的旧配置标记为只读历史模板，保留全部记录，使用前须复制到应用。AGV 与三传感器创建入口已改为先注册应用、再建协议并激活版本。详细说明与验收见 [`应用协议层级说明`](docs/application-protocol-ownership.md)，测试入口为 [`归属约束`](backend/src/test/java/com/cayleywcs/protocol/ApplicationProtocolOwnershipTest.java)、[`共享协议迁移`](backend/src/test/java/com/cayleywcs/protocol/ApplicationOwnedProtocolMigrationTest.java) 和 [`PG 升级演练`](scripts/postgres_migration_rehearsal.py)。

Docker 数据已按维护指令清除全部无归属历史模板及关联记录；清理完成时六个应用共保留 6 份协议、123 个点位、6 个发布版本。随后仙工导航升级新增一份本应用协议及不可变快照，保留原状态版本用于追溯，当前共 **7 份所属协议、168 个点位、7 个发布版本**，应用仍为六个，历史模板目录为空。清理范围、备份和业务保留验证见 [历史协议清理记录](docs/operations/2026-11-12-history-protocol-cleanup.md)；导航升级见 [站点导航说明](docs/seer-agv-navigation.md)。

应用和协议的删除现由 [`ResourceDeletionService`](backend/src/main/java/com/cayleywcs/application/ResourceDeletionService.java) 及其[事务实现](backend/src/main/java/com/cayleywcs/application/impl/ResourceDeletionServiceImpl.java) 执行实际删除。应用连同全部所属协议、点位、发布版本、故障配置、令牌、看板偏好和运行记录一起清理；单独删除协议会清理其全部点位与版本。任何任务、任务链或授权引用（包含历史任务、撤销授权和软删除记录）均拒绝删除；正在选用的协议、未关闭连接、任务号占用、共享采集依赖及待投递回调同样受保护。前端在确认框说明清理范围，并显示具体阻止原因。

[`V26`](backend/src/main/resources/db/migration/V26__resource_lifecycle_guard.sql) 新增单行生命周期锁，不改动既有业务数据。[`ResourceLifecycleGuard`](backend/src/main/java/com/cayleywcs/common/support/ResourceLifecycleGuard.java) 让 PostgreSQL 中普通引用写入共享锁、删除独占锁，防止“检查后新增引用”；仅在提交后清理对应的故障、采集和 AppKey 重放缓存，Redis 暂时不可用时由独立的 [`AppKeyReplayCache`](backend/src/main/java/com/cayleywcs/application/security/AppKeyReplayCache.java) 后台重试，不依赖旧签名过滤器是否启用，不清空其他应用或用户登录缓存。延迟到达的采集、日志、告警和回调入队也会重新核验归属。验证入口为 [`ResourceDeletionTest`](backend/src/test/java/com/cayleywcs/application/ResourceDeletionTest.java) 与支持 **V25→V26** 的 [`PG 升级演练`](scripts/postgres_migration_rehearsal.py)；规则和部署证据见 [安全删除说明](docs/application-protocol-deletion.md)。

## 点位合同与 HTTP 对接（2026-10-13）

协议点位支持新增、修改、删除，明确设备模板、请求或响应方向、业务或内部用途、逻辑与原生类型、必填规则及取值约束。草稿通过完整定义校验后发布为不可修改的快照；修改已发布协议须克隆新草稿。应用选择固定发布版本，上下游绑定由 [`ProtocolContractService`](backend/src/main/java/com/cayleywcs/protocol/contract/ProtocolContractService.java) 逐项核对两方向字段及约束。原生地址和 Modbus 字节序属于设备映射，类型别名通过 [`PointTypeRegistry`](backend/src/main/java/com/cayleywcs/adapter/PointTypeRegistry.java) 归一；未知类型、无损映射失败、非 1 缩放及 OPC UA UInt64 明确拒绝。

HTTP 任务只接收 `appId/taskNo/priority/wmsRef/payload`，所有设备点位放在 `payload`；任务链每段同样使用点位对象。管理端点位读写也使用 `payload`，运维写入要求维护模式且无活动任务、遗留设备任务号或物理动作。业务任务号保持原值，原生设备号由 [`TaskExecutionService`](backend/src/main/java/com/cayleywcs/task/TaskExecutionService.java) 持久分配并占用至设备完成清理，所有入口共享同一设备号分配器。

[`DeviceExecutionCoordinator`](backend/src/main/java/com/cayleywcs/connection/DeviceExecutionCoordinator.java) 协调设备写入、取消、连接轮询和配置切换；任务固定来源、绑定及发布版本，执行前重复校验。已被设备受理的任务撤权后安全收尾。完成响应在清反馈前持久保存为 `result={quality,capturedAt,payload}`；后续任务不会覆盖该结果。输送线与提升机另有持久化清理阶段，实际反馈空闲后才完成并释放设备号。公共查询使用 [`TaskPublicView`](backend/src/main/java/com/cayleywcs/task/TaskPublicView.java) 与 [`OpenPublicViews`](backend/src/main/java/com/cayleywcs/openapi/OpenPublicViews.java) 的固定字段，快照点位异常返回质量 `bad` 和空点位。

前端 [`ProtocolView`](frontend/src/views/ProtocolView.vue)、[`PointConstraintsEditor`](frontend/src/components/PointConstraintsEditor.vue)、[`BindingMatchDetails`](frontend/src/components/BindingMatchDetails.vue) 提供发布、固定版本、两方向匹配差异和约束维护；连接监控、任务页及看板统一展示点位对象。

逻辑类型和协议原生类型使用 [`PointTypeSelect`](frontend/src/components/PointTypeSelect.vue) 的完整基础类型、数组类型下拉列表，选中 BOOL 时不会过滤掉其他类型。[`pointTypes`](frontend/src/utils/pointTypes.ts) 提供中文说明；原生选项依照后台支持范围，另提供别名与固定长度数组的手动声明入口，既有声明在编辑和查看时保留原值。前端构建、类型检查及当前 Docker 浏览器的选择、保存、重开验证通过，见 [`类型选择验证证据`](docs/reviews/2026-10-13-point-type-selector-evidence.json)；模拟接口页面流程入口为 [`protocol-contract-ui.mjs`](frontend/tests/protocol-contract-ui.mjs)。

读写依照方向、用途和驱动流程处理，协议点位无需维护独立物理访问权限。对外请求用于参数写入，响应用于反馈采集；内部任务号读回、心跳、确认和清零由驱动显式执行。管理读取只开放业务响应，管理写入只开放业务请求且要求维护模式和设备空闲；应用授权、版本及类型校验继续生效。数据库 [`V20`](backend/src/main/java/db/migration/V20__remove_point_rw.java) 删除旧字段和废弃字典，清理发布快照时保留版本编号、历史指纹、绑定、任务和时间。对应验证见 [`PointRwRemovalMigrationTest`](backend/src/test/java/com/cayleywcs/protocol/PointRwRemovalMigrationTest.java)、[`AdapterPointDirectionTest`](backend/src/test/java/com/cayleywcs/adapter/AdapterPointDirectionTest.java) 与 [`本次部署联调证据`](docs/reviews/2026-10-13-rw-removal-evidence.json)。

协议点位已取消分组配置，新增、编辑、查看和管理接口不再携带分组字段；方向和用途继续用于匹配与驱动读写。数据库 [`V21`](backend/src/main/java/db/migration/V21__remove_point_group.java) 删除分组列、废弃字典及发布快照中的分组属性，保留原生地址中的 `From_WCS` / `To_WCS` 路径、版本身份、历史指纹和业务数据。验证入口为 [`PointGroupRemovalMigrationTest`](backend/src/test/java/com/cayleywcs/protocol/PointGroupRemovalMigrationTest.java) 和支持明确目标版本的 [`升级演练脚本`](scripts/postgres_migration_rehearsal.py)。本次最近后台39项、前端16项流程、实际PG副本19项迁移检查通过；Docker中的13项浏览器流程与9项真实HTTP到OPC UA验证通过，堆垛机任务102052完成且结果采集有效。见 [`分组移除验收`](docs/reviews/2026-10-13-group-removal-evidence.json) 与 [`V20到V21升级演练`](docs/reviews/2026-10-13-group-removal-migration-evidence.json)。

V17/V18/V19 分别增加点位与发布快照、任务版本及原生号预约、旧任务和链段的审计迁移。原请求、业务时间和终态保留；未知来源和冲突历史记录暂停等待核对，旧协议不会自动发布。升级演练见 [`PostgreSQL 证据`](docs/reviews/2026-10-13-postgres-migration-evidence.json)。协议仿真入口见 [`PROTOCOL_SIMULATION.md`](scripts/PROTOCOL_SIMULATION.md)，六类真实网络及 UInt64 边界见 [`传输证据`](docs/reviews/2026-10-13-transport-test-evidence.json)；执行与恢复集成用例见 [`TaskPayloadExecutionIntegrationTest`](backend/src/test/java/com/cayleywcs/task/TaskPayloadExecutionIntegrationTest.java)。正式 HTTP 上位到 OPC UA 堆垛机合同见 [`开放 API 文档`](docs/礁盘工业WCS开放API接口文档.docx)。

HTTP上位到OPC UA堆垛机和WMS完整业务的可重跑脚本为 [`http_stacker_business_e2e.py`](scripts/http_stacker_business_e2e.py)，WMS侧入口为 [`wcs-e2e.ps1`](../cayley_wms/scripts/wcs-e2e.ps1)。操作员须显式提供测试密码、仓库外工作目录和独立数据前缀，库区编码也使用该前缀，便于保留旧资料并建立新的仿真仓库；脚本仅连接已知容器内Milo仿真端点，按发布、固定版本、匹配授权、下发/查询、入库/整托移库/出库及回调实例采集分阶段验证。私有应用凭据和原始业务响应留在仓库外，测试数据保留。PostgreSQL复本演练脚手架见 [`POSTGRES_MIGRATION_REHEARSAL.md`](scripts/POSTGRES_MIGRATION_REHEARSAL.md)。

连接服务退出仅关闭传输，保留 `desired_state=open`，启动后按固定发布版本恢复连接；管理端关闭和配置切换仍明确保存关闭意图。故障失败任务保留设备号，专用复位确认故障清除及物理空闲后才释放，重试重新预约设备号。连接读取、状态列表及关闭按当前账户固定归属过滤。

点位合同初次实施验收见 [`2026-10-13-protocol-point-contract-evidence.json`](docs/reviews/2026-10-13-protocol-point-contract-evidence.json)，包含业务过账、重复回调、重启、管理API及前端证据。删除独立物理访问配置后的最新后台报告329通过、4可选PG门禁跳过、零失败，包含六协议真实网络11项；实际PG V19→V20副本19项检查通过，见 [`本次升级演练`](docs/reviews/2026-10-13-rw-removal-migration-evidence.json)。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 25 · Spring Boot 4.0.6 · MyBatis-Plus 3.5.16 · PostgreSQL 17 · Redis 7.4 · Flyway · springdoc |
| 工业驱动 | Apache PLC4X（OPC UA/Modbus/S7）· Paho（MQTT）· WebClient（HTTP）· Netty（TCP）· Eclipse Milo（仿真器）｜ M2/M3 引入 |
| 前端 | Vue 3 · Vite · TypeScript · vue-router · axios（精简手写 UI，工业橙主题，无第三方组件库）｜ M6 引入，M14 补齐全部管理页 |

## 端口（避开 WMS 的 20011/5174/5432/6379）

| 服务 | Host 端口 | 容器内 |
|---|---|---|
| backend | 20021 | 20021 |
| frontend（M6） | 5184 | 80 |
| PostgreSQL | 5433 | 5432 |
| Redis | 6380 | 6379 |

- Swagger：`http://127.0.0.1:20021/swagger-ui.html`
- 本地测试账号：`admin / 1`

## 后端分层（`com.cayleywcs`）

| 包 | 职责 |
|---|---|
| `common.api` | 统一响应 `ApiResponse` / 分页 `PageData`·`PageSearch`·`PageSupport`（小表内存切片，大表可用数据库 COUNT + LIMIT/OFFSET）/ `IdRequest` |
| `common.exception` | 全局异常处理 `GlobalExceptionHandler` + `WcsException` + `ErrorCode` |
| `common.security` | `CurrentUser` |
| `config` | 安全/CORS、OpenAPI、`SchedulerConfig`（4 线程核心定时池 + Webhook/监控各单线程池）、`WcsMetrics`/`WcsRequestMetricsFilter`/`WebhookBacklogMetrics`（固定标签指标） |
| `auth` | 管理端 JWT 登录链路；预留 APISIX/Keycloak flag |
| `system` | 当前用户、用户/角色（登录用） |
| `health` | 健康检查 `/health` |
| `dict`（M1） | 数据字典：协议类型等枚举单一事实源 |
| `protocol`（M1） | 协议表 + 协议点位/字段映射 |
| `application`（M1） | 应用信息表、稳定 app_id/app_key、密钥重置；每个应用固定归属 |
| `openapi` | 对接应用 `POST /system/token` 颁发 7200 秒 access_token；开放业务采用 `app_id/app_key/access_token/request_id/data` JSON 封装，返回 `errcode/errmsg/request_id/timestamp/data`。逐次校验应用状态、密钥、固定归属和设备绑定权限；管理端 JWT 与回调签名独立 |
| `binding` | 应用绑定授权：`wcs_app_binding`(HTTP上位→已实现下位，完整点位合同及方向校验、禁自绑定) CRUD + **按应用整批授权**(`grant`/`grantedDownstreamIds`) + 下发越权判定(`isAllowed`/`assertAllowed`) |
| `faultcode`（M1） | 故障码表 + 解析（未维护走统一兜底） |
| `connection`（M2/M12） | 连接治理：线程池 + 信号量 + 60s 超时回收 + 看门狗重连；**单机自愈**（M12）：`open/close` 落 `desired_state`（用户意图）、看门狗 rearm（FAILED+desired=open 冷却后重入重连 / desired≠open 释放槽）、`ConnectionRecoveryRunner`（启动+周期对账把 desired=open 未连应用拉起）、建连成功防御复位 `cmd_ResetFault=0`；`ManagedConnection` 保留连接建立时的只读点位列表与名称索引，重复名称取首项 |
| `adapter`（M2） | 常规协议适配器 SPI + 独立应用适配器（`ApplicationAdapterProvider` / `ApplicationAdapterCatalog`），在应用层选择仙工帧或 DR164 共享采集，不注册设备专用协议类型 |
| `dashboard` | [看板服务](backend/src/main/java/com/cayleywcs/dashboard/impl/DashboardServiceImpl.java)：已发布点位与缓存状态、按租户/账号/应用保存展示选择、版本与点位集合校验 |
| `device`（M8/M9） | **设备类型 SPI**：`DeviceHandler`/`AbstractDeviceHandler`（advanceGuarded 故障闸+传输异常放弃、claim 占位、bumpStall 死等检测、payload 取值）/`DeviceHandlerRegistry`，按 `wcs_application.device_type` 路由（未知类型：保存时拒绝 + 运行期报警不静默）；实现：`task/StackerHandshakeStateMachine`(stacker/palletizer)、`device/conveyor`(输送线)、`device/lift`(电梯)、`device/autodoor`(自动门)。新增设备类型零改引擎，接入见 `docs/device-handler-template.md` |
| `task`（M4/M8） | 任务调度引擎（tick 只扇出 worker 池 + 设备级单飞 + `wcs_task.version` 乐观锁）+ 堆垛机三段握手状态机（stacker/palletizer 的 DeviceHandler，含确认步重入容错）+ `TaskStatus` 状态常量 |
| `task.orchestrate`（M10） | **任务链编排**：`ChainService`（chain_no 幂等下发/级联取消）+ `TaskOrchestrator`（无内存态，每 tick 从 DB 派生；段严格串行；物化前先赢链行版本防 cancel 竞态；确定性 task_no=chainNo-seq 崩溃重放幂等；候选组激活可配超时报警 -3）+ **双策略 SPI**：选机 `DeviceSelectionStrategy`(idle_first/round_robin) 与派工 `TaskSelectionStrategy`(priority/fifo，按 `wcs_application.dispatch_strategy` 路由；在飞优先是正确性约束非策略) |
| `monitor`/`alarm`（M5） | WebSocket 实时推送 + 报警生命周期；`MonitorPusher` 使用 Spring 配置的 JSON 序列化器；管理端经 `POST /monitor/ws-ticket` 用 Bearer JWT 换一次性 30 秒握手票据，`/ws/monitor` 校验票据与 Origin，并在 JWT 到期时关闭连接 |
| `stats`（M11） | 运行统计：任务状态与故障码 SQL 聚合、完成任务时长按原逐行毫秒规则流式求均值；报警窗口查询只返回 TOP；连接日志按 JDBC 游标重放。多语句统计入口使用只读 `REPEATABLE_READ` 快照，保留 H2/PG 口径 |
| `webhook`（M13） | **主动回调（游标 outbox，崩溃安全）**：`WebhookScanner`（每 webhook×每源 keyset 游标 (last_update_time,id) 扫 task/alarm/chain 终态→分类事件→scope 匹配→插投递，游标推进到已处理最后一行）+ `WebhookDeliveryWorker`（HMAC 签名头 ts+nonce+sha256(body) POST，指数退避超 max-attempts 置 failed，SSRF：scheme+host allowlist+禁重定向；停机/中断后不启动本轮后续投递）+ `WebhookService/Controller` CRUD/投递查询 |
| `simulator`（M3/M9） | 堆垛机 OPC UA 仿真服务端（profile `simulator`）；**通用内存仿真** `DeviceSim`/`AbstractDeviceSim`/`DeviceSimRegistry`/`DeviceSimFactory`（按 device_type 建模）+ Stacker/Conveyor/Lift/AutoDoor 四种设备态；`SimulatorController` 故障注入/复位/快照按类型透传 |

## 文档

- **仙工 AGV 只读状态与三传感器连续采集（2026-11-12）**：[接入说明](docs/agv-readonly-telemetry.md)、[实施与验证日志](.logs/2026-11-12-agv-readonly-telemetry-implementation.md)。使用 `tcp` 协议与独立的 `seer_agv` / `dr164_sensor` 应用适配器，1004/1005/1020 状态缓存；独立四读块连续采集、12 指标、供电预热、6 秒陈旧、每网关 60 轮存储队列与 30 天历史；[V22](backend/src/main/resources/db/migration/V22__agv_continuous_telemetry.sql) 新增网关/最新值/历史表。三外设默认 Unit ID 80/1/2，红外 0x0000、暂定 0–600℃，目标 2 秒轮询、请求间隔 ≥250 ms。导航与其他运动写入尚未开放，传感器不创建 WCS 任务。新 API、服务、字段和测试路径均见接入说明。

- **现有 Docker 部署（2026-11-12）**：后端 `agv-navigation-20261112`、前端 `agv-navigation-20261112-ui1`，数据库 V27，发布配置在仓库外 `20261112-agv-navigation` 目录。仙工应用已启用 V3 状态与导航合同（协议 40、发布版本 18、45 个点位），IP 在应用界面维护，其他五个应用配置保留；见 [导航使用说明](docs/seer-agv-navigation.md) 和 [部署证据](docs/operations/2026-11-12-agv-navigation-evidence.json)。本次只替换前后端容器，PostgreSQL、Redis 容器和数据卷不变，沿用 Temurin 25.0.5+8 和完整 C2 编译。AGV 及三传感器仍分别使用通用 TCP 的 `seer_agv`、`dr164_sensor` 应用适配器；DR164 未配置时显示“待配置采集端点”。原有 [安全删除规则](docs/application-protocol-deletion.md) 继续生效。21:04 JST 曾再次发生 C2 编译线程崩溃，根因仍未闭环；本次业务验证不代表 JVM 根因已修复，见 [复发证据](docs/deployments/2026-11-12-jvm-c2-recurrence-evidence.json) 与 [JVM 事故处理](docs/deployments/2026-11-12-jvm-c2.md)。初次接入见 [AGV 部署记录](docs/deployments/2026-11-12-agv-readonly.md)。
- **JVM 编译验证**：[后台 Dockerfile](backend/Dockerfile) 将已验证的 JRE 版本及摘要固定；[MyBatis C2 探针](scripts/jvm_c2_probe.py) 从实际 Spring Boot JAR 提取 MyBatis，编译并运行 [参数读取与动态 SQL 用例](scripts/jvm/MyBatisC2Probe.java)，校验默认最高层级 4、实际目标方法 C2 编译和结果正确性。它不是原 SIGSEGV 的确定性复现器，不以探针通过冒充唯一根因证明。
- **当前应用数据清理（2026-11-12）**：[清理与验证记录](docs/operations/2026-11-12-application-cleanup.md)。按用户要求保留 `PALLET_SIM`、`WMS-MAIN`、`SEER_AGV_01` 和振动/噪声/红外温度三个传感器，共 6 个应用；其余 19 个应用及关联任务、链、授权、独占协议、旧令牌和占用计数已在同一事务中删除。正式备份已完整恢复到 PG17 副本复验，28 项保留数据校验通过；WCS Redis 缓存已清空，后台已重启清除内存缓存。原发布镜像、现场端点与 V24 结构沿用。

- **统一应用与状态设置**：AGV v2 的 40 个业务状态点位与 3 个诊断报文点位，以及振动/噪声/红外温度三个独立应用的 10/1/1 个测量点位，统一在 [连接监控](frontend/src/views/ConnectionView.vue) 查看。三个传感器共用一个 DR164 TCP 采集器，独立启停。业务反馈只读，业务命令按维护模式、物理空闲、任务占用和协议版本判定是否允许设置；所有条件后台复验。实现、V23、接口与测试入口见 [接入说明](docs/agv-readonly-telemetry.md#应用点位与状态设置)。
- **实时看板与应用适配器（2026-11-12）**：[DashboardDeviceCard](frontend/src/components/DashboardDeviceCard.vue) 根据实际点位显示两列有边框网格，长数组/文字自动换行，400px 高卡片内滚动；右上“看板展示编辑”支持中文点位复选，选择按账号和应用保存，状态移至应用名称同一行。[pointLabels](frontend/src/utils/pointLabels.ts) 保留人工维护的中文名，并为旧 PLC 自动别名提供中文说明。[V24](backend/src/main/resources/db/migration/V24__application_adapters_and_dashboard.sql) 将 `seer_tcp` / `sensor_gateway` 归并为通用 TCP，应用管理独立选择报文适配器；[应用适配器目录](backend/src/main/java/com/cayleywcs/adapter/ApplicationAdapterCatalog.java) 校验协议、设备类型和方向。API、结构、迁移与测试见 [实现说明](docs/dashboard-and-application-adapters.md)，执行及验收见 [本次日志](.logs/2026-11-12-dashboard-adapters.md)。
- **点位状态面板与逐点设置**：[`PointStatePanels`](frontend/src/components/PointStatePanels.vue) 将 AGV 状态按定位/运动/导航展示为卡片，传感器按测量项目分区；界面分区不引入协议点位分组字段。可写卡片旁提供“设置”，[`PointValueEditor`](frontend/src/components/PointValueEditor.vue) 按数值、布尔、枚举、文字、数组类型打开编辑窗口；只读项展示权限，暂不允许设置时说明原因。命令值不冒充设备反馈。[`statePoints`](frontend/src/utils/statePoints.ts) 负责类型校验、无损 64 位整数、数据过期和自动门互斥联动；断线或撤权禁用提交，版本变化取消旧编辑。验证入口：[`8 组纯函数测试`](frontend/tests/state-points.test.mjs)、[`独立页面夹具`](frontend/tests/state-panels-fixture.mjs)（仅回环监听，无设备转发）及 [`本次日志`](.logs/2026-11-12-state-panels.md)。
- **状态窗口显示密度**：[状态窗口](frontend/src/views/ConnectionView.vue) 随视口铺开、四周保留 8px，点位区域占满剩余高度；[卡片](frontend/src/components/PointStatePanels.vue) 保持分组与内容顺序，最小宽度 220px、内边距 6/10px、间距 6px，数值字号 19px。实际 1280×720 页面为每行 5 个点位，同屏完整卡片由前版 12 个增至 19 个，首卡高度约 155→113px；数据、权限及设置入口保留。构建、类型检查与当前 Docker 页面验收见 [进一步紧凑布局日志](.logs/2026-11-12-state-panels-dense.md)；前次扩大窗口记录见 [紧凑布局日志](.logs/2026-11-12-state-panels-compact.md)。
- **AGV 看板中文术语**：[`agvLabels.ts`](frontend/src/utils/agvLabels.ts) 集中维护设备枚举及连接、采集、存储状态的中文说明；[状态设置](frontend/src/views/ConnectionView.vue) 使用协议点位中文名称并显示原字段编码。实时看板的 AGV 状态同步使用“状态采集中”等术语；解释边界见 [接入说明](docs/agv-readonly-telemetry.md#看板中文术语)。

- **仙工 AGV 运动与三传感器连续监测对接方案（2026-11-12）**：[`docs/plans/2026-11-12-agv-motion-sensor-integration-plan.md`](docs/plans/2026-11-12-agv-motion-sensor-integration-plan.md)。依据当前任务/协议/连接源码、`seer-agv-operator` 和三传感器说明书，首期采用单车、单执行实例，WCS 直连 SRC 站点导航与 DR164 单连接串行采集；明确 AGV 专属执行/恢复策略、协议模板、质量与工况、数据表、POST 接口和阶段验收，预留到站采集。只读与连续监测软件模块已实施并部署；导航闭环和到站采集尚未实施。
- **协议点位与统一 payload 改造计划（2026-10-13）**：[`docs/plans/2026-10-13-protocol-point-contract-plan.md`](docs/plans/2026-10-13-protocol-point-contract-plan.md)，包含点位维护、请求/响应方向、跨协议类型匹配、绑定版本治理、WMS 联动及真实协议仿真验收；已完成实施，当前状态见本文点位合同说明与对应验收证据。
- **系统架构与任务优先级**：[`架构说明`](docs/architecture/wcs-system-architecture.md)、[`高清架构图`](docs/architecture/wcs-system-architecture.png)、[`矢量架构图`](docs/architecture/wcs-system-architecture.svg)。覆盖当前HTTP接入、单进程后台模块、多设备驱动、多协议适配、监控回调和存储；说明优先级在同设备待派工队列中的生效条件，以及在飞任务和任务链的边界。当前Docker配置读取和19项现有测试验证见 [`核验记录`](docs/reviews/2026-10-13-architecture-priority-evidence.json)。
- **堆垛机任务执行时序**：[`时序说明`](docs/architecture/stacker-task-sequence.md)、[`PNG原图`](docs/architecture/stacker-task-sequence.png)、[`SVG原图`](docs/architecture/stacker-task-sequence.svg)。依据当前HTTP到OPC UA堆垛机实现说明异步受理、调度、参数写入、执行及完成确认、结果先保存、清零空闲和终态通知；图嵌入正式开放API文档的下发单任务一节，核验见 [`时序图文档验证`](docs/reviews/2026-10-13-stacker-sequence-document-evidence.json)。
- **堆垛机故障回传与原始OPC UA核查**：[`实际实验报告`](docs/reviews/2026-10-13-wms-stacker-fault-report.md)、[`机器证据`](docs/reviews/2026-10-13-wms-stacker-fault-evidence.json)及[`原始协议对照`](docs/reviews/2026-10-13-stacker-opcua-conformance.md)。当前Docker WMS到真正Milo OPC UA的48项号码逐项传递通过，补测多故障、数组末槽、执行中故障和恢复过账；WMS只接收首个任务故障码并跳过实时报警。实测确认正常复位48被判失败、PLC心跳冻结不告警，任务号原生类型与反馈枚举仍有协议差异，当前不能认定完全符合原始资料。原连接已恢复，测试业务数据保留。
- **《礁盘工业WCS开放API接口文档》**：[`docs/礁盘工业WCS开放API接口文档.docx`](docs/礁盘工业WCS开放API接口文档.docx)。按当前 WMS API 文档版式编写，依据当前代码和联调数据说明令牌获取、六个开放业务接口、回调、数据模型、相关枚举和错误码。说明文字中的字段采用中文在前、空格后接英文标识的形式；字段表、接口路径和调用实例保留原始标识。下发单任务一节新增当前堆垛机时序图，文字、真实目录页码及逐页排版验证见 [`最新文档验证`](docs/reviews/2026-10-13-stacker-sequence-document-evidence.json)。
- 应用令牌实现：[`ApplicationTokenService`](backend/src/main/java/com/cayleywcs/openapi/ApplicationTokenService.java)、[`ApplicationTokenFilter`](backend/src/main/java/com/cayleywcs/openapi/ApplicationTokenFilter.java)，迁移 [`V16__open_application_token.sql`](backend/src/main/resources/db/migration/V16__open_application_token.sql)。原逐请求 AppKey 签名不再作为开放接口认证入口。
- 定向验证：[`ApplicationTokenIntegrationTest`](backend/src/test/java/com/cayleywcs/openapi/ApplicationTokenIntegrationTest.java) 与既有 `ReconcileAuthTest` 验证真实 HTTP 令牌生命周期、身份不匹配和设备授权边界。
- Docker 部署、WMS 联调、令牌重启持久性及文档字段验证见 [`2026-10-12-open-api-token-integration-evidence.json`](docs/reviews/2026-10-12-open-api-token-integration-evidence.json)，执行记录见 [当日联调日志](.logs/2026-10-12-wcs-application-token-integration.md)。

- **WCS 优化方案（审核修订版，2026-09-29）**：[`docs/optimization-proposal-2026-09-29.md`](docs/optimization-proposal-2026-09-29.md)（分页/索引/统计/监控优化边界；补齐活动报警 WS 帧、WS 握手访问控制、调度池绑定、停机中断和统计快照约束；实施与验收记录见同日 `.logs/2026-09-29_wcs-optimization-implementation.md`）。

优化实施进度：A 的任务/报警/链列表改为数据库 COUNT + LIMIT/OFFSET，入口使用只读 `REPEATABLE_READ`；[`DatabasePaginationTest`](backend/src/test/java/com/cayleywcs/common/api/DatabasePaginationTest.java) 与 [`PageSnapshotPostgresTest`](backend/src/test/java/com/cayleywcs/common/api/PageSnapshotPostgresTest.java) 分别验证固定分页和 PG 并发快照。B 的 [`V15__optimization_indexes`](backend/src/main/java/db/migration/V15__optimization_indexes.java) 根据隔离库 10 万行执行计划添加派工及单应用连接日志索引，PG 并发建索引使用会话级 Flyway 锁，H2 使用等效普通索引。C 的统计按 SQL 聚合和流式读取，由 [`StatsWindowEquivalenceTest`](backend/src/test/java/com/cayleywcs/stats/StatsWindowEquivalenceTest.java) 及可选真实 PG 快照、游标测试覆盖。D 的看板采用单飞轮询、WS 完整帧/seq/过期检测、12 秒无帧主动重连、票据握手及断线恢复；真实握手与报警生命周期由 [`MonitorWebSocketIntegrationTest`](backend/src/test/java/com/cayleywcs/monitor/MonitorWebSocketIntegrationTest.java) 验证。E/F 的调度分池、Webhook 中断处理、连接点位名称索引由定向测试覆盖。G 的指标通过需 JWT 的 `/actuator/metrics` 查询，积压每分钟采样；[`WcsMetricsIntegrationTest`](backend/src/test/java/com/cayleywcs/config/WcsMetricsIntegrationTest.java) 覆盖端点权限与指标生成。后端全套测试、前端构建、真实 PG/Redis 冒烟与 OPC UA 仿真联调通过；生产数据规模、深页和长游标负载仍需在部署前评估。
- **Postman 联调集合**：`docs/CayleyWCS-WMS-Loop.postman_collection.json`（包含应用令牌获取、公共 JSON 封装、独立任务号与状态查询）。
- **《礁盘工业WCS使用功能说明书》**：`docs/礁盘工业WCS使用功能说明书.docx`（参考 WMS 说明书结构，按代码梳理全部模块/接口/数据表 + 业务逻辑图/握手时序/连接状态机）。
- 生成器（python-docx + PIL，同 WMS 套路）：`docs/manual_assets/build_wcs_manual.py`，运行即重建文档与图（`docs/manual_assets/rendered/*.png`）；点位表字段已同步V20，移除旧读写字段。

## 数据库

Flyway 迁移位于 `backend/src/main/resources/db/migration/`：

| 文件 | 内容 |
|---|---|
| `V1__wcs_schema.sql` | 全部表：认证、`wcs_dict_*`、`wcs_protocol`/`wcs_protocol_point`、`wcs_application`、`wcs_fault_code`、`wcs_task`、`wcs_alarm`、`wcs_connection_log`、`wcs_message_log` |
| `V2__seed_dict_and_admin.sql` | 管理员 admin/1 + 协议类型/方向/任务/连接状态等字典种子 |
| `V3__seed_stacker_protocol.sql` | 堆垛机标准协议(6.18 修订版) + 27 点位(DBW0–182) + 故障码 1–48 种子 |
| `V4__seed_pallet_opcua_app.sql` | **礁盘工业码垛机** OPC UA 协议(NodeId 寻址 `ns=3;s="WCS_Task"."<组>"."<字段>"`) + 应用 + 点位/故障码(克隆自 6.18)。适配器 `StackerOpcUaAdapter`(PLC4X OPC UA + WCS_Heart 主动心跳) |
| `V5__seed_opcua_sim_app.sql` | **码垛机OPCUA仿真** 协议+应用(`PALLET_SIM`)，指向内置 Milo 仿真器(`ns=2;s=WCS_Task.<组>.<字段>`)，用于无硬件 OPC UA 联调 |
| `V6__seed_app_binding.sql` | **应用绑定授权表** `wcs_app_binding`(上位侧→下位侧)。dispatch 越权防护依赖此表（建表时含自绑定种子，V8 已清除） |
| `V7__seed_wms_upstream_app.sql` | **上位 WMS 主系统** 应用(`WMS-MAIN`,`direction=upstream`,`ak_wms_main`) + 授权它指挥全部下位设备，作为「上位侧应用指挥下位侧应用」的示例 |
| `V8__app_centric_binding.sql` | **去除自绑定**：删除全部 `upstream==downstream` 行。绑定语义保持「上位侧→下位侧」但**不按 direction 锁方向**（任意应用皆可任一侧，含「下位→下位」），仅禁止自指 |
| `V9__device_type_and_task_version.sql` | **M8**：`wcs_application.device_type`（默认 stacker；字典 `device_type` 十项，agv/shuttle 预留电量语义）+ `wcs_task.version` 乐观锁列；存量订正（PALLET01/PALLET_SIM→palletizer，upstream 应用→none）在本迁移内完成，不改历史文件 |
| `V10__seed_conveyor_lift_door.sql` | **M9**：输送线/电梯/自动门三套 `sim` 协议 + 点位 + 故障码 + 仿真应用（`CONVEYOR_SIM`/`LIFT_SIM`/`DOOR_SIM`，各带 `device_type`）+ WMS-MAIN 上位授权 |
| `V11__task_chain.sql` | **M10**：`wcs_task_chain` 链表（chain_no 幂等唯一索引 + version 乐观锁 + upstream_app_id 下发者 + segments JSON）；`wcs_task` 加 `chain_id/seq_no/retry_count/max_retry` + `(chain_id,seq_no)` 唯一索引；`wcs_application` 加 `work_mode`（normal/paused/maintenance）/`dispatch_strategy`（priority/fifo）；三组字典 |
| `V12__failure_policy.sql` | **M11**：`wcs_application` 加 `failure_policy`（manual 转人工/auto_retry 自动重试，上限=任务 `max_retry`>0 ? 任务级 : 应用 `max_retry`——该列自此承担任务重试默认上限语义）+ `task_stale_seconds`（在飞停滞超时，0=关）；字典 + `task_type=charge` 充电预留 + message_log 时间索引 |
| `V13__desired_state.sql` | **M12**：`wcs_application` 加 `desired_state`（open/closed，用户连接意图，重启后 `ConnectionRecoveryRunner` 据此自动恢复）+ 字典 |
| `V14__webhook_and_sign_v2.sql` | **M13**：`wcs_webhook`（订阅：url/secret/event_types/scope_app_ids）+ `wcs_webhook_cursor`（每源独立 keyset 游标）+ `wcs_webhook_delivery`（投递记录，唯一键 webhook×event×biz 幂等）+ `wcs_application.min_sign_version`（签名 v2 灰度强制） |
| `V15__optimization_indexes.java` | **优化**：PostgreSQL `CREATE INDEX CONCURRENTLY` 部分/覆盖索引（待派工优先级、单应用状态变更日志）；H2 等效前缀索引。PG 失败遗留的无效同名索引可在重试时重建，Flyway 使用 `spring.flyway.postgresql.transactional-lock=false` |
| [`V20__remove_point_rw.java`](backend/src/main/java/db/migration/V20__remove_point_rw.java) | 删除点位旧读写字段和对应字典，清理已发布点位快照，保留发布身份及所有其他定义；读写由方向、用途及驱动决定 |
| [`V24__application_adapters_and_dashboard.sql`](backend/src/main/resources/db/migration/V24__application_adapters_and_dashboard.sql) | 设备专用协议分类归并为 TCP，应用保存独立报文适配器；新增按租户/账号/应用隔离的看板点位选择表，点位、发布身份和业务记录保留 |
| [`V25__application_owned_protocols.java`](backend/src/main/java/db/migration/V25__application_owned_protocols.java) | 应用拥有协议；新增复合外键约束，拆分共享旧协议并保留业务引用，无引用旧协议作为只读历史模板保留 |

## 无硬件 OPC UA 联调（Milo 仿真服务端）

```powershell
docker compose up -d postgres redis
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local,simulator"   # 启动 Milo OPC UA Server：opc.tcp://127.0.0.1:4840/cayleywcs/stacker
```

然后对应用 **码垛机OPCUA仿真(`PALLET_SIM`)** 建连（前端连接监控页或 `POST /connection/open`）——WCS 经 PLC4X 走真·OPC UA 连到仿真器，可读状态/写命令/跑三段握手。注入故障：`POST /simulator/inject-fault {"appId":999001,"code":8}`。

> **联调观测（`docker logs -f cayleywcs-backend` / Docker Desktop 可见）**：Milo 服务端 `StackerNamespace` 的 setValue 过滤器（`simulator` profile 生效）在收到 WCS 写入时，打印**原始 OPC UA 写入包**（不做自定义格式化，便于核对指令与数据是否正确）：`[OPC UA写入] node=ns=2;s=WCS_Task.From_WCS.cmd_TaskNum dataValue=DataValue{value=Variant{value=...}, status=..., sourceTime=..., serverTime=...}`（跳过每秒心跳 `WCS_Heart`）。下发一个任务即可看到取放坐标/任务号/任务类型/确认位逐条原始写入。
>
> 看当前寄存器值用 `POST /simulator/snapshot {"appId":999001,"code":0}`（code 必填）；看历史逐字段用 `POST /audit/message-log/list {"appId":<设备id>,"limit":40}`。

> 注：jsonb 字段(`conn_params`/`data_format`/`payload`)用 `Map<String,Object>`（Spring Boot 4 = Jackson 3，避免与 MyBatis-Plus Jackson 2 的 `JsonNode` 跨版本冲突）；PLC4X 连接非并发安全，读写已在 `Plc4xAdapter` 串行化。

JSON 字段（`data_format`/`conn_params`/`payload` 等）以 text 存储，由 MyBatis-Plus `JacksonTypeHandler` 序列化，PG 与 H2 测试双兼容。

## 本地运行

```powershell
# 1) 起依赖
docker compose up -d postgres redis
# 2) 起后端（local profile；联调加 simulator）
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

完整容器编排（pg `5433` + redis `6380` + backend `20021` + frontend `5184` 四容器）：

```powershell
Copy-Item .env.example .env          # 可选，缺省走 compose 默认值
docker compose --profile full up -d --build
```

- 后端容器默认带 **`simulator` profile**（`SPRING_PROFILES_ACTIVE`）——内置 Milo OPC UA 仿真器随后端同进程启动（容器内 `127.0.0.1:4840` 自连），开箱即可跑 Postman 下发闭环。
- 对接**真实 PLC** 时关掉仿真器：`.env` 设 `CAYLEYWCS_BACKEND_PROFILES=`（置空）后重建。
- 验证：`GET http://127.0.0.1:20021/health` → `UP`；前端 `http://127.0.0.1:5184`；账号 `admin/1`。

## 进度

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 脚手架 | ✅ 完成 | 认证(JWT,admin/1)、健康、Flyway(V1-V3)、Docker 骨架、全局异常处理；编译+H2 上下文通过 |
| M1 配置中台 | ✅ 完成 | dict/protocol/protocol_point/application(双向 AppKey)/faultcode CRUD + 故障码解析；堆垛机协议+点位+0-27 故障码种子；`SchemaSeedTest` 通过 |
| M2 适配器+连接池 | ✅ 完成 | 适配器 SPI(Adapter/Factory/Template/Strategy)+ 连接治理(信号量/线程池/60s 超时回收/看门狗/状态机)；适配器实现：**OPC UA/Modbus TCP/S7(Apache PLC4X)、MQTT(Paho v5)、HTTP(JDK)、TCP(Socket)、loopback**；`ConnectionManagerTest` 通过 |
| M3 仿真器+审计 | ✅ 完成 | 审计落库(连接事件→`wcs_connection_log`、手动读写→`wcs_message_log`)；**内存堆垛机仿真器**(sim 适配器) `StackerSimEndToEndTest` 通过；**Milo OPC UA 线上仿真服务端**(`OpcUaStackerSimulator`，profile `simulator`)已落地——真·OPC UA 端到端联调通过(PLC4X→Milo：连接/心跳/读状态/写命令/三段握手/注故障) |
| M4 任务调度 | ✅ 完成 | `wcs_task` CRUD + **堆垛机三段握手状态机**(检查→下发→执行确认→等待→完成确认→清零) + 任务引擎(设备级单飞@Scheduled) + `/open/task/dispatch`(AppKey,幂等)；`StackerHandshakeTest`(完成+故障失败)通过 |
| M5 监控+报警 | ✅ 完成 | 报警生命周期(产生/确认/清除/历史+去重) + **故障码→报警联动**(轮询故障边沿→查故障表→报警,未维护走兜底) + 连接断开/恢复联动 + **WebSocket 实时推送**(`/ws/monitor` 连接快照/槽位/活动报警)；`AlarmFlowTest` 通过 |
| M4/M5 协议对齐加固 | ✅ 完成 | ① 故障码数组扫描：`detectFaultEdge` 支持 `ARRAY[1..60] OF INT`(真实PLC)与标量(仿真)双模式，多码边沿检测逐个发事件；`StackerHandshakeStateMachine` 新增 `readFirstFaultCode` 数组感知读取 + `doCheck` 改用 `hasActiveFault`。② 任务下发顺序对齐 6.18 协议：地址(排/列/层)→货口→任务号→**最后写任务类型**。③ 故障复位：新增 `ConnectionManager.resetFault`(写 `cmd_ResetFault` 1→0 电平握手) + `POST /alarm/reset-fault` 接口 + 仿真器 `handleResetFault`。26 用例全绿。 |
| M6 Vue 前端 | ✅ MVP 完成 | Vue3+Vite+TS 精简脚手架(reefplex 风)：登录 + **协议管理/应用管理(AppKey)/授权绑定/绑定明细/连接监控/实时看板(WebSocket)** 6 页；`npm run build` 通过。其余页(字典/故障码/任务/报警中心/统计/Webhook)于 M14 补齐 |
| M7 测试/文档 | ✅ 基本完成 | 架构约束+接口契约测试(全 POST+JSON/Service 接口+impl/Mapper)、前端 Dockerfile+nginx 模板+compose frontend 服务、父级 `cayley_project` 注册表登记。allinone 单容器镜像列为可选项 |
| M8 内核重构 | ✅ 完成 | **设备类型 SPI**（`device` 包，stacker/palletizer 首个 handler，`DispatchRequest` 加 `payload` 通用参数）+ **并发地基修复**（调度池 `spring.task.scheduling.pool.size=4`、tick 只扇出 worker 池防慢设备饿死全局、`wcs_task.version` 乐观锁防取消/推进丢失更新）+ **确认步重入修复**（`doConfirmExecute` 按 `status_TaskNum` 归属核对续推，防崩溃/冲突窗口活锁）+ **开放接口越权补齐**（`OpenAccessGuard`：query/snapshot 须自查或绑定授权，`appId` 缺省收敛为授权集）+ 仿真注册表泛化（`DeviceSim`/`DeviceSimRegistry`）。V9 迁移 |
| M8 对抗审查加固 | ✅ 完成 | 5 镜头×2 反驳者多智能体审查后修复：① **cancel 竞态占位提交**——执行确认（不可回滚设备写）前先以乐观锁 `claim` 落库赢版本，管理端取消赢了则本步废弃、设备零写入；② **传输/生命周期异常不落终态**——连接拆除/读写超时/停机中断只放弃本步下轮续推（原 catch-all 会把执行中任务误判 FAILED），配合引擎**优雅停机**（先 `awaitTermination` 再放行连接销毁）；③ 重入归属核对改**等值匹配**（含 0==0，task_no 无数字不再误判）；④ **复位防护**：确认前回读命令区任务号不符→回退重写参数（防 PLC 复位后以清零坐标起动）；等待完成期间 `status_Task` 回 0 连续 3 轮→判失败（防无限空转饿死队列）；⑤ 越权可见集**按租户过滤**（`grantedDownstreamIds(upstream, tenant)` 与 `isAllowed` 口径一致）；V9 字典种子补 not-exists 守卫（棕地防重复） |
| M9 新设备类型·仿真先行 | ✅ 完成 | 设备类型 SPI 泛化落地：`AbstractDeviceHandler` 提炼 `advanceGuarded` 模板（故障闸+传输异常放弃+M8 全部加固复用）；新增 **输送线/电梯/自动门** 三种 handler（`device/conveyor|lift|autodoor`，各自步枚举+claim占位+死等检测，任务参数走 `payload`）；仿真泛化 `AbstractDeviceSim` + Conveyor/Lift/AutoDoor 内存态 + `DeviceSimFactory` 按类型建模，`SimAdapter`/`SimAdapterProvider`/`SimulatorController` 全部按 device_type 透传（保留堆垛机门面向后兼容）；V10 三设备 sim 种子 + 上位授权绑定。接入指南 `docs/device-handler-template.md`（穿梭车/机械手/打包机留模板） |
| M10 智能任务编排 | ✅ 完成 | **一对多任务链**（`wcs_task_chain` 独立表 + materialize-on-bind：段定义存链行 JSON，编排器选中设备后才物化子任务，`app_id` 保持 NOT NULL 不破幂等/对账/索引）+ **多对多候选组选机**（idle_first 空闲优先→最少积压→全忙等待 / round_robin 无状态取模）+ **可配派工策略**（priority/fifo 按应用路由）+ **队列管理**（`/task/pause-app|resume-app`(work_mode 停新派工在飞跑完)、`/task/reprioritize`、`/task/retry`）+ 开放接口 `/open/chain/dispatch`（**每段全量绑定授权**，chain_no 幂等）/`/open/chain/query`（按下发者收敛）；管理端 `/chain/{list,detail,cancel}`（级联取消）。并发立法：引擎唯一推进者/编排器只插子行改链行消费已提交终态/管理 API 全走 version；物化前先赢链行版本（claim 模式），段严格串行无 hold-and-wait 不死锁。V11 迁移 |
| M11 设备维护与统计 | ✅ 完成 | **失败处理策略**：`failure_policy=auto_retry` 时 `TaskRecoveryScheduler` 退避（`cayleywcs.task.retry-backoff-ms`）后自动重派独立任务（`resetForRetry` 原语，乐观锁保护）；链段重试由编排器在链级挂钩（同一原语，chain_id 判空天然分工），耗尽才链 failed 转人工。**在飞停滞检测**：`task_stale_seconds`>0 时 last_update_time 停滞超时 → 判 failed+报警(-4)——引擎每步 save 都 touch，停滞时长即无进展时长（连接断开引擎不推进，天然覆盖设备失联）。**统计三接口**（`stats` 包，Java 聚合）+ **报文留存清理**（`MessageLogCleaner` 每日 cron 按 `message-log-retention-days` 清理，connection_log 供统计重放不清）。维护语义（work_mode 停新派工/在飞跑完/选机排除）已在 M10 落地，本期补 failure/stale 维度。V12 迁移 |
| M12 高可用·单机自愈 | ✅ 完成 | **期望态持久化**：`ConnectionManager.open` 入口先落 `desired_state=open`（用户意图非执行结果，建连失败只影响实际态+报警）、`close` 先落 `closed` 防被自动拉起；`ApplicationService.updateDesiredState` 只更该列。**启动/周期恢复**：`ConnectionRecoveryRunner`（ApplicationRunner 后台线程启动恢复 + `@Scheduled` 对账，扫 `desired=open && !isOpen` 逐个 try-open，槽满报警不阻断）。**看门狗 rearm（F4）**：FAILED+desired=open 距上次失败超 `reconnect-rearm-cooldown-ms`(默认60s) → retryCount 清零回 DISCONNECTED 重入重连（保连接实例与轮询 worker，续跑靠 M11 再收养）；FAILED+desired≠open → 释放槽位（防 FAILED 永久占槽）。**重入收尾**：建连成功防御写 `cmd_ResetFault=0`（清崩溃遗留复位脉冲）。职责边界：恢复器只处理**不在连接表**的应用，看门狗只处理表内 FAILED，以 isOpen 互斥不重复建连。双机热备设计见 `docs/ha-active-standby-design.md`（仅文档：Redis 租约+fencing/appId 分片/接管流程/脑裂防护）。V13 迁移 |
| M13 系统对接增强 | ✅ 完成 | **Webhook 主动回调**（免轮询）：游标 outbox（`WebhookScanner` keyset 游标扫 task/alarm/chain 终态→投递记录；`WebhookDeliveryWorker` HMAC 签名 POST+指数退避+SSRF 防护），管理端 `/webhook/**` CRUD。**签名 v2**（`AppKeySigner.signV2` body 纳签闭合篡改边界，`min_sign_version` 按应用灰度强制）。**OpenAPI 分组**（open 组仅 `/open/**`）+ 第三方对接文档 `docs/open-api-guide.md`（AppKey 申请→v1/v2 签名→dispatch→webhook 验签→对账算法→错误码）。ERP/其他厂商系统对接 = 注册 upstream 应用 + 此文档。V14 迁移 |
| M14 前端管理补齐 | ✅ 完成 | 6 个管理页随后端能力落地：**任务队列**(`TaskView` 任务/链两 tab、取消/重试/改优先级/暂停恢复派工、链段下钻)、**报警中心**(`AlarmView` 活动/历史、确认/清除/故障复位)、**运行统计**(`StatsView` 汇总卡+设备利用率条+报警TOP+失败故障码TOP，设备/时间窗过滤)、**故障码**(`FaultCodeView` 按协议维护)、**Webhook**(`WebhookView` CRUD+事件勾选+作用域+投递记录排障)、**数据字典**(`DictView` 类型/项两级 CRUD)；应用管理加 **device_type** 选择（联动 M8/M9 设备类型 SPI）；实时看板增强设备类型/工作模式(维护/暂停)标注 + 进行中任务链面板。`api/wcs.ts` 扩展全部接口封装、路由/菜单补齐。`npm run build` + `vue-tsc --noEmit` 均通过；**活后端端到端冒烟 27/27**（Postgres17+Redis 容器，Flyway V1–V14 干净迁移→登录→开连接→输送线仿真派工执行至 completed→webhook 订阅+投递记录 outbox→注故障报警联动+ack→统计出数），冒烟发现并修复 2 处前端状态字面量错配：报警确认态后端为 `ack`（非 `acked`）、webhook 投递成功态后端为 `ok`（非 `delivered/success`） |
| M13 对抗审查加固 | ✅ 完成 | 4 镜头审查后修复：① **游标全精度化**——`watermark_millis` 毫秒截断致亚毫秒行反复重扫，改 `watermark_time timestamp(6)` 全精度；② **滞后窗口（P2 丢事件）**——事务「先取 now 后延迟提交」的低 lut 行会越过游标被永久跳过，改为只处理 `lut<=now-lag`(默认2s)，行「稳定」lag 后才投递；③ **游标起点=订阅时刻（P2 洪泛）**——新 webhook 从 `create_time` 起投递，不回放全部历史（顺带消除测试跨类串扰）；④ **v2 body 有界读（P1 OOM）**——签名校验读 body 加 `max-body-bytes`(默认1MB)上限，超限拒；⑤ v2 摘要改**原始字节** SHA256（非 UTF-8 转换往返）；⑥ keyset (last_update_time,id) 索引；⑦ 链旁路下发(upstream=0)广播给订阅者；⑧ SSRF 纵深 `block-private-hosts`（opt-in 封私网/环回）；⑨ @Scheduled 加 initialDelay 防启动首拍串扰 |
| M12 对抗审查加固 | ✅ 完成 | 4 镜头审查后修复：① **worker 不打回 FAILED（P1）**——`pollOnce` 只监控 RUNNING/CONNECTED，否则 FAILED 连接被 worker 每轮打回 DISCONNECTED、`attemptReconnect` 耗尽路径 `markFailed(now)` 刷新 `lastFailedAt`，rearm 冷却永久饿死（F4 修复在生产默认配置下失效）；② **期望态列丢失更新（P2）**——`work_mode/dispatch_strategy/failure_policy/task_stale_seconds/desired_state` 标注 `@TableField(updateStrategy=NEVER)`，普通 `update()` 全行写永不覆盖专用接口刚落的值（wcs_application 无 @Version，根治 M8/M10 同类问题）；③ **双重 slots.release（P2）**——看门狗 release 分支改原子条件移除 `remove(appId,mc)`，防与用户 close 双释放击穿槽上限；④ **并发 open 泄槽**——`opening` 集合串行化同一 appId 建连；⑤ 期望态未知（DB 抖动）时 rearm 判定**既不 rearm 也不 release**（不在信息缺失时不可逆拆除）；⑥ open 建连成功后复核 desired，防 close 竞态下已请求关闭的连接却 RUNNING 上线 |
| M11 对抗审查加固 | ✅ 完成 | 4 镜头×2 反驳者审查（确认 2×P1+4×P2+若干 P3）后修复：① **再收养（P1）**——停滞判 failed 期间设备自主完成停 `status_Task=2` 等确认，重派任务 `doCheck` 按设备任务号匹配直接续推（st=2 补发完成确认按完成收尾，st=1 续等），闭合「整台设备队列永久静默死锁」；配套：`clearCommAlarms` 只清 -1（-2/-3/-4 任务侧报警不随连接恢复清除）、doCheck 长期不就绪有界计数后 raise 运维报警、`doConfirmComplete` 加占位、`doClear` 清零前补发确认（崩溃兜底）；② **链段重派占位（P1，M10 同构）**——`tryAutoRetrySegment` 的 resetForRetry 纳入链行版本事务，防已取消链复活孤儿子任务；③ 恢复扫描**耗尽行 SQL 下推**（P2）——冻结 lu 的耗尽任务不入扫描窗，消灭永久饥饿；④ 统计加固（P2×3）：utilization 回溯有界防全表装载、alarmTop 显式排序（PG 不保证插入序）、message_log **分批删除**+V12 补 create_time 索引；⑤ 链段重试对 DB 抖动只跳过本轮不判链终态；测试消偶发（avgExec 固定基准时刻、清理 cron 测试期永不触发） |
| M10 对抗审查加固 | ✅ 完成 | 5 镜头×2 反驳者审查（确认 2×P1+7×P2+若干 P3）后修复：① **物化占位+子行插入同事务**（TransactionTemplate，P1）——否则 /chain/cancel 可在两次 auto-commit 间穿越，已取消链的孤儿子任务照常驱动设备；② **dispatchChain 去 @Transactional**（P1）——PG 上唯一键冲突把事务标记 aborted(25P02)，catch 后兜底重查必然失败（H2 语句级回滚掩盖）；③ `(chain_id,seq_no)` 升级**唯一索引** + 物化 catch DuplicateKey 良性放弃（双编排线程/多实例双物化兜底）；④ 激活超时基准改**上一段完成时间**（原 last_update_time 把上段执行耗时误算进等待）；⑤ 单任务幂等查询加 `chain_id IS NULL`（防误命中撞名链子任务）；⑥ findApp 只容错 NOT_FOUND（DB 抖动不再 fail-open 绕过暂停闸）；⑦ `update()` 缺省沿用 work_mode/dispatch_strategy（防全行写覆盖暂停状态）；⑧ chainNo 长度/选机策略码校验、候选清洗、/task/create 清洗链字段、终态先落库再报警、对账水位线协议文档修正（取最后一行 last_update_time 防 limit 截断漏行） |
| M9 对抗审查加固 | ✅ 完成 | 4 镜头×2 反驳者审查后修复 15 项确认发现：① **claim 版本还原（P1）**——MyBatis-Plus 乐观锁拦截器 updateById 无论命中几行都把实体 version 就地 +1，claim 失败若不还原会令引擎兜底 save 复活已取消任务；现失败时还原 version（同时闭合 M8 stacker 同源缺陷）；② payload 必填参数缺失/非法 → 判失败不用垃圾值命令设备；③ 自动门过渡态不计死等（慢门不误杀）、电梯到层门不开有界等待；④ **故障复位回空闲**——cmd_ResetFault 中止当前任务，防故障失败任务把设备永久卡非空闲阻塞后续；⑤ 仿真 `reset` 原地复位保持实例身份（修 SimAdapter/OPC UA 引用脱钩）；⑥ 缺 status_ErrorCode 点位跳过故障闸不空转；⑦ V10 补上位授权绑定、子查询加 tenant 过滤、注释订正 |

测试：`cd backend && mvn test`（H2，无需外部依赖）。本轮全套 **119 项：0 失败、0 错误、1 跳过**（真实 PG 可选测试另行运行）（schema/连接池/仿真端到端/握手/报警联动/对账/绑定授权/架构约束 + M8/M9 全部加固回归 + M10：链端到端(conveyor→lift 全自动)/chain_no 幂等/崩溃重放/级联取消/每段越权/下发者收敛/fifo·priority·idle_first·round_robin 策略/暂停恢复/重试改优先级）。

> 绑定授权经两轮多智能体对抗式评审加固：`create()`/`update()` 复用或校验软删行避免撞唯一索引(返回干净 CONFLICT)、`grant()` 与 **dispatch 越权判定 `isAllowed`** 均按 `tenant_id` 隔离(写/读两侧一致，杜绝跨租户放行)、`grant()` 声明式收敛 `scope`、appkey 关闭时 dispatch 记 WARN、前端校正已删上位选择、禁止自绑定。

## 安全：下发越权防护（应用绑定授权）

`/open/task/dispatch` 经 `AppKeyAuthFilter` 验签得到**调用方真实身份** `ATTR_APP_ID`(证明)，与 body 中的 `appId`(声明) 一起查 `wcs_app_binding`：仅当存在**启用中的绑定**(上位侧应用→下位侧应用) 才放行，否则 `FORBIDDEN`。

- **模型**：绑定为「上位侧 `upstream_app_id` → 下位侧 `downstream_app_id`」。**不按应用 `direction` 锁方向**——任意应用都可出现在上位侧或下位侧（含「下位→下位」），仅**禁止自绑定**(`upstream==downstream`)。应用的 `direction` 仅作提示。
- **按应用授权（推荐用法）**：种子 `WMS-MAIN`(`ak_wms_main`)用自身 AppKey 调 `/open/**`；在「绑定授权」页**选定一个上位侧应用 → 勾选它可指挥的下位侧应用（多选，已排除自身）→ 保存**即整批授权。该应用只能指挥被勾选的应用。
  - `POST /binding/granted {upstreamAppId}` → 该上位侧已授权的下位侧应用 id 列表（回显勾选）。
  - `POST /binding/grant {upstreamAppId, downstreamAppIds[], scope}` → 声明式整批授权（缺补、撤选软删、自指忽略，幂等）。
- 维护入口：前端「应用绑定授权」页 或 `/binding/{list,all,create,update,delete}`；自绑定在 `create`/`grant` 被拒/忽略。
- 边界：这是 **WCS API 层访问控制**，拦截「经 WCS 越权指挥别的应用/设备」；它**不替代**设备级保护(网络分段把 WCS 做唯一闸口 + 设备侧 OPC UA 安全策略/证书 + 写保护/钥匙开关 + 硬线急停)。
- **body 纳签已闭合（M13 签名 v2）**：`X-Sign-Version: 2` 时 canonical 追加 `sha256Hex(body)`，坐标/任务类型篡改即签名失效；应用 `min_sign_version=2` 可强制（灰度共存，`CachedBodyHttpServletRequest` 让过滤器与控制器共读 body）。v1 仍兼容。
- **对账接口越权已补齐（M8）**：`/open/task/query`、`/open/alarm/query`、`/open/snapshot` 也经 `OpenAccessGuard` 校验——查自己放行，否则须存在启用中的绑定；`appId` 缺省时不再全量返回，收敛为「自身+已授权下位应用」集合；`snapshot` 的 `appId` 必填。

## 前端本地运行

```powershell
cd frontend
npm install
npm run dev          # http://127.0.0.1:5184 ，/api 与 /ws 代理到后端 20021
```

页面（`src/views/`，菜单顺序）：
- **实时看板** [DashboardView](frontend/src/views/DashboardView.vue)（JWT 兑换短期 WS 票据；完整帧/seq/过期检查、断线退避重连；连接和报警在健康 WS 期间每 30 秒 REST 校准，失联时每 2 秒单飞轮询；链与实际应用点位每 2 秒刷新；设备类型/工作模式、进行中任务链与连接槽；中文点位复选配置、每行两项、固定区域内部滚动）
- **任务队列** `TaskView`（任务/任务链两 tab；任务取消/重试/改优先级、按应用暂停/恢复派工；任务链段明细下钻、级联取消）
- **报警中心** `AlarmView`（活动/历史切换、确认/清除、按设备下发故障复位脉冲）
- **运行统计** `StatsView`（任务量/完成率/平均时长汇总卡、设备利用率、报警 TOP、失败故障码 TOP；设备+时间窗过滤）
- **协议管理** `ProtocolView`（所属应用 → 协议 → 点位与版本；历史模板只读、复制时指定目标应用）
- **故障码** `FaultCodeView`（按协议维护故障码 CRUD）
- **应用管理** `ApplicationView`（平台 AppID、AppKey 生成/重置、所属协议与点位、本应用固定发布版本、设备类型及应用适配器）
- **授权绑定** `BindingGrantView`（选定上位侧应用 → 勾选其可指挥的下位侧应用，批量授权）
- **绑定明细** `BindingDetailView`（全部绑定列表 + 手动新建/编辑/删除 + 按应用搜索）
- **连接监控** [`ConnectionView`](frontend/src/views/ConnectionView.vue)（全部设备应用、建连/断开/重连、按权限的“状态设置”；AGV 连续监测已合并，旧路由跳转此页）
- **采集配置与历史** [`AgvCollectionPanel`](frontend/src/components/AgvCollectionPanel.vue)（连接监控内的面板：车辆接入、三传感器应用注册、默认档案、供电记录、通信与存储健康、分传感器历史曲线；接口见 [`agv.ts`](frontend/src/api/agv.ts) 和 [`deviceState.ts`](frontend/src/api/deviceState.ts)）
- **Webhook** `WebhookView`（订阅 CRUD + 事件类型勾选 + 作用域应用 + 投递记录排障）
- **数据字典** `DictView`（字典类型/字典项两级 CRUD）

绑定不锁 direction、禁自绑定。构建校验：`npm run build`（vite）+ `npm run type-check`（vue-tsc `--noEmit`）均通过。

## WMS 对账闭环（HTTP 下发、Webhook 与 REST 对账）

当前 CayleyWMS 实现使用应用凭据获取 access_token 并通过公共 JSON 封装进行 HTTP 下发、HMAC Webhook 终态回调和定时 REST 对账；回调与对账共用 WMS 的终态 claim 与库存内核。WCS 的 WS 监控是可用的遥测能力，当前 WMS 设备任务闭环不依赖 WS 订阅。既有巷道联调与当前多设备关联边界见 [WMS 对接说明](../cayley_wms/docs/WCS对接说明.md#8-使用现有基础数据联调)。

- **WS 帧**（`/ws/monitor`，每秒）：`{type, seq, ts, connections:[{appId,appCode,state,latest{...}}], slots, alarms:[...]}`。`seq` 单调递增，WMS 据此发现断号。
- **增量对账**（`/open/**`，AppKey 鉴权）：
  - `POST /open/task/query  {appId, sinceMillis, limit}` → `{serverTimeMillis, count, rows}`（last_update_time ≥ sinceMillis 的任务）
  - `POST /open/alarm/query {appId, sinceMillis, limit}` → 同上（报警）
  - `POST /open/snapshot    {appId}` → `{serverTimeMillis, connection, alarms, tasks}`（断线一把全量重同步）

对需要接入 WS 遥测的上位系统，可在稳态读取 WS；发现断号或重连时用 `/open/snapshot` 全量同步，再增量拉取并按 id 去重。关键任务状态仍以 REST 返回的数据库事实为准。

> **水位线取值（重要）**：下次 `sinceMillis` 应取**本次已处理最后一行的 `last_update_time`**，而非响应的 `serverTimeMillis`——当 `count == limit`（结果被截断）时用 serverTimeMillis 会永久漏掉截断行。查询按 `last_update_time >= since` 含等值重拉，配合按 id 去重无重复风险。`/open/chain/query` 同此协议。
