# WCS 优化方案（审核修订版，工作区实施与验证完成）

日期：2026-09-29。任务：TASK-20260929-DED1BF09。阅读基线：本机工作区，Git HEAD `07f0cf8`。

本文件保留最初的方案审核依据与实施约束。以下「现状」「尚未实施」等语句描述 Git HEAD `07f0cf8` 的实施前基线；当前工作区已有未提交的业务代码、迁移、前端及运行参数改动。方案审核任务为 TASK-20260929-36E3B844，实施任务为 TASK-20260929-9D3E5790。

**审核结论：原版不能整体直接进入实施。第一阶段方向保留；C/D/E 必须补齐下列前置条件与验收后再批准。** 现有测试通过只说明已覆盖行为通过，不证明方案的所有等价性主张成立。

实施进度（2026-09-29）：A/B/C/D/E/F/G 已在工作区落地。A 的 H2 对照和真实 PG 并发 COUNT/分页快照通过；深页成本仍取决于部署数据。B 在隔离的 10 万行 PG 数据集下完成执行计划、写入成本和 V15 迁移失败重试验证；生产数据规模下需评估写放大。C 的 H2 口径、真实 PG 跨查询快照和 2 万行游标/连接占用测试通过；更大长窗口的堆与数据库负载未做生产容量测试。D 的真实握手访问控制、报警生命周期，以及浏览器断开后轮询并恢复 WS 均验证；12 秒无帧主动重连、无效帧及旧 REST 晚响应由实现约束覆盖，尚无独立浏览器自动化断言。E/F 定向测试及真实 OPC UA 仿真联调通过；G 的 JWT 保护指标端点测试通过。全套后端 H2 测试、前端构建与类型检查、PG/Redis 冒烟、集成和在线契约回归通过。不能将这些隔离环境结果直接外推为生产容量结论。

| 级别 | 原版遗漏 | 具体影响 | 本版处置 |
|---|---|---|---|
| P1 | D 未识别活动报警会导致当前监控整帧序列化失败 | 降低 REST 轮询后，更依赖 WS 的看板在报警发生时反而收不到连接/报警更新 | 已离线复现；把报警帧修复和端到端验证列为减少轮询的前置任务，未完成时保留原 REST 兜底 |
| P1 | D 未审查 WS 握手的访问控制 | `/ws/**` 被 `permitAll` 放行，监控握手允许任意 Origin，未携带 JWT 也可订阅全部连接和活动报警 | 先单独设计并验证 WS 授权及浏览器凭据传递；在解决前不能把 WS 当作可信的管理端主通道，不能降低 REST 兜底；安全修复不暗含在等价优化内 |
| P1 | E 未定义新增调度器后的默认调度器与绑定关系 | 新增专用池可能令 Boot 默认池退出自动配置，其余定时任务回退为单线程，长重连阻塞派工 | 明确保留 4 线程核心池、逐任务显式绑定、启动上下文断言和慢重连回归 |
| P2 | E 停机验收未覆盖投递循环吞掉中断的现状 | 第一条 HTTP 被中断后，循环仍发送停机时尚未开始的下一条；单次 shutdownNow 不足以停止一批投递 | 已离线复现；明确逐条停止检查、中断后的本轮退出、在途投递状态与资源销毁顺序 |
| P2 | C 的生产执行未明确多条统计 SQL 共用快照 | 任务在统计过程中重试，可出现 failed 数量与故障 TOP 不一致；报警最新消息与次数也可能不属同一批数据 | 明确单语句或请求内一致性快照，增加可控并发验收 |

上述定位针对原版 L75、L85、L65–69；修订后的详细约束见 C/D/E。A 已写明计数和取页使用同一快照，本版仅进一步明确实现方式，不将其重复计为审核缺陷。

## 1. 结论与范围

当前结构已具备设备 SPI、设备级单飞、任务乐观锁、串行任务链、连接自愈和 Webhook 扫描投递机制。主要优化机会在查询数据量、重复请求和慢 I/O 对后台调度的影响。保留现有分层，按模块渐进优化即可。

本次阅读覆盖任务引擎与握手、任务链、连接治理、适配器、分页、统计、报警、审计、Webhook、看板及相关测试；没有对每个文件做全量审计，也没有做生产容量测试。下文收益均是基于代码的判断，不是已测得的性能提升。

必须保留的行为：

| 边界 | 当前行为及实施约束 |
|---|---|
| 设备控制 | 三段握手及各设备状态机不变；堆垛机参数按坐标→货口→任务号→任务类型写入；执行确认和完成确认的顺序、复位脉冲宽度不变 |
| 并发保护 | 同设备单飞、设备启动/确认前 claim、version 冲突放弃本步、claim 失败恢复内存 version 不变 |
| 派工与编排 | 在飞任务优先；priority/fifo 排序及并列规则不变；暂停/维护只停新派工；链段串行、选机策略、链占位与子任务插入同事务不变 |
| 恢复 | manual/auto_retry、退避、次数上限、停滞判定、重入收尾与再收养规则不变 |
| 对外契约 | POST+JSON、ApiResponse、PageData 的 rows/totals、状态字面量、AppKey/HMAC 与授权集合不变；既有对账与 Webhook 语义不变 |

关键依据：[TaskEngine](../backend/src/main/java/com/cayleywcs/task/TaskEngine.java) L55–97、[AbstractDeviceHandler](../backend/src/main/java/com/cayleywcs/device/AbstractDeviceHandler.java) L91–109、[StackerHandshakeStateMachine](../backend/src/main/java/com/cayleywcs/task/StackerHandshakeStateMachine.java) L114–153、[TaskOrchestrator](../backend/src/main/java/com/cayleywcs/task/orchestrate/TaskOrchestrator.java) L198–240。

## 2. 建议清单

| 编号 | 优化项 | 收益 | 风险 | 建议阶段 |
|---|---|---|---|---|
| A | 大表列表改为数据库分页 | 每页仅传输和构造当页实体，降低历史数据增长带来的堆内存与 GC 开销 | 低；须保留排序、总数与筛选语义 | 第一阶段 |
| B | 按实际查询补充索引 | 降低派工选取、统计和日志查询的扫描/排序成本 | 低至中；需衡量索引写入成本与建索引锁影响 | 第一阶段，先测后加 |
| C | 统计改为聚合查询或有界读取 | 降低任务/报警全实体加载与分组的内存成本 | 中；请求内一致性快照、统计精度与排序需验证 | 第二阶段，补齐并发验收 |
| D | 看板请求去重、WS 健康检测与恢复 | 减少重复 HTTP 查询和并发轮询，改善断线后的数据恢复 | 减少轮询有 P1 前置阻断：活动报警帧当前失败 | 第二阶段，先修复并验证 WS 报警帧 |
| E | 慢 I/O 与核心定时任务隔离 | 避免慢回调、慢客户端占用公共调度线程 | 默认核心池丢失有 P1 风险，须明确调度绑定 | 第二阶段，先验证上下文与停机 |
| F | 连接内点位建立名称索引 | 减少每次点位读写的线性查找，简化重复代码 | 低；当前点位数少，收益有限 | 第一阶段，可选 |
| G | 增加低开销指标与异常可见性 | 提供优化基线，缩短定位慢查询、慢设备和回调积压的时间 | 低；避免高基数和日志洪泛 | 第一阶段起始项 |

### A. 数据库分页：优先处理任务、报警、任务链

**现状**：共有 7 处 `PageSupport.slice` 调用。任务、报警、链等入口先 `selectList` 全量加载，再在 Java 中 `subList`。即使请求 20 条，也会先构造全部历史实体。

依据：[TaskServiceImpl](../backend/src/main/java/com/cayleywcs/task/impl/TaskServiceImpl.java) L39–44、[AlarmServiceImpl](../backend/src/main/java/com/cayleywcs/alarm/impl/AlarmServiceImpl.java) L116–121、[ChainServiceImpl](../backend/src/main/java/com/cayleywcs/task/orchestrate/impl/ChainServiceImpl.java) L159–164、[PageSupport](../backend/src/main/java/com/cayleywcs/common/api/PageSupport.java)。

**改法**：先将这三个大表入口改成 Mapper 分页 SQL，复用相同条件做计数，保留 `id desc`、`is_valid=true`、现有入参和响应。计数与取页应在同一只读快照内完成，避免并发写入导致 totals 与 rows 不一致。也可采用分页插件，但不得替换现有乐观锁拦截器。

**快照实现**：优先在单条 SQL 中返回页数据与总数，且覆盖越界页无数据但 totals 非零的情况；使用两条 SQL 时，仅在这三个只读查询入口建立短时 `REPEATABLE_READ` 事务并验证代理生效。单独的 `readOnly=true` 不等于同一快照；不得为分页修改全局事务隔离级别或给派工/状态机套长事务。[PostgreSQL 17 隔离级别说明](https://www.postgresql.org/docs/17/transaction-iso.html)明确默认 READ COMMITTED 下，同一事务的两次 SELECT 仍可看到不同数据。

**边界**：这只减少 Java 实体加载和数据库到服务端的数据传输；精确 COUNT 和深页 OFFSET 仍可能有成本。第一阶段不新增页大小上限、不引入新筛选含义、不删除 `/all`。应用列表已有 Java 大小写无关子串筛选，须单独验证 Unicode、空值、未知字段以及 `%`/`_` 等字面量，不机械替换为 LIKE。协议/绑定/Webhook 等小表待容量证明后再迁移。

**验收**：固定数据集逐页对比原实现的行 ID、顺序和 totals；覆盖空表、越界页、软删行及并发新增/删除。大数据集下返回当页实体，任务执行 SQL 和写入路径不变。

### B. 索引：按 SQL 和 EXPLAIN 选取，不批量堆索引

**现状**：任务已有 `(app_id,status)`，但 priority 策略按 `priority desc,id asc` 取首条；连接日志只有 app_id 单列索引，利用率统计还按 event/时间范围读取。Webhook 与对账使用的 `(last_update_time,id)` 索引已经存在，无需重复添加。

依据：[PriorityTaskStrategy](../backend/src/main/java/com/cayleywcs/task/orchestrate/PriorityTaskStrategy.java) L24–29、[FifoTaskStrategy](../backend/src/main/java/com/cayleywcs/task/orchestrate/FifoTaskStrategy.java) L24–29、[V1](../backend/src/main/resources/db/migration/V1__wcs_schema.sql) L211–218、[V14](../backend/src/main/resources/db/migration/V14__webhook_and_sign_v2.sql) L35–37。

**候选**：任务 `(app_id,status,is_valid,priority DESC,id ASC)`；连接日志 `(app_id,event,create_time,id)`。FIFO/在飞查询、全设备时间窗查询可能需要不同索引顺序，先取得 PostgreSQL 执行计划，再确定最终 DDL。

**实施约束**：新增迁移，保留 V1–V14 校验和；检查索引占用、任务每步更新的额外写入成本及建索引锁时间。若使用 PostgreSQL 并发建索引，单独设计 Flyway 非事务执行及 H2 测试路径，不能直接放入普通事务迁移。

**验收**：相同数据与参数下记录 EXPLAIN (ANALYZE, BUFFERS)、读写延迟及扫描行数；不以“有索引”代替“查询确有改善”。非唯一索引可随代码回退保留，确认无用后再单独移除。

### C. 统计：减少全实体加载，保持现有计算口径

**现状**：[StatsServiceImpl](../backend/src/main/java/com/cayleywcs/stats/impl/StatsServiceImpl.java) L35–84 加载全部匹配任务；L151–179 加载全部匹配报警并分组；L90–148 加载时间窗和前 7 天连接日志后重放。时间窗很大或未指定时，任务/报警加载量可持续增长。

**改法**：任务状态计数、故障码 TOP 使用聚合 SQL；报警次数聚合后仅获取每组最新消息；平均执行时长使用与当前毫秒截断规则等价的表达式，必要时仅流式读取计算所需列。连接利用率先保留原重放算法，按 `(app_id,create_time,id)` 有界读取，只保留当前设备的重放状态。

**生产快照约束（审核补充）**：当前每个统计接口的结果都由一次 SELECT 的列表计算，不能拆成多个各自读取最新状态的 SQL。一个接口响应内的汇总、TOP、最新行和全部日志批次必须来自单语句或同一个请求级一致性快照；多语句采用只读 `REPEATABLE_READ`，只在统计入口生效。连接日志流式读取须使用真正分批获取的游标/驱动设置，防止驱动仍一次缓存全部结果；关闭游标并释放连接，不跨 HTTP 请求维持快照。报警最新行查询复用相同 app/time/is_valid 条件，level/message 取自同一行。取数范围和当前时间边界在请求开始时固定。

**反例**：先聚合 failed=1，期间 retry 将该行改为 pending，再读故障 TOP 得到空列表，会返回当前实现不可能产生的混合统计。分批日志读取期间提交的、时间落在当前窗口内的事件，也不得混入已开始的快照。

**必须对齐**：完成率仍为 completed/(completed+failed)，取消不进入分母；任务时间筛选仍按 create_time；未知任务状态仍归 pending；时长只取完成任务且两端时间均存在；报警按 fault_code 聚合，最新消息按 raised_time/id；利用率仍按连接 RUNNING 定义，前 7 天回溯及设备集合保持一致。报警 TOP 同次数时，现有稳定排序保留各码首次出现的顺序，即每码最早 `(raised_time,id)` 升序；利用率相同时保留 appId 升序。任务失败码 TOP 的来源查询无显式顺序，并列规则未稳定定义，须先决定兼容要求再实施该部分，不能宣称 SQL 改写后能逐行严格等价。

**验收**：旧新实现针对同一数据快照逐字段对照，覆盖时间边界、空数据、空时间、并列 TOP 和跨窗口状态。PostgreSQL 与 H2 都验证；时长精度、整数除法及日期函数差异不能只靠 H2 测试发现。

增加可控屏障，在聚合与后续读取之间分别触发任务 retry、同码新报警插入、窗口内连接事件提交，断言整份响应对应同一快照。验证长时间窗下游标实际内存、数据库连接占用和对派工的影响；若无法同时满足既有无上限时间窗与资源目标，另行提出查询范围变更，不能暗加截断后返回不完整统计。

### D. 看板：先阻止重叠请求，再减少重复刷新

**现状**：[DashboardView](../frontend/src/views/DashboardView.vue) L57–94 在 WS 正常时仍每 2 秒串行请求连接、报警、链列表；setInterval 不等待前一轮完成，慢请求时可重叠；WS 关闭仅更改标志，没有自动重连、seq 检查或帧过期检查。任务/链列表 API 默认仅取前 100 条，看板又从这 100 条筛选活动链，因此当前活动链数量并非全量统计。

**前置阻断（审核补充，已复现）**：[MonitorPusher](../backend/src/main/java/com/cayleywcs/monitor/MonitorPusher.java) L23 使用未注册时间模块的 Jackson 2 `new ObjectMapper()`；L45 将 AlarmEntity 直接放入帧，L46–48 序列化异常后只记 debug。[AlarmEntity](../backend/src/main/java/com/cayleywcs/alarm/entity/AlarmEntity.java) 含 LocalDateTime。离线调用实际 `push()` 的结果：空报警可发送；加入 raised_time 非空的活动报警后没有发送新帧，直接序列化抛 InvalidDefinitionException。需先单独修复 WS 序列化，确认时间字段格式与前端解析契约；在“无报警→报警产生→确认→清除”真实 WS 验证通过之前，不降低现有 REST 兜底频率。不能只把 socket open 或此前收到过正常帧当作通道健康。

**访问控制前置（再次自审补充）**：[SecurityConfig](../backend/src/main/java/com/cayleywcs/config/SecurityConfig.java) 将 `/ws/**` 设为 `permitAll`，[WebSocketConfig](../backend/src/main/java/com/cayleywcs/config/WebSocketConfig.java) 允许任意 Origin；[JwtAuthenticationFilter](../backend/src/main/java/com/cayleywcs/auth/JwtAuthenticationFilter.java) 仅在请求带 `Authorization: Bearer` 时建立身份，而当前 [DashboardView](../frontend/src/views/DashboardView.vue) 的 WebSocket 握手未携带认证信息。因此未认证客户端可以订阅监控帧，暴露连接和报警数据。管理端 JWT 存于 localStorage，浏览器原生 WebSocket 构造函数不能像 axios 一样添加自定义 Authorization 请求头；应先确定短期握手凭证等方案及失效/重连语义，并用未认证拒绝、已认证接收的真实握手测试验证。鉴权属于对外访问契约修复，单独评估；未完成时仅做请求单飞，不把 WS 作为减少 REST 查询的依据。

**改法**：轮询加单飞与卸载取消/过期响应丢弃；连接与报警优先消费健康 WS，断线或超时恢复轮询，加入有上限退避重连。定期 REST 校准间隔由页面验收决定，不能仅凭 socket open 就永久停轮询。链与 work_mode 不在当前 WS 帧中，须保留独立刷新；重连时用管理端权限下的接口恢复数据，不让管理端直接调用需 AppKey 的开放接口。

**恢复约束**：健康判断必须检查最近完整且可解析帧的时间、type 和所需字段；具体过期阈值须在编码前给出并验证可接受的页面更新延迟。seq 当前为进程内计数，后端重启会归零，不能永久拒绝较小 seq。每次 WS 连接建立新会话代号，旧连接回调、重连定时器和上一会话的 REST 响应全部失效；同一页面会话里，轮询发起后若已接收更晚 WS 帧，其连接/报警响应不得回写覆盖。链数据单独处理，不因连接/报警丢弃规则而停止刷新。

**范围拆分**：请求去重是性能优化；增加翻页控件、后端活动链筛选/计数会改善当前展示行为，应作为独立小项批准，避免将前 100 条中的数量继续标作全量。

**验收**：未认证 WS 握手拒绝、管理端认证后接收、凭证过期/重连、正常、报警生命周期、断网、恢复、socket 仍开但无帧、无效帧、服务端重启 seq 归零、REST 晚于更新的 WS 返回、快速切页场景；每类轮询至多一轮在途，WS 正常时重复查询下降，链和工作模式仍刷新，旧请求不能覆盖较新状态。WebSocketSession 发送/浏览器接收必须纳入验证，离线序列化 probe 不能代替该验收。

### E. 调度资源隔离：第一步保持串行顺序

**现状**：[WebhookDeliveryWorker](../backend/src/main/java/com/cayleywcs/webhook/WebhookDeliveryWorker.java) L72–136 每轮最多 100 条，同步 `send` 且逐条等待，请求超时为 10 秒；大量慢目标会使单轮持续很久。[MonitorWebSocketHandler](../backend/src/main/java/com/cayleywcs/monitor/MonitorWebSocketHandler.java) L30–44 逐会话同步发送；看门狗在 [ConnectionManager](../backend/src/main/java/com/cayleywcs/connection/ConnectionManager.java) L319–425 中串行等待重连。公共定时池当前为 4 线程，任务引擎已有独立 worker 池。

**改法**：优先给 Webhook 投递和监控推送独立、受控的调度资源，保持各自 fixedDelay、initialDelay、单轮不重叠，以及每轮按 `(next_retry_time,id)` 顺序串行投递。现有行为不保证业务事件全局 FIFO，不能把线程串行描述成事件全局有序。不将投递随意改成 fire-and-forget。对 WS 可进一步做每会话有界缓冲、串行发送及慢连接断开策略，作为需要额外验收的后续项。

**调度绑定约束（审核补充）**：本机 Spring Boot 4.0.6 在存在自定义 TaskScheduler/ScheduledExecutorService 时会退出默认调度器自动配置；Spring 7.0.7 的任务路由在多个候选无法确定默认池时可能回退单线程。因此不能只声明 webhookScheduler 与 monitorScheduler。明确保留名称 `taskScheduler`、绑定原 scheduling 配置且池大小为 4 的核心池，并为投递和推送各建单线程池；对应两个 `@Scheduled` 明确指定 scheduler，其余任务也必须可验证地绑定核心池。启动断言实际池名、大小和任务所属池，不依赖 yml 中仍写着 size=4。此结论已核对当前本机依赖字节码，不能用其他 Spring 版本的默认行为代替。

**能力边界与停机**：此阶段只隔离 Webhook/监控，不消除看门狗内部串行重连，也不保证慢设备之间公平。每个定时任务仅注册一次；回退切换必须先停旧注册并等待在途工作结束，再启用原调度。停机先拒绝新轮次，等待正在运行的投递及其结果落库，然后释放依赖资源；即使如此，远端成功后本地落库前退出仍可能导致重投，保持当前至少一次交付边界，不能承诺恰好一次。

**中断处理（审核补充，已复现）**：当前 `runOnce` L87–90 没有逐条停止检查，`deliverOne` L118–119 把 InterruptedException 纳入普通异常后继续循环。离线中断第一条 HTTP 后，第二条仍被调用并标记 ok；仅依赖新调度池的 shutdownNow 无法兑现上述停机目标。实现前需确定 stopping 状态和有限等待时间：在每条投递前检查停止；中断单独处理，按既有约定记录当前已开始投递的失败/退避，再恢复中断标志并退出本轮；尚未开始的行保持 pending 且不增加 attempts。销毁数据库、HTTP 客户端之前结束投递线程，明确结果未知的在途请求仍可能重投。该行为修正需与资源隔离一起审核，不视为换个线程池就自然获得。

**暂缓**：Webhook 跨订阅并发、每设备并行重连、任务引擎改执行器，都需要额外占位、停机和竞态设计。本轮不直接推荐上线。尤其目前投递行没有并发 claim，直接多 worker 会重复发送。

**验收**：慢 HTTP 接收端和慢 WS 会话并存时，记录 TaskEngine/编排/恢复的调度延迟；投递 attempts、退避、签名体、成功/失败状态及排序不变；停机不能遗留继续发送的 worker。后续增加并发时再验证领取互斥与崩溃恢复。

增加启动上下文测试：自定义三个池后，核心池仍为 4 线程，所有任务的目标池正确；用可控慢重连而非真实设备证明核心池未退化。模拟停机时 HTTP 已发出、尚未落库，核对不发生同时双发、重启重投仍遵守既有幂等要求，回退后没有重复注册。

### F. 点位名称索引：小改动，低优先级

**现状**：[ConnectionManager](../backend/src/main/java/com/cayleywcs/connection/ConnectionManager.java) L229–247、L304–316 每次读写都遍历点位列表查 field_name。

**改法**：在 ManagedConnection 创建时构造只读名称索引，维持连接当前点位快照的生命周期；保留原 List 供 readAll。重复名称必须保持当前 findFirst 的优先规则，缺点位异常和可选点位返回 null 的行为不变。

**边界**：仅替换定位方式；不缓存 PLC 值，不移除 channelLock，不批量合并设备写入，也不改变在线配置生效时机。点位数量少时收益有限，不应优先于大表分页。

### G. 可观测性：先测量，再决定扩展

**改法**：增加各调度轮次耗时/延迟、分页与统计延迟、worker 活跃数、回调待投递量/最老等待时间、审计写入失败计数。日志携带已有 appId/taskId/chainId；不要把任务号放入指标标签，不记录签名、密钥和完整业务报文。

依据：[AuditServiceImpl](../backend/src/main/java/com/cayleywcs/audit/impl/AuditServiceImpl.java) L27–59 的写入失败目前仅为 debug；[MonitorPusher](../backend/src/main/java/com/cayleywcs/monitor/MonitorPusher.java) L33–49 的推送异常同样仅 debug。

**边界**：保留现有审计同步写入与失败处理语义，通过指标及限频日志使异常可见；不借此改成内存异步审计队列。指标采集使用有界标签，积压统计避免高频重复扫描大表。

## 3. 不纳入等价优化的两项可靠性问题

以下是由代码路径推导的风险，尚未做专门复现；61 项基线通过不能证明它们不存在。应先用仿真/测试数据库复现，再单独审核修复。

1. **正常停机可能清掉自动恢复意图**：ConnectionManager.shutdown L540–547 调用 close，close L164–166 持久化 desired_state=closed；ConnectionRecoveryRunner 仅恢复 desired=open。因而数据库写成功时，正常退出后原本打开的连接可能不会自动恢复。拟修复方向是区分用户主动关闭和进程资源释放，增加正常重启回归；这会改变当前停机行为，不夹带实施。
2. **并发重复下发缺少独立任务幂等的数据库兜底**：TaskServiceImpl.dispatch L69–102 为先查后插；现有唯一约束保护链号及 `(chain_id,seq_no)`，没有保护独立任务的相同 appId/taskNo。并发请求可能都查空后插入。拟先复现，再确认租户、空任务号、软删与链任务命名空间，设计原子防重和 PostgreSQL 冲突后的事务边界；不能直接加一个全表唯一索引。

## 4. 本轮明确暂缓的改法

- 不因任务字段未变化就跳过 `taskService.save`：当前每步 save 会刷新 last_update_time，停滞检测与对账依赖它。减少写入须先另行设计时间语义。
- 不给 work_mode、授权绑定、desired_state 和任务状态加长 TTL 缓存；这些值的即时可见性影响控制行为。
- 不改 PLC 轮询周期、心跳阈值、复位脉冲、握手读写次数或顺序；多点批量读也会改变采样时序，须有协议验证后另案处理。
- 不把任务链改并行段、不批量预读后用过期队列长度选机、不引入内存任务队列取代 DB、不做多实例调度/双机热备。
- 不把 Webhook 扫描器改为事务事件表或改变对账游标契约；这是可靠性架构升级，不能算本轮等价优化。
- 不为优化删减历史任务、报警或连接日志；保留期/归档会改变查询和审计范围，需单独决策。

## 5. 实施顺序、验证与回退

**第一阶段（建议本次批准）**：G 记录基线 → A 任务/报警/链数据库分页 → B 根据执行计划新增必要索引 → F 可选点位索引。逐项交付，所有默认控制参数不变。

实际实施已先开始 A，故 G 的“变更前基线”不能从当前工作区直接采集；性能对照必须使用 HEAD `07f0cf8` 的独立环境，并保持数据集、数据库状态与请求负载一致。A 目前仅完成 H2 固定数据对照，不能据此宣称 PostgreSQL 并发快照或性能验收通过。

**第二阶段（分别审核）**：C 先验证请求内统计快照再聚合；D 先修复并验证活动报警 WS 帧，再做看板请求治理；E 先用最小上下文验证三池绑定与停机，再做资源隔离。原第 3 节两项可靠性问题仍需复现后另列修复任务。后续发现收益不足的项目可以取消，不为完成清单而重构。

**测试矩阵**：

| 变更面 | 所需验收 |
|---|---|
| 分页/索引/统计 | 新旧结果对照；PostgreSQL 执行计划、写入成本；H2 和 PostgreSQL 迁移；边界数据与并发读取 |
| 点位/调度 | 现有握手、claim 冲突、再收养、派工/暂停、链取消回归；仿真设备读写轨迹保持顺序 |
| Webhook/WS/前端 | 慢目标、断线/重连、无帧、在途请求取消；签名/退避/事件顺序；type-check 与 build |
| 发布前 | 完整现有测试；真 PostgreSQL+Redis 冒烟；涉及设备通道则执行 Milo OPC UA 联调 |

容量基线建议使用隔离环境中 10 万/100 万历史记录、8/32 模拟设备、1/10 浏览器会话对照，固定设备响应延迟和请求负载。记录 p50/p95、堆内存/GC、数据库返回/扫描行数、调度延迟及投递积压；这些是计划，不代表本次已压测。业务结果与控制命令轨迹一致是前提，性能改善才有意义。

**回退**：应用改动按模块回退；新增非唯一索引可先保留，不要求业务停机删索引；WS/调度策略在后续实现时预留回到原模式的配置。新旧实现对照只用于读取，绝不让两套状态机同时下发设备命令。

## 6. 首轮方案编写时的验证（审核前基线）

- 后端选定 10 个现有测试类：**61 项通过，0 失败，0 错误，0 跳过**。
- 前端 `npm run type-check`：通过。
- 文档 20 个源码链接有效，`git diff --check` 通过，后端/前端源码无本次差异；技能三端同步检查通过。
- 根工作区 `tools/doctor.py` 未通过：8 处既有 CLAUDE 薄指针问题，均位于其他工作树或 WMS 文档目录，本次未处理；此结果不表示工作区全部自检通过。
- 本次未跑完整测试、前端 build、真实 PostgreSQL/Redis 联调、真实 PLC 或性能压测。
- 本次只增加方案、进展日志及 README 文档入口；仓库原有 `.agents/tester/AGENTS.md` 删除状态未处理。

后端命令（在 backend 目录执行）：

```powershell
mvn "-Dtest=TaskEngineConcurrencyTest,StackerHandshakeTest,StackerHandshakeReentryTest,StackerHandshakeHardeningTest,DeviceHandlerHardeningTest,ChainOrchestrationTest,DispatchStrategyTest,RecoveryAndStatsTest,SelfHealingTest,WebhookFlowTest" test
```

本次输出保存在 `backend/target/optimization-review-baseline.log`，其余报告在 `backend/target/surefire-reports/`；构建目录清理后这些临时报告可能消失。

## 7. 实施前方案审核的验证与结论（历史记录）

审核任务 TASK-20260929-36E3B844，采用主 Agent 核对和两路独立源码审阅，未修改业务代码。确认 4 处需要修订的实施条件，已落实到本文件；WS 重连 seq 归零、旧回调失效和 REST 晚响应覆盖规则也已补齐。

| 离线实验 | 实际观测 | 临时证据 |
|---|---|---|
| 调用当前 MonitorPusher.push | 空报警发送成功；加入含 LocalDateTime 的活动报警后整帧未发送，序列化为 InvalidDefinitionException | `backend/target/PlanReviewProbe.java`、`backend/target/plan-review-probe.log` |
| 最小 Spring 调度上下文 | 基线 taskScheduler=4 线程；只新增两个专用池后默认池消失，回退单线程，第二个核心任务被第一个阻塞 | `backend/target/SchedulerIsolationProbe.java`、`backend/target/SchedulerIsolationProbe.log` |
| 真实 WebhookDeliveryWorker + 内存 Mapper/HTTP 替身 | 第一条被中断后仍调用第二条 send；第二条 attempts=1、status=ok | `backend/target/WebhookShutdownProbe.java`、`backend/target/WebhookShutdownProbe.log` |

上述 3 个实验均未连接真实设备或数据库，未发送 HTTP 请求。使用首轮 Maven 测试生成的编译类和 Surefire XML 中的 `java.class.path` 运行临时 Java 程序；target 为构建目录，日志和源码可能随清理消失。实验用于证明当前问题/方案反例，不代表优化已实现或真实网络验收通过。

统计快照问题经源码路径与 PostgreSQL 隔离语义确认，本轮未运行 PostgreSQL 并发实验；该实验已列入 C 的实施验收。首轮的 61 项测试与前端类型检查本轮未重复运行，也不能替代新增的 WS、调度上下文、停机和并发统计用例。

本轮文档验证：22 个源码链接有效，git diff --check 通过，技能三端同步检查通过；根 doctor 仍报告相同的 8 处范围外 CLAUDE 薄指针问题。原有 `.agents/tester/AGENTS.md` 删除状态保持未处理。

当时的审核结论是：可保留 G 有界指标、A 三个管理列表分页、B 执行计划验证后确定的索引、F 可选点位索引；C/D/E 必须先补足上述前置条件。此段为实施前结论，当前进度与剩余验收以文首状态及实施日志为准。
