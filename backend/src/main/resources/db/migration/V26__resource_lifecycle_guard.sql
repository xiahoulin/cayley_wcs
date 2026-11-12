-- Reference writers share this lock; application/protocol deletion takes it exclusively.
-- Existing business data is unchanged.
create table wcs_resource_lifecycle_lock (id integer primary key check (id = 1));
insert into wcs_resource_lifecycle_lock(id) values (1);
