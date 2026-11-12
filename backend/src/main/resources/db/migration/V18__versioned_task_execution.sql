alter table "wcs_application" add column "protocol_revision_id" bigint;
alter table "wcs_application" add column "configuration_epoch" bigint not null default 0;
alter table "wcs_application" add column "configuration_state" varchar(32) not null default 'active';
alter table "wcs_app_binding" add column "upstream_revision_id" bigint;
alter table "wcs_app_binding" add column "downstream_revision_id" bigint;
alter table "wcs_app_binding" add column "contract_fingerprint" varchar(128);
alter table "wcs_app_binding" add column "row_version" bigint not null default 0;
alter table "wcs_task" add column "caller_app_id" bigint;
alter table "wcs_task" add column "binding_id" bigint;
alter table "wcs_task" add column "protocol_revision_id" bigint;
alter table "wcs_task" add column "execution_epoch" bigint;
alter table "wcs_task" add column "native_task_num" bigint;
alter table "wcs_task" add column "physical_started" boolean not null default false;
alter table "wcs_task" add column "contract_fingerprint" varchar(128);
alter table "wcs_task" add column "request_fingerprint" varchar(128);
alter table "wcs_task" add column "idempotency_scope" varchar(160);
alter table "wcs_task" add column "held_reason" varchar(1024);
alter table "wcs_task" add column "result" text;
alter table "wcs_task" add column "original_request" text;
alter table "wcs_task_chain" add column "request_fingerprint" varchar(128);
create unique index "uk_wcs_task_request" on "wcs_task" ("tenant_id","app_id","task_no","idempotency_scope");
create table "wcs_device_task_number" (
 "app_id" bigint primary key,
 "next_number" bigint not null default 1
);
create table "wcs_device_task_number_lease" (
 "app_id" bigint not null,
 "native_number" bigint not null,
 "task_id" bigint not null unique,
 primary key ("app_id","native_number")
);
insert into "wcs_device_task_number" ("app_id") select "id" from "wcs_application";
create index "ix_wcs_task_revision" on "wcs_task" ("protocol_revision_id","status");
