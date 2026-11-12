alter table wcs_application add column adapter_type varchar(48) not null default 'standard';

update wcs_application set adapter_type='seer_agv',configuration_epoch=configuration_epoch+1
where protocol_id in (select id from wcs_protocol where protocol_type='seer_tcp');
update wcs_application set adapter_type='dr164_sensor',configuration_epoch=configuration_epoch+1
where protocol_id in (select id from wcs_protocol where protocol_type='sensor_gateway');

-- Reclassify transport metadata only. Retain point bytes, publication IDs/fingerprints and pinned task/binding identities.
update wcs_protocol_revision set protocol_type='tcp' where protocol_type in ('seer_tcp','sensor_gateway');
update wcs_protocol set protocol_type='tcp',row_version=row_version+1 where protocol_type in ('seer_tcp','sensor_gateway');
delete from wcs_dict_item where type_code='protocol_type' and item_code in ('seer_tcp','sensor_gateway');

create table wcs_dashboard_point_selection (
    tenant_id bigint not null,
    user_id bigint not null,
    app_id bigint not null references wcs_application(id),
    fields text not null,
    primary key (tenant_id,user_id,app_id)
);
