# HTTP 到 OPC UA 堆垛机任务执行时序

本图说明当前WCS正常任务的典型交互，正式嵌入WCS开放API文档的“下发单任务”说明后。WCS接入与通知一栏合并展示开放接口、查询和回调模块；WCS调度与握手一栏展示任务引擎、执行协调及堆垛机驱动。数据库操作省略返回箭头，读取操作的条件和结果写在相应说明中。

![堆垛机任务时序](stacker-task-sequence.png)

[矢量原图](stacker-task-sequence.svg) · [高清原图](stacker-task-sequence.png)

HTTP返回表示任务已受理，不代表设备已完成。后台按设备和派工策略异步推进，调度开始与HTTP响应返回可能并行。

驱动在写执行确认前先保存执行意图，并通过任务版本占位。参数依次写入取放货坐标、货口、原生设备任务号和任务类型；命令区任务号回读不匹配时退回重写。执行确认点 cmd_ConfirmTask 写1启动；读取任务状态 status_Task 为1时，确认点写0并继续等待。

读取任务状态 status_Task 为2后，先采集业务响应、核验类型和反馈任务号并持久保存采集结果 result，再写完成确认2。清零阶段会补发完成确认2，将命令区清零，并回读任务状态为0；之后保存已完成状态 completed，成功持久保存后释放原生任务号占用。查询与回调使用本任务已经保存的结果。

设备故障按当前业务状态机判失败。响应采集校验未通过时不进入完成确认或清零。通信异常通常放弃当前步骤等待恢复，长期无进展还受停滞配置处理。重启依已存握手阶段与设备反馈恢复，失败任务的原生号占用在受控复位确认空闲后释放。

| 时序内容 | 实现依据 |
| --- | --- |
| 任务受理、授权、幂等与落库 | [OpenTaskController](../../backend/src/main/java/com/cayleywcs/task/OpenTaskController.java)、[TaskServiceImpl](../../backend/src/main/java/com/cayleywcs/task/impl/TaskServiceImpl.java) |
| 按设备推进、互斥、保存及租约释放顺序 | [TaskEngine](../../backend/src/main/java/com/cayleywcs/task/TaskEngine.java)、[DeviceExecutionCoordinator](../../backend/src/main/java/com/cayleywcs/connection/DeviceExecutionCoordinator.java) |
| 原生任务号预约、执行前复验、采集结果立即持久保存 | [TaskExecutionService](../../backend/src/main/java/com/cayleywcs/task/TaskExecutionService.java) |
| 前置条件、参数顺序、确认1/0/2、完成及清零 | [StackerHandshakeStateMachine](../../backend/src/main/java/com/cayleywcs/task/StackerHandshakeStateMachine.java) |
| 任务版本占位 | [AbstractDeviceHandler](../../backend/src/main/java/com/cayleywcs/device/AbstractDeviceHandler.java) |
| 终态查询与签名回调 | [OpenReconcileController](../../backend/src/main/java/com/cayleywcs/openapi/OpenReconcileController.java)、[WebhookScanner](../../backend/src/main/java/com/cayleywcs/webhook/WebhookScanner.java)、[WebhookDeliveryWorker](../../backend/src/main/java/com/cayleywcs/webhook/WebhookDeliveryWorker.java) |

图像、Word插入位置、真实目录页码和逐页排版验证见[时序图文档验证](../reviews/2026-10-13-stacker-sequence-document-evidence.json)。
