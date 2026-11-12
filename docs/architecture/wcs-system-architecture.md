# 礁盘工业 WCS 系统架构

本图依据当前源码与Docker运行配置，展示HTTP对接应用、管理端、WCS后端模块、设备和存储的关系。WCS后端为一个Spring Boot进程；图中的配置、任务、连接、驱动、监控与回调都是这个进程内的模块。

![WCS系统架构](wcs-system-architecture.png)

[矢量架构图](wcs-system-architecture.svg) · [高清架构图](wcs-system-architecture.png)

任务优先级在同一设备应用的待派工队列中生效。当前Docker堆垛机应用3使用优先级策略 priority，工作模式 normal，连接期望状态 open；当前21个有效且启用的应用均配置为 priority。数值越大越优先，未填写时为0，同分按任务编号升序派工。已经下发或执行中的任务先继续，后来的高优先级任务不能抢占设备。

| 场景 | 当前行为 |
| --- | --- |
| 同设备，待派工任务优先级分别为10和1 | 选择10；相同优先级按任务编号升序 |
| 设备派工策略为优先级 priority | 按优先级降序、任务编号升序选择 |
| 设备派工策略为先进先出 fifo | 按任务编号升序选择，优先级数值不参与排序 |
| 已下发或执行中的任务尚未完成 | 继续原任务；更高优先级的新任务等待 |
| 设备处于暂停或维护模式 | 停止新派工，已有在飞任务继续收尾 |
| 不同设备分别有任务 | 由各设备工作线程独立推进，没有跨设备全局优先级队列 |
| 任务链有优先级 | 物化子任务继承链优先级，进入对应设备队列；链扫描本身按链编号升序，链段保持顺序执行 |

任务队列页面的“改优先级”调用真实管理接口。后台允许修改非终态任务的优先级，已下发或执行中的任务修改数值后仍继续原有执行。普通应用更新不修改派工策略，当前应用编辑页面也没有策略选择控件。

设备连接须进入运行状态，任务执行前还须通过固定协议版本、绑定权限、任务号租约及设备状态检查；优先级不会绕过这些条件。

PostgreSQL持久保存协议快照、绑定、应用令牌摘要、任务、任务链、原生任务号租约、采集结果、报警、日志、回调游标和投递记录。Redis保存管理端刷新令牌。WebSocket票据和设备互斥锁在后端进程内管理。Webhook根据数据库中的业务状态变化生成投递待办，并通过带签名的HTTP请求通知接入应用。

当前入站上位协议实现为HTTP。真实下位适配器覆盖OPC UA、S7、Modbus TCP、HTTP、MQTT和TCP，并提供内存sim及loopback测试适配。设备业务驱动覆盖堆垛机、码垛机、输送线、提升机和自动门。Milo OPC UA堆垛机仿真服务端在simulator配置启用时启动。

实现依据：

- [设备队列优先级策略](../../backend/src/main/java/com/cayleywcs/task/orchestrate/PriorityTaskStrategy.java)：按设备应用过滤待派工任务，再按优先级与编号选择。
- [派工入口与在飞优先约束](../../backend/src/main/java/com/cayleywcs/task/impl/TaskServiceImpl.java)、[任务引擎](../../backend/src/main/java/com/cayleywcs/task/TaskEngine.java)。
- [任务链编排](../../backend/src/main/java/com/cayleywcs/task/orchestrate/TaskOrchestrator.java)：按链编号扫描、按段序推进并继承链优先级。
- [应用令牌存储](../../backend/src/main/java/com/cayleywcs/openapi/ApplicationTokenService.java)、[管理刷新令牌存储](../../backend/src/main/java/com/cayleywcs/auth/RefreshTokenStore.java)。
- [设备执行协调](../../backend/src/main/java/com/cayleywcs/connection/DeviceExecutionCoordinator.java)、[连接治理](../../backend/src/main/java/com/cayleywcs/connection/ConnectionManager.java)。
- [协议关系匹配](../../backend/src/main/java/com/cayleywcs/protocol/contract/impl/ProtocolContractServiceImpl.java)、[协议适配器](../../backend/src/main/java/com/cayleywcs/adapter/ProtocolAdapter.java)。
- [回调扫描](../../backend/src/main/java/com/cayleywcs/webhook/WebhookScanner.java)、[投递与重试](../../backend/src/main/java/com/cayleywcs/webhook/WebhookDeliveryWorker.java)、[实时推送](../../backend/src/main/java/com/cayleywcs/monitor/MonitorPusher.java)。

本次运行派工策略、设备调度并发和任务链编排的现有测试共19项，全部通过；当前Docker配置读取未修改用户数据。图像已渲染并核验文字、布局和连接关系。验证记录见[架构与优先级验证](../reviews/2026-10-13-architecture-priority-evidence.json)。
