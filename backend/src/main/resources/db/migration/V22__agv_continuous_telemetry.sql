insert into "wcs_dict_item" ("type_code","item_code","item_name","item_value","sort")
select 'protocol_type','seer_tcp','仙工 SRC 只读 TCP','seer_tcp',8
where not exists (select 1 from "wcs_dict_item" where "type_code"='protocol_type' and "item_code"='seer_tcp');

create table "wcs_sensor_gateway" (
    "id" bigserial primary key,
    "agv_app_id" bigint not null references "wcs_application"("id"),
    "endpoint_ref" varchar(48) not null unique,
    "enabled" boolean not null default false,
    "infrared_offset" integer not null check ("infrared_offset" in (0,1)),
    "infrared_min" double precision not null,
    "infrared_max" double precision not null,
    "power_on_at" bigint,
    "power_evidence" varchar(500),
    "config_version" bigint not null default 1,
    "creator" varchar(128), "create_time" timestamp not null default current_timestamp,
    "last_update_time" timestamp not null default current_timestamp,
    "is_valid" boolean not null default true, "tenant_id" bigint not null,
    unique ("agv_app_id"),
    check ("infrared_min" >= 0 and "infrared_max" > "infrared_min" and "infrared_max" <= 6553.5)
);
create table "wcs_telemetry_cycle" (
    "id" bigserial primary key, "gateway_id" bigint not null references "wcs_sensor_gateway"("id"),
    "cycle_id" varchar(36) not null unique, "config_version" bigint not null,
    "started_at" bigint not null, "finished_at" bigint not null, "duration_ms" bigint not null,
    "connection_state" varchar(32) not null, "measurements" text not null, "diagnostics" text not null,
    "creator" varchar(128), "create_time" timestamp not null default current_timestamp,
    "last_update_time" timestamp not null default current_timestamp,
    "is_valid" boolean not null default true, "tenant_id" bigint not null
);
create index "ix_telemetry_cycle_gateway_time" on "wcs_telemetry_cycle" ("tenant_id","gateway_id","finished_at","id");
create index "ix_telemetry_cycle_retention" on "wcs_telemetry_cycle" ("finished_at");
create table "wcs_telemetry_latest" (
    "id" bigserial primary key, "gateway_id" bigint not null references "wcs_sensor_gateway"("id"),
    "metric" varchar(64) not null, "observed_at" bigint not null, "config_version" bigint not null,
    "sample" text not null, "last_good" text,
    "creator" varchar(128), "create_time" timestamp not null default current_timestamp,
    "last_update_time" timestamp not null default current_timestamp,
    "is_valid" boolean not null default true, "tenant_id" bigint not null,
    unique ("gateway_id","metric")
);
