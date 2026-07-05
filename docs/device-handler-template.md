# 新设备类型接入指南（设备类型 SPI）

CayleyWCS 的设备交互能力经 **设备类型 SPI**（`com.cayleywcs.device`）可插拔扩展：新增一种设备（穿梭车/机械手/打包机/…）**零改任务引擎**，只需三步——加 handler、加仿真模型、加种子。M9 的输送线/电梯/自动门即按此模板落地，可直接参照。

## 架构分层

```
TaskEngine（每设备单飞，按 device_type 路由）
    └─ DeviceHandlerRegistry.find(device_type) → DeviceHandler.advance(task)   ← 业务：握手/命令时序
ConnectionManager.readPoint/writePoint(appId, field)
    └─ ProtocolAdapter（sim / opcua / modbus_tcp / s7 / mqtt / http / tcp）    ← 传输
        └─（sim）DeviceSimFactory.supplier(device_type) → DeviceSim            ← 无硬件仿真
```

- 路由键：`wcs_application.device_type`（字典 `device_type`）。
- 任务参数：统一走 `wcs_task.payload`（JSON），**不为新设备加专用列**。
- 点位语义：`wcs_protocol` + `wcs_protocol_point`（field_name ↔ 设备寄存器）。

## 三步接入

### 1. 加 DeviceHandler（业务状态机）

新建 `device/<type>/XxxDeviceHandler.java`，继承 `AbstractDeviceHandler`：

```java
@Component
public class ShuttleDeviceHandler extends AbstractDeviceHandler {
    private enum Step { CREATED, CHECKING, MOVING, DONE, FAILED } // 终态须含 DONE/FAILED

    public ShuttleDeviceHandler(ConnectionManager cm) { super(cm); }

    @Override public String deviceType() { return "shuttle"; }
    // 一个 handler 服务多类型时覆写 deviceTypes()（如堆垛机 handler 兼服码垛机）

    @Override public boolean advance(TaskEntity task) {
        return advanceGuarded(task, (t, appId, step) -> switch (Step.valueOf(step)) {
            case CREATED  -> check(t, appId);
            case CHECKING -> start(t, appId);
            case MOVING   -> waitDone(t, appId);
            case DONE, FAILED -> { resetStall(t); yield false; }
        });
    }
}
```

基类已提供并**必须复用**的加固能力（M8 对抗审查沉淀，勿绕过）：

| 能力 | 方法 | 作用 |
|---|---|---|
| 故障闸 + 传输异常放弃 | `advanceGuarded(task, dispatcher)` | 非终态读到 `status_ErrorCode≠0` 即 fail；连接拆除/读写超时/停机中断只放弃本步不落终态 |
| 占位提交（cancel 竞态） | `claim(task)` | 对设备发不可回滚写（启动/确认）**前**调用；返回 false（并发取消赢了）则本步废弃、设备零写入 |
| 死等检测 | `bumpStall(task)` / `resetStall(task)` / `STALL_LIMIT` | 等待期间设备意外回空闲连续 N 轮 → fail，避免无限空转饿死队列 |
| 参数取值 | `payloadLong/payloadString(task, key, def)` | 从 `task.payload` 取任务参数 |
| 点位读写 | `readInt/readLong/write/readFirstFaultCode` | 经 ConnectionManager，故障码支持标量(仿真)与数组(真实PLC) |

**启动步范式**（务必照抄占位顺序）：

```java
private boolean start(TaskEntity t, Long appId) {
    write(appId, "cmd_Dest", payloadLong(t, "dest", 0));   // 参数（未启动，可重复无副作用）
    write(appId, "cmd_TaskNum", taskNum(t));
    t.setHandshake_step(Step.MOVING.name());
    t.setStatus(TaskStatus.EXECUTING);
    if (!claim(t)) { t.setHandshake_step(Step.CHECKING.name()); return true; } // 占位失败→回退，不启动
    write(appId, "cmd_Start", 1);                          // 不可回滚的物理启动
    return true;
}
```

### 2. 加内存仿真模型（无硬件联调）

新建 `simulator/XxxDeviceState.java`，继承 `AbstractDeviceSim`，只写命令副作用与节拍推进：

```java
public class ShuttleDeviceState extends AbstractDeviceSim {
    private int taskTicks = 0;
    public ShuttleDeviceState() {
        store.put("status_Mode", 2);       // 联机自动
        store.put("status_Task", 0);
        store.put("status_ErrorCode", 0);  // 故障闸依赖此点位，必须有
        store.put("PLC_Heart", 0);
    }
    @Override protected void onSet(String field, int v) {   // cmd_ResetFault 基类已处理
        if ("cmd_Start".equals(field) && v == 1 && intVal("status_Task") == 0) {
            store.put("status_Task", 1); store.put("status_TaskNum", intVal("cmd_TaskNum")); taskTicks = 0;
        } else if ("cmd_Start".equals(field) && v == 0 && intVal("status_Task") == 2) {
            store.put("status_Task", 0);
        }
    }
    @Override protected void onTick() {
        if (intVal("status_Task") == 1 && ++taskTicks >= 2) { store.put("status_Task", 2); taskTicks = 0; }
    }
}
```

在 `DeviceSimFactory.supplier(deviceType)` 的 switch 中登记：`case "shuttle" -> ShuttleDeviceState::new;`

### 3. 加种子（协议 + 点位 + 故障码 + 应用）

新建 Flyway 迁移 `V<n>__seed_shuttle.sql`（照抄 `V10__seed_conveyor_lift_door.sql`），全部 `not-exists` 守卫幂等：

- `wcs_protocol`：`protocol_type='sim'`（无硬件）；接真实设备时改 `opcua/modbus_tcp/s7/...` 并配 `conn_params`。
- `wcs_protocol_point`：`field_name` 必须与 handler/仿真里的点位名逐一对齐；**必含 `status_ErrorCode`、`status_Mode`**。
- `wcs_fault_code`：设备故障码 → 报警联动（未维护走统一兜底）。
- `wcs_application`：`device_type='shuttle'`（保存时校验必须有已注册 handler）+ AppKey。

字典 `device_type` 已在 V9 预留 shuttle/robot_arm/packer/agv 项（agv/shuttle 备注电量语义 `status_Battery`，充电调度后置）。

## 验证

参照 `test/java/com/cayleywcs/device/NewDeviceSimEndToEndTest.java`：`ConnectionManager` + `SimAdapterProvider(registry, factory)` 建连 → `handler.advance()` 循环推进（`device.tick()` 模拟 PLC 进度）→ 断言到 `completed`；`device.injectFault(code)` → 断言 `failed`。V10 种子落地断言见 `V10SeedTest`。

> 已预留模板未落地：**穿梭车（shuttle）/机械手（robot_arm）/打包机（packer）**——按上述三步即可接入；穿梭车含电量语义，可与 AGV 充电策略统筹（后置）。
