-- 种子：堆垛机标准协议（《堆垛机通讯协议 -6.18改.xlsx》修订版）+ 点位(DBW0-182) + 故障码 1-48 + 仿真器 demo 应用。
-- 6.18 修订要点：地址重排(状态区起于 DBW24)；移除 cmd_Mode/AutoStep/FaultWord/Port；
-- status_Mode 精简为 0断电/1单机手动/2联机自动/3维护/4上电；检查阶段判【联机自动】；
-- 列 1-7、层 1-9、排 1-2；任务类型 0无/1入库/2出库/3移库/4移动/5急停/6解警；
-- 测距 Walk/Lift 命名交叉已修正；故障码改为 ARRAY[1..60] OF INT，含义 1-48。

insert into "wcs_protocol" ("protocol_code", "protocol_name", "target_system", "protocol_type",
    "data_format", "transport_template", "version", "status", "remark", "creator", "tenant_id")
select 'STACKER_STD', '堆垛机标准协议', '堆垛机PLC', 'opcua',
    '{"block":"DB100","command":"From_WCS(DBW0-22)","status":"To_WCS(DBW24-182)","heartbeat":"0/1 every 5s","faultArray":"status_ErrorCode ARRAY[1..60] OF INT @DB100.DBW64-DBW182"}',
    '{"transport":"opcua","addressing":"S7 DB100 word offset mapped to OPC UA NodeId"}',
    '2.0', 'enabled', '6.18 修订：地址重排/模式精简/故障码改数组(1-48)/移除 cmd_Mode/AutoStep/FaultWord/Port', 'system', 1
where not exists (select 1 from "wcs_protocol" where "protocol_code" = 'STACKER_STD' and "tenant_id" = 1);

-- 点位（命令区 From_WCS / 状态区 To_WCS）。protocol_id 由子查询解析。
insert into "wcs_protocol_point"
    ("protocol_id", "point_group", "field_name", "symbol_name", "address", "data_type", "rw", "value_range", "sort", "description", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'WCS_Heart', 'WCS_Heartbeat', 'DB100.DBW0', 'INT', 'W', '0/1', 0, 'WCS心跳，0↔1每5秒交替', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_TaskType', '命令_任务类型', 'DB100.DBW2', 'INT', 'W', '0无1入库2出库3移库4移动5急停6解警', 1, '4移动需取货列/层确定位置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_ResetFault', '命令_异常处理', 'DB100.DBW4', 'INT', 'W', '', 2, '异常处理命令', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_TakeCoor_Row', '命令_取货地址排', 'DB100.DBW6', 'INT', 'W', '1-2', 3, '取货排', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_TakeCoor_Column', '命令_取货地址列', 'DB100.DBW8', 'INT', 'W', '1-7', 4, '取货列', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_TakeCoor_Floor', '命令_取货地址层', 'DB100.DBW10', 'INT', 'W', '1-9', 5, '取货层', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_PutCoor_Row', '命令_放货地址排', 'DB100.DBW12', 'INT', 'W', '1-2', 6, '放货排', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_PutCoor_Column', '命令_放货地址列', 'DB100.DBW14', 'INT', 'W', '1-7', 7, '放货列', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_PutCoor_Floor', '命令_放货地址层', 'DB100.DBW16', 'INT', 'W', '1-9', 8, '放货层', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_PortNum', '命令_取放货口', 'DB100.DBW18', 'INT', 'W', '1-2', 9, '凯福升替换项目只有一条输送线，只发1', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_TaskNum', '命令_任务号', 'DB100.DBW20', 'INT', 'W', '1-65534', 10, '任务号', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'From_WCS', 'cmd_ConfirmTask', '命令_任务确认', 'DB100.DBW22', 'INT', 'W', '0无意义1执行任务2任务完成确认', 11, '任务确认', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'PLC_Heart', 'PLC_Heartbeat', 'DB100.DBW24', 'INT', 'R', '0/1', 100, 'PLC心跳，0↔1每5秒交替', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_Mode', '状态_运行模式', 'DB100.DBW26', 'INT', 'R', '0断电1单机手动2联机自动3维护模式4上电', 101, '运行模式状态；联机自动=2', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_Task', '状态_任务状态', 'DB100.DBW28', 'INT', 'R', '0无任务1执行任务2任务完成', 102, '任务状态', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_TaskTypeFeedback', '状态_任务类型', 'DB100.DBW30', 'INT', 'R', '0待机1入库2出库3移动4转库5急停', 103, '任务类型回读', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_CurrentColumnNum', '状态_当前列', 'DB100.DBW32', 'INT', 'R', '1-7', 104, '当前列', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_CurrentFloorNum', '状态_当前层', 'DB100.DBW34', 'INT', 'R', '1-9', 105, '当前层', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_Speed_Lift', '状态_提升速度', 'DB100.DBD36', 'REAL', 'R', 'mm/s', 106, '提升速度', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_Speed_Walk', '状态_行走速度', 'DB100.DBD40', 'REAL', 'R', 'mm/s', 107, '行走速度', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_Speed_Fork', '状态_货叉速度', 'DB100.DBD44', 'REAL', 'R', 'mm/s', 108, '货叉速度', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_CurrentPos_Walk', '状态_行走方向测距', 'DB100.DBD48', 'REAL', 'R', 'mm', 109, '行走方向测距', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_CurrentPos_Lift', '状态_提升方向测距', 'DB100.DBD52', 'REAL', 'R', 'mm', 110, '提升方向测距', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_CurrentPos_Fork', '状态_货叉方向测距', 'DB100.DBD56', 'REAL', 'R', 'mm', 111, '货叉方向测距', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_TaskNum', '状态_任务号', 'DB100.DBW60', 'INT', 'R', '1-65534', 112, '当前任务号', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_Cargo', '状态_货物状态', 'DB100.DBW62', 'INT', 'R', '1空/2有货', 113, '载货台货物状态', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'To_WCS', 'status_ErrorCode', '状态_故障代码', 'DB100.DBW64', 'ARRAY[1..60] OF INT', 'R', '1-48 故障码数组(DBW64-DBW182)', 114, '故障代码数组，元素值为 1-48 故障码，0 表示无', 'system', 1);

-- 故障码 1-48（6.18 修订版）
insert into "wcs_fault_code" ("protocol_id", "code", "level", "name", "message", "suggestion", "creator", "tenant_id")
values
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 1, 'error', '行走轴误差过大', '行走轴误差过大，请在下限位校准回原', '在下限位校准回原点', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 2, 'error', '断带检测1触发', '断带检测1触发', '检查提升钢带/链条', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 3, 'error', '断带检测2触发', '断带检测2触发', '检查提升钢带/链条', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 4, 'warn', '货仓超长检测1触发', '货仓超长检测1触发', '检查货物外形是否超长', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 5, 'warn', '货仓超长检测2触发', '货仓超长检测2触发', '检查货物外形是否超长', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 6, 'warn', '货仓超高检测1触发', '货仓超高检测1触发', '检查货物外形是否超高', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 7, 'warn', '货仓超高检测2触发', '货仓超高检测2触发', '检查货物外形是否超高', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 8, 'warn', '里库位检测1触发', '里库位检测1触发，库位已占有', '该库位已有货，改派或改双重入库', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 9, 'warn', '里库位检测2触发', '里库位检测2触发，库位已占有', '该库位已有货，改派或改双重入库', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 10, 'warn', '货仓超宽检测1触发', '货仓超宽检测1触发', '检查货物外形是否超宽', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 11, 'warn', '货仓超宽检测2触发', '货仓超宽检测2触发', '检查货物外形是否超宽', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 12, 'warn', '货仓超宽检测3触发', '货仓超宽检测3触发', '检查货物外形是否超宽', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 13, 'warn', '货仓超宽检测4触发', '货仓超宽检测4触发', '检查货物外形是否超宽', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 14, 'warn', '货仓货物检测未触发', '货仓货物检测未触发，无货物', '确认载货台是否确实有货', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 15, 'warn', '货仓货物检测触发', '货仓货物检测触发，货物还在货仓', '确认货物是否已移出载货台', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 16, 'warn', '里库位检测1未触发', '里库位检测1未触发，库位无货物', '出库前确认库位有货', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 17, 'warn', '里库位检测2未触发', '里库位检测2未触发，库位无货物', '出库前确认库位有货', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 18, 'error', '平移轴驱动器错误', '平移轴驱动器错误，丢失驱动器就绪信号', '检查平移轴驱动器', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 19, 'error', '平移轴下限软限位', '平移轴已触发下限软限位开关', '检查平移轴位置与软限位设置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 20, 'error', '平移轴上限软限位', '平移轴已触发上限软限位开关', '检查平移轴位置与软限位设置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 21, 'warn', '平移轴接近下限硬限位', '平移轴已接近下限硬限位开关', '减速并检查平移轴行程', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 22, 'warn', '平移轴接近上限硬限位', '平移轴已接近上限硬限位开关', '减速并检查平移轴行程', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 23, 'error', '平移轴编码器报警', '平移轴编码器发出报警消息', '检查平移轴编码器', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 24, 'error', '平移轴未回原点', '平移轴未回原点', '执行平移轴回原点', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 25, 'error', '平移轴无法启用', '平移轴无法启用', '检查平移轴使能条件', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 26, 'error', '平移轴未找到参考点', '平移轴未找到参考开关/编码器零位标记', '检查平移轴参考开关与零位', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 27, 'error', '平移轴硬限位开关错误', '平移轴硬限位开关错误', '检查平移轴硬限位接线', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 28, 'error', '升降轴驱动器错误', '升降轴驱动器错误，丢失驱动器就绪信号', '检查升降轴驱动器', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 29, 'error', '升降轴下限软限位', '升降轴已触发下限软限位开关', '检查升降轴位置与软限位设置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 30, 'error', '升降轴上限软限位', '升降轴已触发上限软限位开关', '检查升降轴位置与软限位设置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 31, 'warn', '升降轴接近下限硬限位', '升降轴已接近下限硬限位开关', '减速并检查升降轴行程', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 32, 'warn', '升降轴接近上限硬限位', '升降轴已接近上限硬限位开关', '减速并检查升降轴行程', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 33, 'error', '升降轴编码器报警', '升降轴编码器发出报警消息', '检查升降轴编码器', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 34, 'error', '升降轴未回原点', '升降轴未回原点', '执行升降轴回原点', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 35, 'error', '升降轴无法启用', '升降轴无法启用', '检查升降轴使能条件', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 36, 'error', '升降轴未找到参考点', '升降轴未找到参考开关/编码器零位标记', '检查升降轴参考开关与零位', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 37, 'error', '升降轴硬限位开关错误', '升降轴硬限位开关错误', '检查升降轴硬限位接线', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 38, 'error', '货叉轴驱动器错误', '货叉轴驱动器错误，丢失驱动器就绪信号', '检查货叉轴驱动器', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 39, 'error', '货叉轴下限软限位', '货叉轴已触发下限软限位开关', '检查货叉轴位置与软限位设置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 40, 'error', '货叉轴上限软限位', '货叉轴已触发上限软限位开关', '检查货叉轴位置与软限位设置', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 41, 'warn', '货叉轴接近下限硬限位', '货叉轴已接近下限硬限位开关', '减速并检查货叉轴行程', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 42, 'warn', '货叉轴接近上限硬限位', '货叉轴已接近上限硬限位开关', '减速并检查货叉轴行程', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 43, 'error', '货叉轴编码器报警', '货叉轴编码器发出报警消息', '检查货叉轴编码器', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 44, 'error', '货叉轴未回原点', '货叉轴未回原点', '执行货叉轴回原点', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 45, 'error', '货叉轴无法启用', '货叉轴无法启用', '检查货叉轴使能条件', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 46, 'error', '货叉轴未找到参考点', '货叉轴未找到参考开关/编码器零位标记', '检查货叉轴参考开关与零位', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 47, 'error', '货叉轴硬限位开关错误', '货叉轴硬限位开关错误', '检查货叉轴硬限位接线', 'system', 1),
    ((select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 48, 'info', '3轴已复位', '3轴已复位', '正常状态，3 轴已回原点', 'system', 1);

-- 仿真器 demo 应用（固定 app_key/app_secret 便于联调）
insert into "wcs_application" ("app_code", "app_name", "app_key", "app_secret", "protocol_id", "direction",
    "conn_params", "max_retry", "heartbeat_interval_ms", "enabled", "status", "remark", "creator", "tenant_id")
select 'STACKER01', '堆垛机1#（仿真）', 'ak_stacker_demo', 'secret_stacker_demo',
    (select "id" from "wcs_protocol" where "protocol_code"='STACKER_STD'), 'downstream',
    '{"endpoint":"opc.tcp://127.0.0.1:24840/cayleywcs/stacker","namespaceUri":"urn:cayleywcs:stacker"}',
    5, 5000, true, 'idle', '指向内置 OPC UA 仿真器', 'system', 1
where not exists (select 1 from "wcs_application" where "app_code" = 'STACKER01' and "tenant_id" = 1);
