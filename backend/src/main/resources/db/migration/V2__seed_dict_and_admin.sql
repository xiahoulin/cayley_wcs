-- 种子：管理员账号 + 数据字典（协议类型/方向/任务/连接状态/报警等枚举）。

-- 管理员（admin / 1），与 CayleyWMS 一致，便于联调。
insert into "wcs_userrole" ("role_name", "is_valid", "tenant_id")
select 'admin', true, 1
where not exists (select 1 from "wcs_userrole" where "role_name" = 'admin' and "tenant_id" = 1);

insert into "wcs_user" ("user_num", "user_name", "user_role", "auth_string", "is_valid", "creator", "tenant_id")
select 'admin', 'admin', 'admin', '1', true, 'system', 1
where not exists (select 1 from "wcs_user" where "user_name" = 'admin' and "tenant_id" = 1);

-- 字典类型
insert into "wcs_dict_type" ("type_code", "type_name", "sort") values
    ('protocol_type', '协议类型', 1),
    ('app_direction', '对接方向', 2),
    ('fault_level', '故障级别', 3),
    ('alarm_status', '报警状态', 4),
    ('task_type', '任务类型', 5),
    ('task_status', '任务状态', 6),
    ('conn_state', '连接状态', 7),
    ('point_rw', '点位读写', 8),
    ('point_group', '点位分组', 9);

-- 协议类型（驱动 ProtocolAdapterFactory）
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('protocol_type', 'opcua', 'OPC UA', 'opcua', 1),
    ('protocol_type', 'modbus_tcp', 'Modbus TCP', 'modbus_tcp', 2),
    ('protocol_type', 's7', '西门子 S7', 's7', 3),
    ('protocol_type', 'tcp', '原始 TCP', 'tcp', 4),
    ('protocol_type', 'mqtt', 'MQTT', 'mqtt', 5),
    ('protocol_type', 'http', 'HTTP/REST', 'http', 6),
    ('protocol_type', 'sim', '仿真设备', 'sim', 7);

-- 对接方向
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('app_direction', 'upstream', '上位（WMS/MES/ERP）', 'upstream', 1),
    ('app_direction', 'downstream', '下位（PLC/设备）', 'downstream', 2);

-- 故障级别
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('fault_level', 'info', '提示', 'info', 1),
    ('fault_level', 'warn', '警告', 'warn', 2),
    ('fault_level', 'error', '错误', 'error', 3),
    ('fault_level', 'fatal', '严重', 'fatal', 4);

-- 报警状态
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('alarm_status', 'raised', '已产生', 'raised', 1),
    ('alarm_status', 'ack', '已确认', 'ack', 2),
    ('alarm_status', 'cleared', '已清除', 'cleared', 3);

-- 任务类型（堆垛机标准协议 cmd_TaskType，6.18 修订版）
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('task_type', 'none', '无任务', '0', 0),
    ('task_type', 'inbound', '入库', '1', 1),
    ('task_type', 'outbound', '出库', '2', 2),
    ('task_type', 'relocate', '移库', '3', 3),
    ('task_type', 'move', '移动', '4', 4),
    ('task_type', 'estop', '急停', '5', 5),
    ('task_type', 'reset', '解警', '6', 6);

-- 任务状态（WCS 内部生命周期）
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('task_status', 'pending', '待调度', 'pending', 1),
    ('task_status', 'dispatched', '已下发', 'dispatched', 2),
    ('task_status', 'executing', '执行中', 'executing', 3),
    ('task_status', 'completed', '已完成', 'completed', 4),
    ('task_status', 'failed', '失败', 'failed', 5),
    ('task_status', 'cancelled', '已取消', 'cancelled', 6);

-- 连接状态机
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('conn_state', 'NEW', '新建', 'NEW', 1),
    ('conn_state', 'CONNECTING', '连接中', 'CONNECTING', 2),
    ('conn_state', 'CONNECTED', '已连接', 'CONNECTED', 3),
    ('conn_state', 'RUNNING', '运行中', 'RUNNING', 4),
    ('conn_state', 'DISCONNECTED', '已断开', 'DISCONNECTED', 5),
    ('conn_state', 'RECONNECTING', '重连中', 'RECONNECTING', 6),
    ('conn_state', 'FAILED', '失败', 'FAILED', 7),
    ('conn_state', 'CLOSED', '已关闭', 'CLOSED', 8);

-- 点位读写 / 分组
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort") values
    ('point_rw', 'R', '只读', 'R', 1),
    ('point_rw', 'W', '只写', 'W', 2),
    ('point_rw', 'RW', '读写', 'RW', 3),
    ('point_group', 'From_WCS', '命令区（WCS→PLC）', 'From_WCS', 1),
    ('point_group', 'To_WCS', '状态区（PLC→WCS）', 'To_WCS', 2);
