# CayleyWCS — 仓库控制系统

凯利智飞 WCS（Warehouse Control System），定位于 **WMS（上位业务）↔ 设备（下位 PLC/堆垛机/输送线等）** 之间，负责多协议设备通讯、连接治理、任务调度与实时监控。架构对齐 [CayleyWMS](../cayley_wms/)。

> 设计与里程碑详见计划：`C:\Users\<user>\.claude\plans\wcs-1-java-spring-wms-opc-mossy-hinton.md`（或团队内同步副本）。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 25 · Spring Boot 4.0.6 · MyBatis-Plus 3.5.16 · PostgreSQL 17 · Redis 7.4 · Flyway · springdoc |
| 工业驱动 | Apache PLC4X（OPC UA/Modbus/S7）· Paho（MQTT）· WebClient（HTTP）· Netty（TCP）· Eclipse Milo（仿真器）｜ M2/M3 引入 |
| 前端 | Vue 3 · Vite · Vuetify 3 · VXE Table ｜ M6 引入 |

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
| `common.api` | 统一响应 `ApiResponse` / 分页 `PageData`·`PageSearch`·`PageSupport` / `IdRequest` |
| `common.exception` | 全局异常处理 `GlobalExceptionHandler` + `WcsException` + `ErrorCode` |
| `common.security` | `CurrentUser` |
| `config` | 安全/CORS、OpenAPI（后续：连接线程池、WebSocket） |
| `auth` | 管理端 JWT 登录链路；预留 APISIX/Keycloak flag |
| `system` | 当前用户、用户/角色（登录用） |
| `health` | 健康检查 `/health` |
| `dict`（M1） | 数据字典：协议类型等枚举单一事实源 |
| `protocol`（M1） | 协议表 + 协议点位/字段映射 |
| `application`（M1） | 应用信息表 + 双向 AppKey 鉴权 |
| `binding` | 应用绑定授权：`wcs_app_binding`(上位侧→下位侧，不锁 direction、禁自绑定) CRUD + **按应用整批授权**(`grant`/`grantedDownstreamIds`) + 下发越权判定(`isAllowed`/`assertAllowed`) |
| `faultcode`（M1） | 故障码表 + 解析（未维护走统一兜底） |
| `connection`（M2） | 连接治理：线程池 + 信号量 + 60s 超时回收 + 看门狗重连 |
| `adapter`（M2） | 协议适配器 SPI（Adapter/Factory/Strategy/Template）+ 各协议实现 |
| `task`（M4） | 任务调度引擎 + 堆垛机握手状态机 |
| `monitor`/`alarm`（M5） | WebSocket 实时推送 + 报警生命周期 |
| `simulator`（M3） | 堆垛机 OPC UA 仿真服务端（profile `simulator`） |

## 文档

- **《礁盘工业WCS使用功能说明书》**：`docs/礁盘工业WCS使用功能说明书.docx`（参考 WMS 说明书结构，按代码梳理全部模块/接口/数据表 + 业务逻辑图/握手时序/连接状态机）。
- 生成器（python-docx + PIL，同 WMS 套路）：`docs/manual_assets/build_wcs_manual.py`，运行即重建文档与图（`docs/manual_assets/rendered/*.png`）。

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

## 无硬件 OPC UA 联调（Milo 仿真服务端）

```powershell
docker compose up -d postgres redis
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local,simulator"   # 启动 Milo OPC UA Server：opc.tcp://127.0.0.1:4840/cayleywcs/stacker
```

然后对应用 **码垛机OPCUA仿真(`PALLET_SIM`)** 建连（前端连接监控页或 `POST /connection/open`）——WCS 经 PLC4X 走真·OPC UA 连到仿真器，可读状态/写命令/跑三段握手。注入故障：`POST /simulator/inject-fault {"appId":999001,"code":8}`。

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
| M6 Vue 前端 | ✅ MVP 完成 | Vue3+Vite+TS 精简脚手架(reefplex 风)：登录 + **协议管理/应用管理(AppKey)/授权绑定/绑定明细/连接监控/实时看板(WebSocket)** 6 页；`npm run build` 通过。其余页(字典/故障码/任务/报警中心)二期补 |
| M7 测试/文档 | ✅ 基本完成 | 架构约束+接口契约测试(全 POST+JSON/Service 接口+impl/Mapper)、前端 Dockerfile+nginx 模板+compose frontend 服务、父级 `cayley_project` 注册表登记。allinone 单容器镜像列为可选项 |

测试：`cd backend && mvn test`（H2，无需外部依赖）。当前 **26 个用例全绿**（schema/连接池/仿真端到端/握手/报警联动/对账/绑定授权/架构约束）。

> 绑定授权经两轮多智能体对抗式评审加固：`create()`/`update()` 复用或校验软删行避免撞唯一索引(返回干净 CONFLICT)、`grant()` 与 **dispatch 越权判定 `isAllowed`** 均按 `tenant_id` 隔离(写/读两侧一致，杜绝跨租户放行)、`grant()` 声明式收敛 `scope`、appkey 关闭时 dispatch 记 WARN、前端校正已删上位选择、禁止自绑定。

## 安全：下发越权防护（应用绑定授权）

`/open/task/dispatch` 经 `AppKeyAuthFilter` 验签得到**调用方真实身份** `ATTR_APP_ID`(证明)，与 body 中的 `appId`(声明) 一起查 `wcs_app_binding`：仅当存在**启用中的绑定**(上位侧应用→下位侧应用) 才放行，否则 `FORBIDDEN`。

- **模型**：绑定为「上位侧 `upstream_app_id` → 下位侧 `downstream_app_id`」。**不按应用 `direction` 锁方向**——任意应用都可出现在上位侧或下位侧（含「下位→下位」），仅**禁止自绑定**(`upstream==downstream`)。应用的 `direction` 仅作提示。
- **按应用授权（推荐用法）**：种子 `WMS-MAIN`(`ak_wms_main`)用自身 AppKey 调 `/open/**`；在「绑定授权」页**选定一个上位侧应用 → 勾选它可指挥的下位侧应用（多选，已排除自身）→ 保存**即整批授权。该应用只能指挥被勾选的应用。
  - `POST /binding/granted {upstreamAppId}` → 该上位侧已授权的下位侧应用 id 列表（回显勾选）。
  - `POST /binding/grant {upstreamAppId, downstreamAppIds[], scope}` → 声明式整批授权（缺补、撤选软删、自指忽略，幂等）。
- 维护入口：前端「应用绑定授权」页 或 `/binding/{list,all,create,update,delete}`；自绑定在 `create`/`grant` 被拒/忽略。
- 边界：这是 **WCS API 层访问控制**，拦截「经 WCS 越权指挥别的应用/设备」；它**不替代**设备级保护(网络分段把 WCS 做唯一闸口 + 设备侧 OPC UA 安全策略/证书 + 写保护/钥匙开关 + 硬线急停)。
- body 暂未纳入签名(坐标/任务类型可被篡改)——彻底闭合需把 body 摘要并入 canonical，列为后续。

## 前端本地运行

```powershell
cd frontend
npm install
npm run dev          # http://127.0.0.1:5184 ，/api 与 /ws 代理到后端 20021
```

页面：实时看板（WebSocket 设备状态/报警）、协议管理（协议+点位）、应用管理（AppKey 生成/重置）、**授权绑定**（选定上位侧应用 → 勾选其可指挥的下位侧应用，批量授权）、**绑定明细**（全部绑定列表 + 手动新建/编辑/删除 + 按应用搜索）、连接监控（建连/断开/重连、60s 超时回收可见）。绑定不锁 direction、禁自绑定。

## WMS 对账闭环（WS 提示 + REST 权威）

WMS↔WCS 采用「命令走 REST、遥测走 WS、关键事件 REST 对账」。WS 推送不保证不丢，故关键事件以 DB 为真相用 REST 拉权威态。

- **WS 帧**（`/ws/monitor`，每秒）：`{type, seq, ts, connections:[{appId,appCode,state,latest{...}}], slots, alarms:[...]}`。`seq` 单调递增，WMS 据此发现断号。
- **增量对账**（`/open/**`，AppKey 鉴权）：
  - `POST /open/task/query  {appId, sinceMillis, limit}` → `{serverTimeMillis, count, rows}`（last_update_time ≥ sinceMillis 的任务）
  - `POST /open/alarm/query {appId, sinceMillis, limit}` → 同上（报警）
  - `POST /open/snapshot    {appId}` → `{serverTimeMillis, connection, alarms, tasks}`（断线一把全量重同步）

WMS 算法：稳态读 WS（`seq` 连续即可）；发现断号/重连 → `/open/snapshot` 全量重同步 → 之后增量 `query(sinceMillis=上次 serverTimeMillis)`，**按 id 去重**。这样既享受 WS 实时、又保证任务完成/报警不丢（最终一致）。
