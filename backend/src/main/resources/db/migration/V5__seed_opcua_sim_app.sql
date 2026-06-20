-- 无硬件 OPC UA 联调用：码垛机 OPC UA 仿真协议 + 应用，指向内置 Milo 仿真服务端（simulator profile）。
-- NodeId 用仿真器实际暴露的 ns=2;s=WCS_Task.<group>.<field>（Milo 首个自定义命名空间索引=2）。

insert into "wcs_protocol" ("protocol_code", "protocol_name", "target_system", "protocol_type",
    "data_format", "transport_template", "version", "status", "remark", "creator", "tenant_id")
select 'PALLET_SIM_OPCUA', '码垛机OPCUA仿真协议', 'Milo仿真', 'opcua',
    '{"block":"DB100","access":"opcua","addressing":"sim NodeId ns=2;s=WCS_Task.<group>.<field>"}',
    '{"transport":"opcua","simulator":"OpcUaStackerSimulator"}', '1.0', 'enabled',
    '配合 simulator profile：opc.tcp://127.0.0.1:4840/cayleywcs/stacker', 'system', 1
where not exists (select 1 from "wcs_protocol" where "protocol_code" = 'PALLET_SIM_OPCUA' and "tenant_id" = 1);

insert into "wcs_protocol_point"
    ("protocol_id", "point_group", "field_name", "symbol_name", "address", "data_type", "rw", "value_range", "scale", "sort", "description", "creator", "tenant_id")
select np."id", pp."point_group", pp."field_name", pp."symbol_name",
    'ns=2;s=WCS_Task.' || pp."point_group" || '.' || pp."field_name",
    pp."data_type", pp."rw", pp."value_range", pp."scale", pp."sort", pp."description", 'system', 1
from "wcs_protocol_point" pp
join "wcs_protocol" sp on pp."protocol_id" = sp."id" and sp."protocol_code" = 'STACKER_STD'
cross join "wcs_protocol" np
where np."protocol_code" = 'PALLET_SIM_OPCUA'
  and pp."is_valid" = true
  and not exists (select 1 from "wcs_protocol_point" x where x."protocol_id" = np."id");

insert into "wcs_fault_code" ("protocol_id", "code", "level", "name", "message", "suggestion", "creator", "tenant_id")
select np."id", fc."code", fc."level", fc."name", fc."message", fc."suggestion", 'system', 1
from "wcs_fault_code" fc
join "wcs_protocol" sp on fc."protocol_id" = sp."id" and sp."protocol_code" = 'STACKER_STD'
cross join "wcs_protocol" np
where np."protocol_code" = 'PALLET_SIM_OPCUA'
  and fc."is_valid" = true
  and not exists (select 1 from "wcs_fault_code" x where x."protocol_id" = np."id");

insert into "wcs_application" ("app_code", "app_name", "app_key", "app_secret", "protocol_id", "direction",
    "conn_params", "max_retry", "heartbeat_interval_ms", "enabled", "status", "remark", "creator", "tenant_id")
select 'PALLET_SIM', '码垛机OPCUA仿真', 'ak_pallet_sim', 'secret_pallet_sim',
    (select "id" from "wcs_protocol" where "protocol_code" = 'PALLET_SIM_OPCUA'), 'downstream',
    '{"endpoint":"opc.tcp://127.0.0.1:4840/cayleywcs/stacker"}',
    5, 1000, true, 'idle', '指向内置 Milo OPC UA 仿真器（simulator profile）；注入故障用 /simulator/inject-fault {appId:999001}', 'system', 1
where not exists (select 1 from "wcs_application" where "app_code" = 'PALLET_SIM' and "tenant_id" = 1);
