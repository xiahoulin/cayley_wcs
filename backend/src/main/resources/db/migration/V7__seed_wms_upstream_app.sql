-- 引入一个真实的「上位」应用（WMS 主系统），让绑定授权从“设备自绑定”升级为“以上位为中心授权下位设备”。
-- WMS 用本应用的 AppKey 调 /open/**；绑定授权页可为它勾选可指挥的下位设备集合。
-- protocol_id 对上位无实际意义（上位不建设备连接），指向任一已存在协议以满足 NOT NULL。

insert into "wcs_application" ("app_code", "app_name", "app_key", "app_secret", "protocol_id", "direction",
    "conn_params", "max_retry", "heartbeat_interval_ms", "enabled", "status", "remark", "creator", "tenant_id")
select 'WMS-MAIN', '上位 WMS 主系统', 'ak_wms_main', 'secret_wms_main',
    coalesce((select min("id") from "wcs_protocol" where "is_valid" = true), 0), 'upstream',
    '{}', 5, 5000, true, 'idle',
    '上位调用方（WMS）。用 ak_wms_main 调 /open/**；可指挥的下位设备在「绑定授权」页按上位勾选。', 'system', 1
where not exists (select 1 from "wcs_application" where "app_code" = 'WMS-MAIN' and "tenant_id" = 1);

-- 授权 WMS-MAIN 指挥当前所有「下位设备」（direction=downstream）。后续可在前端按上位增删。
insert into "wcs_app_binding"
    ("upstream_app_id", "downstream_app_id", "scope", "enabled", "remark", "creator", "tenant_id")
select up."id", dev."id", 'dispatch', true, '初始授权（上位 WMS-MAIN → 全部下位设备）', 'system', 1
from "wcs_application" up
cross join "wcs_application" dev
where up."app_code" = 'WMS-MAIN' and up."tenant_id" = 1
  and dev."direction" = 'downstream' and dev."is_valid" = true
  and not exists (
      select 1 from "wcs_app_binding" b
      where b."upstream_app_id" = up."id" and b."downstream_app_id" = dev."id" and b."tenant_id" = 1
  );
