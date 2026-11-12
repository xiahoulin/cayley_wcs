# 堆垛机 OPC UA 原始协议符合性核查

任务：TASK-20261013-FCBCC0C5。核查时间：2026-10-13T17:15:34.564899+09:00。

结论：当前正常任务握手、27个点位名称与方向映射、60元素Int16故障数组及48项释义基本符合原始资料，但不能认定完全符合现场PLC协议。发现PLC心跳停止翻转不被识别、48正常复位通知被当任务故障、任务号INT/65534自相矛盾及模拟反馈枚举偏差。

本核查只读原始工作簿、当前源码、Docker PostgreSQL；没有改代码、设备或在线业务数据。WMS真实链路注入与收到结果由主任务实验单独记录。

## 原始来源与当前在线应用

- 原始工作簿：[堆垛机通讯协议-opcua对接版.xlsx](../堆垛机通讯协议-opcua对接版.xlsx)，SHA-256 `efa5c1f5a43f9d170d93f3561436ba4c84c44b4629081e592fbb735bdb852913`。
- 在线应用 `PALLET_SIM`、编号 `3`，协议 `PALLET_SIM_OPCUA`、编号 `3`，固定已发布版本编号 `2`。
- 物理协议 STACKER_STD、STACKER_OPCUA 当前均为草稿；仿真协议 PALLET_SIM_OPCUA 已发布。
- 对照的是应用绑定的冻结快照，不把编辑草稿误当实际运行协议。

## 关键差异

| 编号 | 程度 | 原表位置 | 发现 |
| --- | --- | --- | --- |
| OPC-01 | high | WCS对接流程!B45 | 没有监测PLC_Heart是否翻转。每次成功读状态区即刷新lastHeartbeatAt，因此PLC应用停止跳动而OPC UA仍正常响应时不会超时告警。 |
| OPC-02 | high | 堆垛机标准协议!F28 第48项 | 48为3轴已复位，当前字典标info/正常状态，却仍被任何非零即故障的闸门阻塞并使任务failed。 |
| OPC-03 | high | 堆垛机标准协议!I12/F12/I26/F26；WCS对接流程!C21/D21/C38/D38 | 原表任务号类型INT与范围1–65534不相容。仿真明确使用UINT16支持65534；物理协议仍INT/INT16且在线保持draft，必须确认现场PLC真实类型，不能把仿真通过当现场完全符合。 |
| OPC-04 | medium | 堆垛机标准协议!F3/F17；WCS对接流程!D12/D29 | 请求3=移库、4=移动；原表反馈3=移动、4=转库。模拟器直接复制请求类型，当前反馈约束扩展0–6。真实驱动只保留PLC反馈原值，须确认0–5/6与3/4映射。 |
| OPC-05 | low | 堆垛机标准协议!F14；WCS对接流程!D26/B45 | WCS主动心跳5000ms符合原表；模拟PLC心跳每tick翻转，Milo每1秒tick，故仿真PLC心跳周期1秒而非原表5秒。 |
| OPC-06 | information | 堆垛机标准协议!F3/F5:F12/F18:F19/F26:F27；WCS对接流程!B49 | 业务命令类型仅允许1–6，0由驱动清零；取放坐标/货口等允许0用于非适用方向及收尾，实际适用方向坐标和入出库货口另有语义校验。反馈坐标/货物/任务号允许0作为空闲值，为兼容扩展。移动类型4额外要求有效排，较原表仅列层更严格。 |
| OPC-07 | information | 堆垛机标准协议!H2:H27/J2:J28 | 原表提供DB100地址与WCS_Task.From_WCS/To_WCS符号路径，未给OPC UA命名空间索引。仿真ns=2、物理种子ns=3带Siemens引号均为服务端映射；现场需浏览节点核对，不能仅凭xlsx验证具体NodeId。故障数组原表未给英文符号名，当前status_ErrorCode为明确映射。 |
| OPC-08 | information | 堆垛机标准协议!G12 | 当前执行确认前回读命令任务号，模拟请求节点允许读写；原表W表示WCS写入方向但未声明真实OPC UA AccessLevel，须现场确认cmd_TaskNum允许读取，否则该安全回读会失败并阻止执行。 |

## 27个点位对照

原表“堆垛机标准协议”D28只写“故障代码”，H28未定义英文符号；本表把它对应当前故障数组 status_ErrorCode。DB地址是原资料固定内存偏移，OPC UA使用服务端发布符号NodeId，不能把DB地址直接当NodeId。

| 原表行 | 字段 | DB地址 | 原表原生类型 | 当前原生类型 / 逻辑类型 | 方向 / 用途 | 当前NodeId | 核查 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2 | `WCS_Heart` | `DB100.DBW0` | `INT` | `INT` / `INT16` | `request` / `internal` | `ns=2;s=WCS_Task.From_WCS.WCS_Heart` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 3 | `cmd_TaskType` | `DB100.DBW2` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_TaskType` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 4 | `cmd_ResetFault` | `DB100.DBW4` | `INT` | `INT` / `INT16` | `request` / `internal` | `ns=2;s=WCS_Task.From_WCS.cmd_ResetFault` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 5 | `cmd_TakeCoor_Row` | `DB100.DBW6` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_TakeCoor_Row` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 6 | `cmd_TakeCoor_Column` | `DB100.DBW8` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_TakeCoor_Column` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 7 | `cmd_TakeCoor_Floor` | `DB100.DBW10` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_TakeCoor_Floor` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 8 | `cmd_PutCoor_Row` | `DB100.DBW12` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_PutCoor_Row` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 9 | `cmd_PutCoor_Column` | `DB100.DBW14` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_PutCoor_Column` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 10 | `cmd_PutCoor_Floor` | `DB100.DBW16` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_PutCoor_Floor` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 11 | `cmd_PortNum` | `DB100.DBW18` | `INT` | `INT` / `INT16` | `request` / `business` | `ns=2;s=WCS_Task.From_WCS.cmd_PortNum` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 12 | `cmd_TaskNum` | `DB100.DBW20` | `INT` | `UINT` / `UINT16` | `request` / `internal` | `ns=2;s=WCS_Task.From_WCS.cmd_TaskNum` | 原表INT与1–65534相矛盾；仿真使用UINT，物理草稿保留INT且不能按65534范围发布。 |
| 13 | `cmd_ConfirmTask` | `DB100.DBW22` | `INT` | `INT` / `INT16` | `request` / `internal` | `ns=2;s=WCS_Task.From_WCS.cmd_ConfirmTask` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 14 | `PLC_Heart` | `DB100.DBW24` | `INT` | `INT` / `INT16` | `response` / `internal` | `ns=2;s=WCS_Task.To_WCS.PLC_Heart` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 15 | `status_Mode` | `DB100.DBW26` | `INT` | `INT` / `INT16` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_Mode` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 16 | `status_Task` | `DB100.DBW28` | `INT` | `INT` / `INT16` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_Task` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 17 | `status_TaskTypeFeedback` | `DB100.DBW30` | `INT` | `INT` / `INT16` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_TaskTypeFeedback` | 类型一致但取值/语义有扩展：当前0–6，原表0–5；模拟器直接回显请求3/4，原表反馈3=移动、4=转库。 |
| 18 | `status_CurrentColumnNum` | `DB100.DBW32` | `INT` | `INT` / `INT16` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_CurrentColumnNum` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 19 | `status_CurrentFloorNum` | `DB100.DBW34` | `INT` | `INT` / `INT16` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_CurrentFloorNum` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 20 | `status_Speed_Lift` | `DB100.DBD36` | `REAL` | `REAL` / `FLOAT32` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_Speed_Lift` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 21 | `status_Speed_Walk` | `DB100.DBD40` | `REAL` | `REAL` / `FLOAT32` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_Speed_Walk` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 22 | `status_Speed_Fork` | `DB100.DBD44` | `REAL` | `REAL` / `FLOAT32` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_Speed_Fork` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 23 | `status_CurrentPos_Walk` | `DB100.DBD48` | `REAL` | `REAL` / `FLOAT32` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_CurrentPos_Walk` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 24 | `status_CurrentPos_Lift` | `DB100.DBD52` | `REAL` | `REAL` / `FLOAT32` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_CurrentPos_Lift` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 25 | `status_CurrentPos_Fork` | `DB100.DBD56` | `REAL` | `REAL` / `FLOAT32` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_CurrentPos_Fork` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 26 | `status_TaskNum` | `DB100.DBW60` | `INT` | `UINT` / `UINT16` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_TaskNum` | 原表INT与1–65534相矛盾；仿真使用UINT，物理草稿保留INT且不能按65534范围发布。 |
| 27 | `status_Cargo` | `DB100.DBW62` | `INT` | `INT` / `INT16` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_Cargo` | 字段、方向、原生类型匹配；NodeId命名空间按模拟服务端映射。 |
| 28 | `status_ErrorCode` | `DB100.DBW64-DB100.DBW182` | `ARRAY[1..60] OF INT` | `ARRAY[1..60] OF INT` / `ARRAY<INT16>` | `response` / `business` | `ns=2;s=WCS_Task.To_WCS.status_ErrorCode` | 原表D28仅写中文故障代码，H28未给符号名；当前明确映射为status_ErrorCode，仍为60个Int16。 |

用途分配与当前驱动一致：8个请求业务点位，14个响应业务点位，WCS心跳、PLC心跳、原生任务号、握手确认和异常复位共5个内部点位。故障数组的一基下标1–60对应JSON数组下标0–59；数组元素是故障号，不是60位布尔告警，也不是“第N位对应故障N”。

## 48项故障定义对照

48个故障号均存在，当前message与原表F28逐项对照后仅标点、引号、空格不同，含义一致。severity和处理建议为系统补充，原表没有给分级。48为正常复位消息，当前分级info，却仍经过非零故障闸门，这是处理逻辑错误。

| 故障号 | 原表释义 | 当前分级 | 释义核查 |
| --- | --- | --- | --- |
| 1 | 行走轴误差过大，请在下限位校准回原 | `error` | 相符 |
| 2 | 断带检测1触发 | `error` | 相符 |
| 3 | 断带检测2触发 | `error` | 相符 |
| 4 | 货仓超长检测1触发 | `warn` | 相符 |
| 5 | 货仓超长检测2触发 | `warn` | 相符 |
| 6 | 货仓超高检测1触发 | `warn` | 相符 |
| 7 | 货仓超高检测2触发 | `warn` | 相符 |
| 8 | 里库位检测1触发，库位已占有 | `warn` | 相符 |
| 9 | 里库位检测2触发，库位已占有 | `warn` | 相符 |
| 10 | 货仓超宽检测1触发 | `warn` | 相符 |
| 11 | 货仓超宽检测2触发 | `warn` | 相符 |
| 12 | 货仓超宽检测3触发 | `warn` | 相符 |
| 13 | 货仓超宽检测4触发 | `warn` | 相符 |
| 14 | 货仓货物检测未触发，无货物 | `warn` | 相符 |
| 15 | 货仓货物检测触发，货物还在货仓 | `warn` | 相符 |
| 16 | 里库位检测1未触发，库位无货物 | `warn` | 相符 |
| 17 | 里库位检测2未触发，库位无货物 | `warn` | 相符 |
| 18 | 平移轴驱动器错误，丢失“驱动器就绪”信号 | `error` | 相符 |
| 19 | 平移轴已触发下限软限位开关 | `error` | 相符 |
| 20 | 平移轴已触发上限软限位开关 | `error` | 相符 |
| 21 | 平移轴已接近下限硬限位开关 | `warn` | 相符 |
| 22 | 平移轴已接近上限硬限位开关 | `warn` | 相符 |
| 23 | 平移轴编码器发出报警消息 | `error` | 相符 |
| 24 | 平移轴未回原点 | `error` | 相符 |
| 25 | 平移轴无法启用 | `error` | 相符 |
| 26 | 平移轴未找到参考开关/编码器零位标记 | `error` | 相符 |
| 27 | 平移轴硬限位开关错误 | `error` | 相符 |
| 28 | 升降轴驱动器错误，丢失“驱动器就绪”信号 | `error` | 相符 |
| 29 | 升降轴已触发下限软限位开关 | `error` | 相符 |
| 30 | 升降轴已触发上限软限位开关 | `error` | 相符 |
| 31 | 升降轴已接近下限硬限位开关 | `warn` | 相符 |
| 32 | 升降轴已接近上限硬限位开关 | `warn` | 相符 |
| 33 | 升降轴编码器发出报警消息 | `error` | 相符 |
| 34 | 升降轴未回原点 | `error` | 相符 |
| 35 | 升降轴无法启用 | `error` | 相符 |
| 36 | 升降轴未找到参考开关/编码器零位标记 | `error` | 相符 |
| 37 | 升降轴硬限位开关错误 | `error` | 相符 |
| 38 | 货叉轴驱动器错误，丢失“驱动器就绪”信号 | `error` | 相符 |
| 39 | 货叉轴已触发下限软限位开关 | `error` | 相符 |
| 40 | 货叉轴已触发上限软限位开关 | `error` | 相符 |
| 41 | 货叉轴已接近下限硬限位开关 | `warn` | 相符 |
| 42 | 货叉轴已接近上限硬限位开关 | `warn` | 相符 |
| 43 | 货叉轴编码器发出报警消息 | `error` | 相符 |
| 44 | 货叉轴未回原点 | `error` | 相符 |
| 45 | 货叉轴无法启用 | `error` | 相符 |
| 46 | 货叉轴未找到参考开关/编码器零位标记 | `error` | 相符 |
| 47 | 货叉轴硬限位开关错误 | `error` | 相符 |
| 48 | 3轴已复位 | `info` | 相符 |

当前ConnectionManager按完整数组提取全部非零唯一故障号，逐个产生新增/解除事件；任务失败的error_code只存数组中第一个非零故障号，完整多故障列表不等于单个任务error_code。其他未知非零号由故障解析器返回未维护兜底说明，不冒充1–48字典。

## 握手与收尾

| 原表位置 | 要求 | 结论 | 对应源码 |
| --- | --- | --- | --- |
| WCS对接流程!B46 | 联机自动、无故障、无任务前置检查 | `matches_with_info_48_issue` | `task/StackerHandshakeStateMachine.java:advance/doCheck` |
| WCS对接流程!B47 | 坐标→货口→任务号→任务类型 | `matches` | `task/StackerHandshakeStateMachine.java:doWriteParams` |
| WCS对接流程!B48/A52 | 状态0写确认1；状态1写0；状态2写2 | `matches` | `task/StackerHandshakeStateMachine.java:doConfirmExecute/doWaitComplete/doConfirmComplete` |
| WCS对接流程!B49 | 坐标、货口、任务号、任务类型、确认清0 | `matches` | `task/StackerHandshakeStateMachine.java:doClear` |
| 堆垛机标准协议!I28/J28；WCS对接流程!D40 | 60元素Int16数组，非零项按1–48释义 | `array_and_definitions_match_except_normal_48_execution_semantics` | `simulator/opcua/StackerNamespace.java/connection/ConnectionManager.java` |
| WCS对接流程!B45 | 双向每5秒心跳并监测PLC翻转超时 | `wcs_toggle_matches_plc_stagnation_detection_missing` | `adapter/plc4x/StackerOpcUaAdapter.java/connection/ConnectionManager.java` |

当前在正常握手之外加入原生任务号预约、任务版本占位、启动前命令任务号回读、完成响应先持久化、清零后确认物理空闲及重启续接。这些是执行安全/可靠性扩展，不改变原表1→0→2→0的确认及清零流程。

## 定位源码

- [task/StackerHandshakeStateMachine.java](../../backend/src/main/java/com/cayleywcs/task/StackerHandshakeStateMachine.java)
- [connection/ConnectionManager.java](../../backend/src/main/java/com/cayleywcs/connection/ConnectionManager.java)
- [adapter/plc4x/StackerOpcUaAdapter.java](../../backend/src/main/java/com/cayleywcs/adapter/plc4x/StackerOpcUaAdapter.java)
- [adapter/plc4x/Plc4xAdapter.java](../../backend/src/main/java/com/cayleywcs/adapter/plc4x/Plc4xAdapter.java)
- [adapter/PointTypeRegistry.java](../../backend/src/main/java/com/cayleywcs/adapter/PointTypeRegistry.java)
- [adapter/AbstractProtocolAdapter.java](../../backend/src/main/java/com/cayleywcs/adapter/AbstractProtocolAdapter.java)
- [simulator/StackerDeviceState.java](../../backend/src/main/java/com/cayleywcs/simulator/StackerDeviceState.java)
- [simulator/OpcUaStackerSimulator.java](../../backend/src/main/java/com/cayleywcs/simulator/OpcUaStackerSimulator.java)
- [simulator/opcua/StackerNamespace.java](../../backend/src/main/java/com/cayleywcs/simulator/opcua/StackerNamespace.java)
- [protocol/contract/PointContractValidator.java](../../backend/src/main/java/com/cayleywcs/protocol/contract/PointContractValidator.java)
- [protocol/contract/DevicePointTemplates.java](../../backend/src/main/java/com/cayleywcs/protocol/contract/DevicePointTemplates.java)
- [device/AbstractDeviceHandler.java](../../backend/src/main/java/com/cayleywcs/device/AbstractDeviceHandler.java)

| 核查依据 | 文件与行号 |
| --- | --- |
| WCS心跳5000ms | [backend\src\main\java\com\cayleywcs\adapter\plc4x\StackerOpcUaAdapter.java:14](../../backend\src\main\java\com\cayleywcs\adapter\plc4x\StackerOpcUaAdapter.java#L14) |
| 心跳仅写WCS并检查连接 | [backend\src\main\java\com\cayleywcs\adapter\plc4x\StackerOpcUaAdapter.java:32](../../backend\src\main\java\com\cayleywcs\adapter\plc4x\StackerOpcUaAdapter.java#L32) |
| 读状态区成功即刷新活跃时间 | [backend\src\main\java\com\cayleywcs\connection\ConnectionManager.java:613](../../backend\src\main\java\com\cayleywcs\connection\ConnectionManager.java#L613) |
| 全部非零均为活动故障 | [backend\src\main\java\com\cayleywcs\connection\ConnectionManager.java:434](../../backend\src\main\java\com\cayleywcs\connection\ConnectionManager.java#L434) |
| 提取数组所有非零故障 | [backend\src\main\java\com\cayleywcs\connection\ConnectionManager.java:653](../../backend\src\main\java\com\cayleywcs\connection\ConnectionManager.java#L653) |
| 任务以首个非零判败 | [backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java:53](../../backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java#L53) |
| 参数写入顺序 | [backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java:114](../../backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java#L114) |
| 执行前回读命令任务号 | [backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java:139](../../backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java#L139) |
| 执行确认1 | [backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java:155](../../backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java#L155) |
| 运行中确认0 | [backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java:174](../../backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java#L174) |
| 先采集结果再完成确认2 | [backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java:193](../../backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java#L193) |
| 补发确认2并清零且检查空闲 | [backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java:208](../../backend\src\main\java\com\cayleywcs\task\StackerHandshakeStateMachine.java#L208) |
| 模拟PLC每tick翻转心跳 | [backend\src\main\java\com\cayleywcs\simulator\StackerDeviceState.java:54](../../backend\src\main\java\com\cayleywcs\simulator\StackerDeviceState.java#L54) |
| 模拟器直接复制请求任务类型 | [backend\src\main\java\com\cayleywcs\simulator\StackerDeviceState.java:106](../../backend\src\main\java\com\cayleywcs\simulator\StackerDeviceState.java#L106) |
| Milo每一秒tick | [backend\src\main\java\com\cayleywcs\simulator\OpcUaStackerSimulator.java:106](../../backend\src\main\java\com\cayleywcs\simulator\OpcUaStackerSimulator.java#L106) |
| 故障数组60元素 | [backend\src\main\java\com\cayleywcs\simulator\opcua\StackerNamespace.java:65](../../backend\src\main\java\com\cayleywcs\simulator\opcua\StackerNamespace.java#L65) |
| 模拟命令节点允许读写 | [backend\src\main\java\com\cayleywcs\simulator\opcua\StackerNamespace.java:103](../../backend\src\main\java\com\cayleywcs\simulator\opcua\StackerNamespace.java#L103) |
| MiloUInt16 | [backend\src\main\java\com\cayleywcs\simulator\opcua\StackerNamespace.java:109](../../backend\src\main\java\com\cayleywcs\simulator\opcua\StackerNamespace.java#L109) |
| INT明确映射Int16 | [backend\src\main\java\com\cayleywcs\adapter\PointTypeRegistry.java:40](../../backend\src\main\java\com\cayleywcs\adapter\PointTypeRegistry.java#L40) |
| UINT明确映射UInt16 | [backend\src\main\java\com\cayleywcs\adapter\PointTypeRegistry.java:41](../../backend\src\main\java\com\cayleywcs\adapter\PointTypeRegistry.java#L41) |
| 有效方向坐标语义校验 | [backend\src\main\java\com\cayleywcs\protocol\contract\PointContractValidator.java:149](../../backend\src\main\java\com\cayleywcs\protocol\contract\PointContractValidator.java#L149) |
| 原生值域发布校验 | [backend\src\main\java\com\cayleywcs\protocol\contract\PointContractValidator.java:45](../../backend\src\main\java\com\cayleywcs\protocol\contract\PointContractValidator.java#L45) |

机器证据：[2026-10-13-stacker-opcua-conformance.json](2026-10-13-stacker-opcua-conformance.json)。

建议先用当前模拟器完成WMS实际故障接收验证，再分别修复PLC心跳停滞检测与正常复位48处理；现场任务号类型、反馈3/4含义、6的反馈枚举、真实NodeId和命令任务号读权限需要依据PLC实际节点核对后发布。不要静默改原始工作簿。

## 后续实际链路验证

主任务已用WMS到真正OPC UA模拟堆垛机复现PLC心跳冻结不告警、48正常复位提示判失败，以及多故障只取第一个号码。48个号码逐项传递全部通过，完整故障信息仍未进入WMS。实际记录见[WMS故障实验报告](2026-10-13-wms-stacker-fault-report.md)与[机器证据](2026-10-13-wms-stacker-fault-evidence.json)。
