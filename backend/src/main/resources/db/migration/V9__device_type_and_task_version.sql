-- M8 内核重构：设备类型抽象 + 任务乐观锁。
-- wcs_application.device_type：DeviceHandlerRegistry 路由键（字典 device_type）；上位系统用 none。
-- wcs_task.version：乐观锁列（引擎推进 / 管理端取消改优先级 / 编排器三方写并发，防丢失更新）。

alter table "wcs_application" add column if not exists "device_type" varchar(64) not null default 'stacker';
alter table "wcs_task" add column if not exists "version" bigint not null default 0;

-- 字典：设备类型（shuttle/agv 预留电量语义 status_Battery，充电调度后置；none=上位系统不参与设备调度）
insert into "wcs_dict_type" ("type_code", "type_name", "sort")
select 'device_type', '设备类型', 10
where not exists (select 1 from "wcs_dict_type" where "type_code" = 'device_type');

-- 逐行 not-exists 守卫（对齐 V4/V5/V7 棕地迁移惯例）：已运营库可能经字典 API 手建过 device_type 条目，
-- (type_code,item_code) 无唯一约束，无守卫会静默产生重复项。
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'stacker', '堆垛机', 'stacker', 1, '6.18 三段握手'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='stacker');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'palletizer', '码垛机', 'palletizer', 2, '复用堆垛机 handler'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='palletizer');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'conveyor', '输送线/滚筒线', 'conveyor', 3, 'M9 落地'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='conveyor');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'lift', '提升机/电梯', 'lift', 4, 'M9 落地'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='lift');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'auto_door', '自动门', 'auto_door', 5, 'M9 落地'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='auto_door');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'shuttle', '穿梭车', 'shuttle', 6, '预留：电量点位 status_Battery，充电调度后置'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='shuttle');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'robot_arm', '机械手', 'robot_arm', 7, '预留模板'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='robot_arm');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'packer', '打包机', 'packer', 8, '预留模板'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='packer');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'agv', 'AGV', 'agv', 9, '预留：电量点位 status_Battery，充电调度后置'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='agv');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'device_type', 'none', '无（上位系统）', 'none', 10, '不参与设备任务调度'
where not exists (select 1 from "wcs_dict_item" where "type_code"='device_type' and "item_code"='none');

-- 存量订正（历史迁移文件不改，数据订正在新版本内完成）：
-- V4/V5 码垛机应用归类 palletizer；upstream 应用（如 V7 WMS-MAIN）不是设备，归类 none。
update "wcs_application" set "device_type" = 'palletizer' where "app_code" in ('PALLET01', 'PALLET_SIM');
update "wcs_application" set "device_type" = 'none' where "direction" = 'upstream';
