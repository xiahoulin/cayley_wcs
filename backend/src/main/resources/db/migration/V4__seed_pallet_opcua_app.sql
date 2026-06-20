-- 新增「礁盘工业码垛机」OPC UA 协议 + 应用（堆垛机通讯协议-opcua对接版）。
-- 数据与 STACKER_STD(6.18) 一致（DB100 内存映射），区别在于以 OPC UA NodeId 寻址：
-- 西门子 S7-1500 内置 OPC UA，NodeId 形如 ns=3;s="WCS_Task"."From_WCS"."<字段>"。
-- 协议类型 opcua → 由 StackerOpcUaAdapter 处理（含 WCS_Heart 主动心跳）。

-- 1) 协议
insert into "wcs_protocol" ("protocol_code", "protocol_name", "target_system", "protocol_type",
    "data_format", "transport_template", "version", "status", "remark", "creator", "tenant_id")
select 'STACKER_OPCUA', '礁盘工业码垛机 OPC UA 协议', '礁盘工业码垛机PLC', 'opcua',
    '{"block":"DB100","access":"opcua","addressing":"OPC UA NodeId (Siemens symbolic)"}',
    '{"transport":"opcua","nodeIdPattern":"ns=3;s=\"WCS_Task\".\"<group>\".\"<field>\""}',
    '1.0', 'enabled', 'OPC UA 对接西门子 DB100；三段握手 + WCS_Heart 主动心跳。NodeId 命名空间/路径需按现场 PLC 核对', 'system', 1
where not exists (select 1 from "wcs_protocol" where "protocol_code" = 'STACKER_OPCUA' and "tenant_id" = 1);

-- 2) 点位：克隆 STACKER_STD 点位，地址转 OPC UA NodeId
insert into "wcs_protocol_point"
    ("protocol_id", "point_group", "field_name", "symbol_name", "address", "data_type", "rw", "value_range", "scale", "sort", "description", "creator", "tenant_id")
select np."id", pp."point_group", pp."field_name", pp."symbol_name",
    'ns=3;s="WCS_Task"."' || pp."point_group" || '"."' || pp."field_name" || '"',
    pp."data_type", pp."rw", pp."value_range", pp."scale", pp."sort", pp."description", 'system', 1
from "wcs_protocol_point" pp
join "wcs_protocol" sp on pp."protocol_id" = sp."id" and sp."protocol_code" = 'STACKER_STD'
cross join "wcs_protocol" np
where np."protocol_code" = 'STACKER_OPCUA'
  and pp."is_valid" = true
  and not exists (select 1 from "wcs_protocol_point" x where x."protocol_id" = np."id");

-- 3) 故障码：克隆 STACKER_STD 的 1-48
insert into "wcs_fault_code" ("protocol_id", "code", "level", "name", "message", "suggestion", "creator", "tenant_id")
select np."id", fc."code", fc."level", fc."name", fc."message", fc."suggestion", 'system', 1
from "wcs_fault_code" fc
join "wcs_protocol" sp on fc."protocol_id" = sp."id" and sp."protocol_code" = 'STACKER_STD'
cross join "wcs_protocol" np
where np."protocol_code" = 'STACKER_OPCUA'
  and fc."is_valid" = true
  and not exists (select 1 from "wcs_fault_code" x where x."protocol_id" = np."id");

-- 4) 应用：礁盘工业码垛机
insert into "wcs_application" ("app_code", "app_name", "app_key", "app_secret", "protocol_id", "direction",
    "conn_params", "max_retry", "heartbeat_interval_ms", "enabled", "status", "remark", "creator", "tenant_id")
select 'PALLET01', '礁盘工业码垛机', 'ak_pallet_opcua', 'secret_pallet_opcua',
    (select "id" from "wcs_protocol" where "protocol_code" = 'STACKER_OPCUA'), 'downstream',
    '{"endpoint":"opc.tcp://192.168.0.1:4840","namespaceIndex":3,"requestTimeoutMs":5000}',
    5, 1000, true, 'idle', 'OPC UA 对接；endpoint 与 NodeId 命名空间需按现场 PLC 调整', 'system', 1
where not exists (select 1 from "wcs_application" where "app_code" = 'PALLET01' and "tenant_id" = 1);
