# 实时看板点位与应用适配器

## 实时看板

[DashboardView](../frontend/src/views/DashboardView.vue) 使用 [DashboardService](../backend/src/main/java/com/cayleywcs/dashboard/DashboardService.java) 提供的已发布点位和状态缓存，覆盖当前已建立连接的设备应用。AGV 默认展示所在站点、导航任务状态、速度、目标和定位置信度；堆垛机沿用实际协议中的状态点位，不向其他设备套用 PLC 字段。

[DashboardDeviceCard](../frontend/src/components/DashboardDeviceCard.vue) 的右上方是“看板展示编辑”，下一行左侧为应用名称、右侧为连接状态。点位使用有边框的两列网格，每行两项，长数组和文字在单元格内换行。卡片高度固定 400px，选项数量和内容长度变化时，仅数据区内部滚动，不撑开相邻应用或压缩前一列。

展示编辑为中文点位名称的复选框列表，允许全选、清空和任意组合。显式清空会展示空状态，不回退为默认点位；请求点位无回读时显示“未提供回读”。选择按租户、账号、应用保存到 `wcs_dashboard_point_selection`。保存时校验归属、发布版本和点位集合；更换账号互不影响。此设置只改变展示，不改变应用配置或设备值。

[pointLabels](../frontend/src/utils/pointLabels.ts) 保留人工维护的中文点位名；对旧协议中 `PLC_Heartbeat`、`状态_运行模式` 等自动生成别名，使用对应逻辑字段的正式中文说明。协议中存储的点位编码、地址、名称和历史快照不因此改写。

| 接口 | 数据 |
| --- | --- |
| `POST /dashboard/devices` | 当前连接应用的真实点位、值、质量、版本、所选字段 |
| `POST /dashboard/selection` | `{appId, revisionId, fields: [点位编码]}`；保存当前账号的选择 |
| `POST /application/adapter/options` | 已实现应用适配器及其协议、设备类型范围 |

所有接口使用管理端 JWT 和统一响应。看板每 2 秒读取状态缓存；连接/报警的原 WebSocket 与 REST 校准逻辑保留。过期和缺失值按质量显示，不用提交值代替设备反馈。

## 协议与应用适配器

协议类型只表示通用通信机制：HTTP、OPC UA、Modbus TCP、S7、MQTT、TCP，以及仿真、回环测试类型。`seer_tcp` 和 `sensor_gateway` 不再是可创建或注册的协议类型。

[ApplicationAdapterCatalog](../backend/src/main/java/com/cayleywcs/adapter/ApplicationAdapterCatalog.java) 定义应用的 `adapter_type`：

| 应用适配器 | 绑定协议 | 用途 |
| --- | --- | --- |
| `standard` | 常规协议 | 使用协议原有通用实现 |
| `seer_agv` | `tcp` | 仙工 AGV 状态帧、响应关联和点位映射；仅 1004/1005/1020 查询 |
| `dr164_sensor` | `tcp` | DR164 共享 TCP 总线、RTU 报文及传感器逻辑订阅 |

[ProtocolAdapterFactory](../backend/src/main/java/com/cayleywcs/adapter/ProtocolAdapterFactory.java) 先校验应用适配器与协议/设备类型是否匹配，专用应用使用 [ApplicationAdapterProvider](../backend/src/main/java/com/cayleywcs/adapter/ApplicationAdapterProvider.java)，普通应用使用原 `ProtocolAdapterProvider`。仙工编码与解析在 [SeerTcpAdapter](../backend/src/main/java/com/cayleywcs/adapter/seer/SeerTcpAdapter.java) 及其会话中，传感器共享采集由 [SensorGatewayAdapter](../backend/src/main/java/com/cayleywcs/adapter/sensor/SensorGatewayAdapter.java) 对接原总线所有者，不新增设备专属协议类型。

应用管理可以选择适配器；改变适配器与改变连接参数一样执行版本、任务占用和空闲校验。协议点位仍按草稿编辑、发布快照、应用固定版本的通用流程维护。

## V24 迁移

[V24](../backend/src/main/resources/db/migration/V24__application_adapters_and_dashboard.sql) 增加 `adapter_type` 与看板选择表。旧专用类型统一改为 `tcp`，关联应用分别选择 `seer_agv`、`dr164_sensor` 并推进配置代次。迁移只纠正传输分类元数据，点位内容、发布版本编号、历史指纹和绑定/任务引用保留；协议行版本递增，以拒绝旧页面覆盖。

验证入口：

- [适配器路由测试](../backend/src/test/java/com/cayleywcs/adapter/ApplicationAdapterRoutingTest.java)：相同 TCP 协议的通用/仙工路由、无效组合拒绝与旧仿真上下文兼容。
- [迁移测试](../backend/src/test/java/com/cayleywcs/protocol/ApplicationAdapterMigrationTest.java)：V23→V24 的身份、点位、指纹、配置保留和重复执行。
- [真实协议与看板集成测试](../backend/src/test/java/com/cayleywcs/agv/UnifiedDeviceStateIntegrationTest.java)：真实 TCP 只读反馈、共享采集、选择保存、清空、账号/租户隔离、旧版本和未知点位拒绝。
- [PostgreSQL 演练](../scripts/POSTGRES_MIGRATION_REHEARSAL.md)：支持 `--source-version 23 --target-version 24`，从当前数据库只读备份到独立副本，使用构建产物的真实 Flyway 迁移，逐字段核对保留项。
