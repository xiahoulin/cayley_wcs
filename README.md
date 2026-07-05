# CayleyWCS — 仓库控制系统

凯利智飞 WCS（Warehouse Control System），定位于 **WMS（上位业务）↔ 设备（下位 PLC/堆垛机/输送线等）** 之间，负责多协议设备通讯、连接治理、任务调度与实时监控。架构对齐 [CayleyWMS](../cayley_wms/)。

> 设计与里程碑详见计划：`C:\Users\<user>\.claude\plans\wcs-1-java-spring-wms-opc-mossy-hinton.md`（或团队内同步副本）。

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
| `connection`（M2/M12） | 连接治理：线程池 + 信号量 + 60s 超时回收 + 看门狗重连；**单机自愈**（M12）：`open/close` 落 `desired_state`（用户意图）、看门狗 rearm（FAILED+desired=open 冷却后重入重连 / desired≠open 释放槽）、`ConnectionRecoveryRunner`（启动+周期对账把 desired=open 未连应用拉起）、建连成功防御复位 `cmd_ResetFault=0` |
| `adapter`（M2） | 协议适配器 SPI（Adapter/Factory/Strategy/Template）+ 各协议实现 |
| `device`（M8/M9） | **设备类型 SPI**：`DeviceHandler`/`AbstractDeviceHandler`（advanceGuarded 故障闸+传输异常放弃、claim 占位、bumpStall 死等检测、payload 取值）/`DeviceHandlerRegistry`，按 `wcs_application.device_type` 路由（未知类型：保存时拒绝 + 运行期报警不静默）；实现：`task/StackerHandshakeStateMachine`(stacker/palletizer)、`device/conveyor`(输送线)、`device/lift`(电梯)、`device/autodoor`(自动门)。新增设备类型零改引擎，接入见 `docs/device-handler-template.md` |
| `task`（M4/M8） | 任务调度引擎（tick 只扇出 worker 池 + 设备级单飞 + `wcs_task.version` 乐观锁）+ 堆垛机三段握手状态机（stacker/palletizer 的 DeviceHandler，含确认步重入容错）+ `TaskStatus` 状态常量 |
| `task.orchestrate`（M10） | **任务链编排**：`ChainService`（chain_no 幂等下发/级联取消）+ `TaskOrchestrator`（无内存态，每 tick 从 DB 派生；段严格串行；物化前先赢链行版本防 cancel 竞态；确定性 task_no=chainNo-seq 崩溃重放幂等；候选组激活可配超时报警 -3）+ **双策略 SPI**：选机 `DeviceSelectionStrategy`(idle_first/round_robin) 与派工 `TaskSelectionStrategy`(priority/fifo，按 `wcs_application.dispatch_strategy` 路由；在飞优先是正确性约束非策略) |
| `monitor`/`alarm`（M5） | WebSocket 实时推送 + 报警生命周期 |
| `stats`（M11） | 运行统计（Java 聚合保 H2/PG 双兼容）：`/stats/task-summary`（任务量/终态分布/完成率/平均执行时长/失败码 TOP）、`/stats/device-utilization`（重放 `wcs_connection_log` 状态迁移算 RUNNING 占比）、`/stats/alarm-top` |
| `webhook`（M13） | **主动回调（游标 outbox，崩溃安全）**：`WebhookScanner`（每 webhook×每源 keyset 游标 (last_update_time,id) 扫 task/alarm/chain 终态→分类事件→scope 匹配→插投递，游标推进到已处理最后一行）+ `WebhookDeliveryWorker`（HMAC 签名头 ts+nonce+sha256(body) POST，指数退避超 max-attempts 置 failed，SSRF：scheme+host allowlist+禁重定向）+ `WebhookService/Controller` CRUD/投递查询 |
| `simulator`（M3/M9） | 堆垛机 OPC UA 仿真服务端（profile `simulator`）；**通用内存仿真** `DeviceSim`/`AbstractDeviceSim`/`DeviceSimRegistry`/`DeviceSimFactory`（按 device_type 建模）+ Stacker/Conveyor/Lift/AutoDoor 四种设备态；`SimulatorController` 故障注入/复位/快照按类型透传 |

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
| `V9__device_type_and_task_version.sql` | **M8**：`wcs_application.device_type`（默认 stacker；字典 `device_type` 十项，agv/shuttle 预留电量语义）+ `wcs_task.version` 乐观锁列；存量订正（PALLET01/PALLET_SIM→palletizer，upstream 应用→none）在本迁移内完成，不改历史文件 |
| `V10__seed_conveyor_lift_door.sql` | **M9**：输送线/电梯/自动门三套 `sim` 协议 + 点位 + 故障码 + 仿真应用（`CONVEYOR_SIM`/`LIFT_SIM`/`DOOR_SIM`，各带 `device_type`）+ WMS-MAIN 上位授权 |
| `V11__task_chain.sql` | **M10**：`wcs_task_chain` 链表（chain_no 幂等唯一索引 + version 乐观锁 + upstream_app_id 下发者 + segments JSON）；`wcs_task` 加 `chain_id/seq_no/retry_count/max_retry` + `(chain_id,seq_no)` 唯一索引；`wcs_application` 加 `work_mode`（normal/paused/maintenance）/`dispatch_strategy`（priority/fifo）；三组字典 |
| `V12__failure_policy.sql` | **M11**：`wcs_application` 加 `failure_policy`（manual 转人工/auto_retry 自动重试，上限=任务 `max_retry`>0 ? 任务级 : 应用 `max_retry`——该列自此承担任务重试默认上限语义）+ `task_stale_seconds`（在飞停滞超时，0=关）；字典 + `task_type=charge` 充电预留 + message_log 时间索引 |
| `V13__desired_state.sql` | **M12**：`wcs_application` 加 `desired_state`（open/closed，用户连接意图，重启后 `ConnectionRecoveryRunner` 据此自动恢复）+ 字典 |
| `V14__webhook_and_sign_v2.sql` | **M13**：`wcs_webhook`（订阅：url/secret/event_types/scope_app_ids）+ `wcs_webhook_cursor`（每源独立 keyset 游标）+ `wcs_webhook_delivery`（投递记录，唯一键 webhook×event×biz 幂等）+ `wcs_application.min_sign_version`（签名 v2 灰度强制） |

## 无硬件 OPC UA 联调（Milo 仿真服务端）

```powershell
docker compose up -d postgres redis
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local,simulator"   # 启动 Milo OPC UA Server：opc.tcp://127.0.0.1:4840/cayleywcs/stacker
```

然后对应用 **码垛机OPCUA仿真(`PALLET_SIM`)** 建连（前端连接监控页或 `POST /connection/open`）——WCS 经 PLC4X 走真·OPC UA 连到仿真器，可读状态/写命令/跑三段握手。注入故障：`POST /simulator/inject-fault {"appId":999001,"code":8}`。

> **联调观测（`docker logs -f cayleywcs-backend` / Docker Desktop 可见）**：Milo 服务端 `StackerNamespace` 的 setValue 过滤器（`simulator` profile 生效）在收到 WCS 写入命令区 `cmd_*` 字段时打 INFO：
> - **逐字段明细**：`[OPC UA仿真] 收到指令写入 <字段> = <值>`（跳过每秒心跳 `WCS_Heart`）；
> - **完整指令数据**：写入 `cmd_ConfirmTask=1`（确认执行、参数已就位）时汇总打印 `[OPC UA仿真] ★收到完整指令★ 任务号=.. 类型=3(移库) 取货[排.. 列.. 层..] 放货[排.. 列.. 层..] 货口=..`。
>
> 下发一个任务即可看到。看当前寄存器值用 `POST /simulator/snapshot {"appId":999001,"code":0}`（code 必填）；看历史逐字段用 `POST /audit/message-log/list {"appId":<设备id>,"limit":40}`。

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

测试：`cd backend && mvn test`（H2，无需外部依赖）。当前 **106 个用例全绿**（schema/连接池/仿真端到端/握手/报警联动/对账/绑定授权/架构约束 + M8/M9 全部加固回归 + M10：链端到端(conveyor→lift 全自动)/chain_no 幂等/崩溃重放/级联取消/每段越权/下发者收敛/fifo·priority·idle_first·round_robin 策略/暂停恢复/重试改优先级）。

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
- **实时看板** `DashboardView`（WebSocket 设备状态/报警，轮询兜底；设备类型/工作模式标注、进行中任务链、连接槽/进行中链统计卡）
- **任务队列** `TaskView`（任务/任务链两 tab；任务取消/重试/改优先级、按应用暂停/恢复派工；任务链段明细下钻、级联取消）
- **报警中心** `AlarmView`（活动/历史切换、确认/清除、按设备下发故障复位脉冲）
- **运行统计** `StatsView`（任务量/完成率/平均时长汇总卡、设备利用率、报警 TOP、失败故障码 TOP；设备+时间窗过滤）
- **协议管理** `ProtocolView`（协议 + 点位）
- **故障码** `FaultCodeView`（按协议维护故障码 CRUD）
- **应用管理** `ApplicationView`（AppKey 生成/重置、设备类型选择）
- **授权绑定** `BindingGrantView`（选定上位侧应用 → 勾选其可指挥的下位侧应用，批量授权）
- **绑定明细** `BindingDetailView`（全部绑定列表 + 手动新建/编辑/删除 + 按应用搜索）
- **连接监控** `ConnectionView`（建连/断开/重连、60s 超时回收可见）
- **Webhook** `WebhookView`（订阅 CRUD + 事件类型勾选 + 作用域应用 + 投递记录排障）
- **数据字典** `DictView`（字典类型/字典项两级 CRUD）

绑定不锁 direction、禁自绑定。构建校验：`npm run build`（vite）+ `npm run type-check`（vue-tsc `--noEmit`）均通过。

## WMS 对账闭环（WS 提示 + REST 权威）

WMS↔WCS 采用「命令走 REST、遥测走 WS、关键事件 REST 对账」。WS 推送不保证不丢，故关键事件以 DB 为真相用 REST 拉权威态。

- **WS 帧**（`/ws/monitor`，每秒）：`{type, seq, ts, connections:[{appId,appCode,state,latest{...}}], slots, alarms:[...]}`。`seq` 单调递增，WMS 据此发现断号。
- **增量对账**（`/open/**`，AppKey 鉴权）：
  - `POST /open/task/query  {appId, sinceMillis, limit}` → `{serverTimeMillis, count, rows}`（last_update_time ≥ sinceMillis 的任务）
  - `POST /open/alarm/query {appId, sinceMillis, limit}` → 同上（报警）
  - `POST /open/snapshot    {appId}` → `{serverTimeMillis, connection, alarms, tasks}`（断线一把全量重同步）

WMS 算法：稳态读 WS（`seq` 连续即可）；发现断号/重连 → `/open/snapshot` 全量重同步 → 之后增量拉取，**按 id 去重**。这样既享受 WS 实时、又保证任务完成/报警不丢（最终一致）。

> **水位线取值（重要）**：下次 `sinceMillis` 应取**本次已处理最后一行的 `last_update_time`**，而非响应的 `serverTimeMillis`——当 `count == limit`（结果被截断）时用 serverTimeMillis 会永久漏掉截断行。查询按 `last_update_time >= since` 含等值重拉，配合按 id 去重无重复风险。`/open/chain/query` 同此协议。
