create table wcs_agv_navigation_command (
    id bigserial primary key,
    app_id bigint not null references wcs_application(id),
    protocol_revision_id bigint not null references wcs_protocol_revision(id),
    configuration_epoch bigint not null,
    request_id varchar(64) not null,
    station_name varchar(128) not null,
    status varchar(16) not null check (status in ('sending','accepted','rejected','failed','unknown')),
    sent boolean not null default false,
    ret_code integer,
    message varchar(512) not null,
    created_at bigint not null,
    received_at bigint,
    acknowledged_at bigint,
    creator varchar(128) not null,
    tenant_id bigint not null,
    unique (app_id,request_id)
);
create index ix_agv_navigation_app on wcs_agv_navigation_command(app_id,id);
