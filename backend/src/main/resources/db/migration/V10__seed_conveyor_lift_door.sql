-- M9 新设备类型仿真种子：输送线/电梯/自动门三套 sim 协议 + 点位 + 故障码 + 仿真应用 + 上位授权绑定。
-- protocol_type='sim' → SimAdapter 直连内存仿真（DeviceSimFactory 按 device_type 建模），无硬件即可端到端联调。
-- 幂等守卫：协议/应用/绑定用 not-exists；点位/故障码为随新协议一次性写入的裸 VALUES（同 V3 惯例，
-- 依赖协议为本迁移新建=无既有子行）。所有 protocol_code 子查询均带 tenant_id=1，避免跨租户同名协议致子查询多行。

-- ============ 输送线/滚筒线（conveyor）============
insert into "wcs_protocol" ("protocol_code", "protocol_name", "target_system", "protocol_type",
    "data_format", "transport_template", "version", "status", "remark", "creator", "tenant_id")
select 'CONVEYOR_SIM', '输送线仿真协议', '输送线PLC', 'sim',
    '{"access":"sim","semantics":"目的站台+启动，到位 status_Task=2"}',
    '{"transport":"sim","deviceType":"conveyor"}', '1.0', 'enabled', 'M9 输送线仿真', 'system', 1
where not exists (select 1 from "wcs_protocol" where "protocol_code" = 'CONVEYOR_SIM' and "tenant_id" = 1);

insert into "wcs_protocol_point"
    ("protocol_id", "point_group", "field_name", "symbol_name", "address", "data_type", "rw", "value_range", "sort", "description", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'From_WCS', 'WCS_Heart', 'WCS心跳', 'WCS_Heart', 'INT', 'W', '0/1', 0, 'WCS心跳', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_DestStation', '命令_目的站台', 'cmd_DestStation', 'INT', 'W', '', 1, '目的站台号', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_TaskNum', '命令_任务号', 'cmd_TaskNum', 'INT', 'W', '1-65534', 2, '任务号', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_Start', '命令_启动', 'cmd_Start', 'INT', 'W', '0/1', 3, '1启动运输，到位后写0清零', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_ResetFault', '命令_异常处理', 'cmd_ResetFault', 'INT', 'W', '0/1', 4, '故障复位', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'To_WCS', 'PLC_Heart', 'PLC心跳', 'PLC_Heart', 'INT', 'R', '0/1', 100, 'PLC心跳', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_Mode', '状态_运行模式', 'status_Mode', 'INT', 'R', '2联机自动', 101, '运行模式，联机自动=2', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_Task', '状态_任务状态', 'status_Task', 'INT', 'R', '0空闲1运行2到位', 102, '任务状态', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_TaskNum', '状态_任务号', 'status_TaskNum', 'INT', 'R', '1-65534', 103, '当前任务号', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_Position', '状态_当前站台', 'status_Position', 'INT', 'R', '', 104, '当前站台', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_Occupied', '状态_占用', 'status_Occupied', 'INT', 'R', '0空1有料', 105, '线体占用', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_ErrorCode', '状态_故障代码', 'status_ErrorCode', 'INT', 'R', '0无', 106, '故障代码(0无)', 'system', 1);

insert into "wcs_fault_code" ("protocol_id", "code", "level", "name", "message", "suggestion", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 1, 'error', '光电堵料', '输送线光电检测堵料', '清理线体物料', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 2, 'error', '变频器故障', '输送线变频器报警', '检查变频器', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 3, 'warn', '皮带跑偏', '输送带跑偏检测触发', '检查张紧与纠偏', 'system', 1);

insert into "wcs_application" ("app_code", "app_name", "app_key", "app_secret", "protocol_id", "direction", "device_type",
    "conn_params", "max_retry", "heartbeat_interval_ms", "enabled", "status", "remark", "creator", "tenant_id")
select 'CONVEYOR_SIM', '输送线（仿真）', 'ak_conveyor_sim', 'secret_conveyor_sim',
    (select "id" from "wcs_protocol" where "protocol_code"='CONVEYOR_SIM' and "tenant_id"=1), 'downstream', 'conveyor',
    '{}', 5, 1000, true, 'idle', '内存仿真输送线', 'system', 1
where not exists (select 1 from "wcs_application" where "app_code" = 'CONVEYOR_SIM' and "tenant_id" = 1);

-- ============ 提升机/电梯（lift）============
insert into "wcs_protocol" ("protocol_code", "protocol_name", "target_system", "protocol_type",
    "data_format", "transport_template", "version", "status", "remark", "creator", "tenant_id")
select 'LIFT_SIM', '电梯仿真协议', '电梯PLC', 'sim',
    '{"access":"sim","semantics":"目标层+启动，到层开门 status_Task=2/status_DoorState=1"}',
    '{"transport":"sim","deviceType":"lift"}', '1.0', 'enabled', 'M9 电梯仿真', 'system', 1
where not exists (select 1 from "wcs_protocol" where "protocol_code" = 'LIFT_SIM' and "tenant_id" = 1);

insert into "wcs_protocol_point"
    ("protocol_id", "point_group", "field_name", "symbol_name", "address", "data_type", "rw", "value_range", "sort", "description", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'From_WCS', 'WCS_Heart', 'WCS心跳', 'WCS_Heart', 'INT', 'W', '0/1', 0, 'WCS心跳', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_TargetFloor', '命令_目标层', 'cmd_TargetFloor', 'INT', 'W', '', 1, '目标楼层', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_TaskNum', '命令_任务号', 'cmd_TaskNum', 'INT', 'W', '1-65534', 2, '任务号', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_Go', '命令_启动', 'cmd_Go', 'INT', 'W', '0/1', 3, '1启动运行，到层后写0关门离开', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_ResetFault', '命令_异常处理', 'cmd_ResetFault', 'INT', 'W', '0/1', 4, '故障复位', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'To_WCS', 'PLC_Heart', 'PLC心跳', 'PLC_Heart', 'INT', 'R', '0/1', 100, 'PLC心跳', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'To_WCS', 'status_Mode', '状态_运行模式', 'status_Mode', 'INT', 'R', '2联机自动', 101, '运行模式，联机自动=2', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'To_WCS', 'status_Task', '状态_任务状态', 'status_Task', 'INT', 'R', '0空闲1运行2到层', 102, '任务状态', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'To_WCS', 'status_TaskNum', '状态_任务号', 'status_TaskNum', 'INT', 'R', '1-65534', 103, '当前任务号', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'To_WCS', 'status_CurrentFloor', '状态_当前层', 'status_CurrentFloor', 'INT', 'R', '', 104, '当前楼层', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'To_WCS', 'status_DoorState', '状态_门状态', 'status_DoorState', 'INT', 'R', '0关1开', 105, '门状态', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'To_WCS', 'status_ErrorCode', '状态_故障代码', 'status_ErrorCode', 'INT', 'R', '0无', 106, '故障代码(0无)', 'system', 1);

insert into "wcs_fault_code" ("protocol_id", "code", "level", "name", "message", "suggestion", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 1, 'error', '门联锁未闭合', '电梯门联锁未闭合', '检查门联锁', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 2, 'error', '超限位', '电梯超行程限位', '检查限位与位置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 3, 'error', '变频器故障', '电梯变频器报警', '检查变频器', 'system', 1);

insert into "wcs_application" ("app_code", "app_name", "app_key", "app_secret", "protocol_id", "direction", "device_type",
    "conn_params", "max_retry", "heartbeat_interval_ms", "enabled", "status", "remark", "creator", "tenant_id")
select 'LIFT_SIM', '电梯（仿真）', 'ak_lift_sim', 'secret_lift_sim',
    (select "id" from "wcs_protocol" where "protocol_code"='LIFT_SIM' and "tenant_id"=1), 'downstream', 'lift',
    '{}', 5, 1000, true, 'idle', '内存仿真电梯', 'system', 1
where not exists (select 1 from "wcs_application" where "app_code" = 'LIFT_SIM' and "tenant_id" = 1);

-- ============ 自动门（auto_door）============
insert into "wcs_protocol" ("protocol_code", "protocol_name", "target_system", "protocol_type",
    "data_format", "transport_template", "version", "status", "remark", "creator", "tenant_id")
select 'DOOR_SIM', '自动门仿真协议', '自动门PLC', 'sim',
    '{"access":"sim","semantics":"开/关命令，到位 status_DoorState=1开/0关"}',
    '{"transport":"sim","deviceType":"auto_door"}', '1.0', 'enabled', 'M9 自动门仿真', 'system', 1
where not exists (select 1 from "wcs_protocol" where "protocol_code" = 'DOOR_SIM' and "tenant_id" = 1);

insert into "wcs_protocol_point"
    ("protocol_id", "point_group", "field_name", "symbol_name", "address", "data_type", "rw", "value_range", "sort", "description", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'From_WCS', 'WCS_Heart', 'WCS心跳', 'WCS_Heart', 'INT', 'W', '0/1', 0, 'WCS心跳', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_Open', '命令_开门', 'cmd_Open', 'INT', 'W', '0/1', 1, '1开门', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_Close', '命令_关门', 'cmd_Close', 'INT', 'W', '0/1', 2, '1关门', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'From_WCS', 'cmd_ResetFault', '命令_异常处理', 'cmd_ResetFault', 'INT', 'W', '0/1', 3, '故障复位', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'To_WCS', 'PLC_Heart', 'PLC心跳', 'PLC_Heart', 'INT', 'R', '0/1', 100, 'PLC心跳', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_Mode', '状态_运行模式', 'status_Mode', 'INT', 'R', '2联机自动', 101, '运行模式，联机自动=2', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_DoorState', '状态_门状态', 'status_DoorState', 'INT', 'R', '0关1开2开门中3关门中', 102, '门状态', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'To_WCS', 'status_ErrorCode', '状态_故障代码', 'status_ErrorCode', 'INT', 'R', '0无', 103, '故障代码(0无)', 'system', 1);

insert into "wcs_fault_code" ("protocol_id", "code", "level", "name", "message", "suggestion", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 1, 'error', '障碍物检测', '门口障碍物检测触发', '清除门口障碍物', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 2, 'error', '门机构卡阻', '门机构运动卡阻', '检查门机构', 'system', 1);

insert into "wcs_application" ("app_code", "app_name", "app_key", "app_secret", "protocol_id", "direction", "device_type",
    "conn_params", "max_retry", "heartbeat_interval_ms", "enabled", "status", "remark", "creator", "tenant_id")
select 'DOOR_SIM', '自动门（仿真）', 'ak_door_sim', 'secret_door_sim',
    (select "id" from "wcs_protocol" where "protocol_code"='DOOR_SIM' and "tenant_id"=1), 'downstream', 'auto_door',
    '{}', 5, 1000, true, 'idle', '内存仿真自动门', 'system', 1
where not exists (select 1 from "wcs_application" where "app_code" = 'DOOR_SIM' and "tenant_id" = 1);

-- ============ 上位授权（开箱即用）============
-- V7 只授权了当时存量下位设备，新设备需补授权，否则 WMS-MAIN 经 /open 下发即被越权防护 FORBIDDEN。
-- 无 WMS-MAIN（未跑 V7）时 cross join 为空、自动跳过。
insert into "wcs_app_binding"
    ("upstream_app_id", "downstream_app_id", "scope", "enabled", "remark", "creator", "tenant_id")
select up."id", dev."id", 'dispatch', true, 'M9 授权（WMS-MAIN → 新设备）', 'system', 1
from "wcs_application" up
cross join "wcs_application" dev
where up."app_code" = 'WMS-MAIN' and up."tenant_id" = 1
  and dev."app_code" in ('CONVEYOR_SIM', 'LIFT_SIM', 'DOOR_SIM') and dev."tenant_id" = 1 and dev."is_valid" = true
  and not exists (
      select 1 from "wcs_app_binding" b
      where b."upstream_app_id" = up."id" and b."downstream_app_id" = dev."id" and b."tenant_id" = 1
  );
