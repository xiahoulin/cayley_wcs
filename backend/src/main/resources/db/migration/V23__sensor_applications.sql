create table wcs_sensor_application (
    app_id bigint primary key references wcs_application(id),
    gateway_id bigint not null references wcs_sensor_gateway(id),
    sensor_kind varchar(32) not null,
    tenant_id bigint not null,
    unique (gateway_id, sensor_kind),
    check (sensor_kind in ('vibration_sensor','noise_sensor','infrared_sensor'))
);

insert into wcs_dict_item (type_code,item_code,item_name,item_value,sort)
select 'protocol_type','sensor_gateway','DR164 共享总线传感器','sensor_gateway',9
where not exists(select 1 from wcs_dict_item where type_code='protocol_type' and item_code='sensor_gateway');
insert into wcs_dict_item (type_code,item_code,item_name,item_value,sort)
select 'device_type','vibration_sensor','振动传感器','vibration_sensor',21
where not exists(select 1 from wcs_dict_item where type_code='device_type' and item_code='vibration_sensor');
insert into wcs_dict_item (type_code,item_code,item_name,item_value,sort)
select 'device_type','noise_sensor','噪声传感器','noise_sensor',22
where not exists(select 1 from wcs_dict_item where type_code='device_type' and item_code='noise_sensor');
insert into wcs_dict_item (type_code,item_code,item_name,item_value,sort)
select 'device_type','infrared_sensor','红外温度传感器','infrared_sensor',23
where not exists(select 1 from wcs_dict_item where type_code='device_type' and item_code='infrared_sensor');
