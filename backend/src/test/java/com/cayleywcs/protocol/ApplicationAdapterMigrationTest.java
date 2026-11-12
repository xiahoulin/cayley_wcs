package com.cayleywcs.protocol;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ApplicationAdapterMigrationTest {
    @Test void oldTransportTypesBecomeTcpWhilePublishedPointsFingerprintsAndApplicationIdentityRemain() throws Exception {
        String url="jdbc:h2:mem:application_adapters_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("23").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()) {
            for(int i=0;i<2;i++) {
                String type=i==0?"seer_tcp":"sensor_gateway";long id=900100+i;
                sql.executeUpdate("insert into wcs_protocol(id,protocol_code,protocol_name,protocol_type,lifecycle_state,published_revision_id,row_version) values ("+id+",'MIG_"+i+"','迁移测试','"+type+"','published',"+id+",7)");
                sql.executeUpdate("insert into wcs_protocol_revision(id,protocol_id,revision,protocol_type,fingerprint,points) values ("+id+","+id+",7,'"+type+"','original-fingerprint-"+i+"','  []  ')");
                sql.executeUpdate("insert into wcs_application(id,app_code,app_name,app_key,app_secret,protocol_id,protocol_revision_id,configuration_epoch,device_type,conn_params,desired_state) values ("+id+",'MIG_"+i+"','迁移应用','preserved-key-"+i+"','preserved-secret',"+id+","+id+",4,'"+(i==0?"agv":"noise_sensor")+"','{\"endpointRef\":\"private-reference\"}','open')");
            }
            var upgrade=Flyway.configure().dataSource(url,"sa","").target("24").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);upgrade.validate();assertThat(upgrade.migrate().migrationsExecuted).isZero();
            try(var rows=sql.executeQuery("select * from wcs_application where id>=900100 order by id")) {
                for(int i=0;i<2;i++) {assertThat(rows.next()).isTrue();assertThat(rows.getString("adapter_type")).isEqualTo(i==0?"seer_agv":"dr164_sensor");assertThat(rows.getLong("protocol_revision_id")).isEqualTo(900100+i);assertThat(rows.getLong("configuration_epoch")).isEqualTo(5);assertThat(rows.getString("app_key")).isEqualTo("preserved-key-"+i);assertThat(rows.getString("app_secret")).isEqualTo("preserved-secret");assertThat(rows.getString("conn_params")).isEqualTo("{\"endpointRef\":\"private-reference\"}");assertThat(rows.getString("desired_state")).isEqualTo("open");}
            }
            try(var rows=sql.executeQuery("select * from wcs_protocol_revision where id>=900100 order by id")) {
                for(int i=0;i<2;i++){assertThat(rows.next()).isTrue();assertThat(rows.getString("protocol_type")).isEqualTo("tcp");assertThat(rows.getString("points")).isEqualTo("  []  ");assertThat(rows.getString("fingerprint")).isEqualTo("original-fingerprint-"+i);assertThat(rows.getLong("revision")).isEqualTo(7);}
            }
            try(var rows=sql.executeQuery("select count(*) from wcs_protocol where protocol_type in ('seer_tcp','sensor_gateway')")){rows.next();assertThat(rows.getInt(1)).isZero();}
            try(var rows=sql.executeQuery("select count(*) from wcs_dict_item where type_code='protocol_type' and item_code in ('seer_tcp','sensor_gateway')")){rows.next();assertThat(rows.getInt(1)).isZero();}
            try(var rows=sql.executeQuery("select adapter_type from wcs_application where app_code='PALLET_SIM'")){rows.next();assertThat(rows.getString(1)).isEqualTo("standard");}
        }
    }
}
