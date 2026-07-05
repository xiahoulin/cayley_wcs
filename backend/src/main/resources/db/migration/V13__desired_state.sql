-- M12 高可用·单机自愈：连接期望态持久化。
-- desired_state = 用户意图（非执行结果）：open()入口即落 'open'，建连失败只影响实际态+报警，
-- 恢复器/看门狗 rearm 持续追赶意图；close()先落 'closed' 防恢复器把刚关的连接又拉起。
-- 重启后 ConnectionRecoveryRunner 扫 desired_state='open' 的应用自动建连。
alter table "wcs_application" add column if not exists "desired_state" varchar(16) not null default 'closed';

insert into "wcs_dict_type" ("type_code", "type_name", "sort")
select 'desired_state', '连接期望态', 15
where not exists (select 1 from "wcs_dict_type" where "type_code" = 'desired_state');

insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'desired_state', 'open', '期望连接', 'open', 1, '重启/失败后自动恢复到已连接'
where not exists (select 1 from "wcs_dict_item" where "type_code"='desired_state' and "item_code"='open');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'desired_state', 'closed', '期望断开', 'closed', 2, '不自动恢复（默认）'
where not exists (select 1 from "wcs_dict_item" where "type_code"='desired_state' and "item_code"='closed');
