create table "wcs_open_token" (
    "token_hash" varchar(64) primary key,
    "app_id" bigint not null,
    "tenant_id" bigint not null,
    "credential_hash" varchar(64) not null,
    "issued_at" bigint not null,
    "expires_at" bigint not null
);
create index "idx_wcs_open_token_expiry" on "wcs_open_token" ("expires_at");
