package com.cayleywcs.protocol;

import static org.assertj.core.api.Assertions.*;
import com.cayleywcs.protocol.contract.impl.ProtocolContractServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ApplicationOwnedProtocolMigrationTest {
    @Test void sharedProtocolsAreSplitWithPinnedTasksChainsGrantsAndFaultsPreserved() throws Exception {
        String url="jdbc:h2:mem:ownership_migration_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("24").load().migrate();
        try(var db=DriverManager.getConnection(url,"sa","");var sql=db.createStatement()) {
            long upstreamProtocol=number(sql,"select protocol_id from wcs_application where id=4");
            sql.executeUpdate("insert into wcs_protocol_revision(id,protocol_id,revision,protocol_type,fingerprint,points) values(300,"+upstreamProtocol+",1,'http','up-fingerprint','[]')");
            sql.executeUpdate("update wcs_protocol set lifecycle_state='published',published_revision_id=300 where id="+upstreamProtocol);
            sql.executeUpdate("update wcs_application set protocol_revision_id=300 where id=4");
            sql.executeUpdate("insert into wcs_protocol(id,protocol_code,protocol_name,protocol_type,lifecycle_state,published_revision_id) values(100,'SHARED','共享协议','sim','published',101)");
            sql.executeUpdate("insert into wcs_protocol_point(id,protocol_id,field_name,address,logical_type,data_type) values(1000,100,'sample','native.unchanged','INT16','INT16')");
            sql.executeUpdate("insert into wcs_protocol_revision(id,protocol_id,revision,protocol_type,fingerprint,points) values(101,100,7,'sim','down-fingerprint','[{\"id\":1000,\"protocol_id\":100,\"field_name\":\"sample\",\"address\":\"native.unchanged\",\"constraints\":{\"max\":18446744073709551615}}]')");
            sql.executeUpdate("insert into wcs_fault_code(protocol_id,code,message) values(100,123,'保留故障解释')");
            for(int id:List.of(200,201)) sql.executeUpdate("insert into wcs_application(id,app_code,app_name,app_key,app_secret,protocol_id,protocol_revision_id,device_type,configuration_epoch,desired_state,conn_params) values("+id+",'A"+id+"','设备','key"+id+"','unchanged-secret',100,101,'conveyor',8,'open','{\"preserved\":true}')");
            String fingerprint=ProtocolContractServiceImpl.hash("300:101:conveyor:up-fingerprint:down-fingerprint");
            sql.executeUpdate("insert into wcs_app_binding(id,upstream_app_id,downstream_app_id,upstream_revision_id,downstream_revision_id,contract_fingerprint) values(500,4,201,300,101,'"+fingerprint+"')");
            sql.executeUpdate("insert into wcs_task(id,task_no,app_id,caller_app_id,binding_id,protocol_revision_id,status,handshake_step,version,native_task_num,execution_epoch,contract_fingerprint) values(500,'PRESERVED',201,4,500,101,'executing','EXECUTING',9,12,8,'"+fingerprint+"')");
            sql.executeUpdate("insert into wcs_device_task_number_lease(app_id,native_number,task_id) values(201,12,500)");
            sql.executeUpdate("insert into wcs_task_chain(id,chain_no,upstream_app_id,status,segments) values(500,'PINNED',4,'running','[{\"appId\":201,\"pinnedRevisionIds\":{\"200\":101,\"201\":101}}]')");
            sql.executeUpdate("insert into wcs_protocol(id,protocol_code,protocol_name,protocol_type) values(110,'UNUSED','历史模板','tcp')");
            var migration=Flyway.configure().dataSource(url,"sa","").target("25").load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();assertThat(migration.migrate().migrationsExecuted).isZero();
            assertThat(number(sql,"select protocol_id from wcs_application where id=200")).isEqualTo(100);
            long copied=number(sql,"select protocol_id from wcs_application where id=201"),revision=number(sql,"select protocol_revision_id from wcs_application where id=201");
            assertThat(copied).isNotEqualTo(100);assertThat(revision).isNotEqualTo(101);
            assertThat(number(sql,"select app_id from wcs_protocol where id="+copied)).isEqualTo(201);
            assertThat(text(sql,"select address from wcs_protocol_point where protocol_id="+copied)).isEqualTo("native.unchanged");
            assertThat(text(sql,"select message from wcs_fault_code where protocol_id="+copied)).isEqualTo("保留故障解释");
            var snapshot=new ObjectMapper().readTree(text(sql,"select points from wcs_protocol_revision where id="+revision)).get(0);
            assertThat(snapshot.path("protocol_id").longValue()).isEqualTo(copied);assertThat(snapshot.path("constraints").path("max").asText()).isEqualTo("18446744073709551615");
            String expected=ProtocolContractServiceImpl.hash("300:"+revision+":conveyor:up-fingerprint:down-fingerprint");
            assertThat(text(sql,"select contract_fingerprint from wcs_app_binding where id=500")).isEqualTo(expected);
            assertThat(text(sql,"select contract_fingerprint from wcs_task where id=500")).isEqualTo(expected);
            assertThat(number(sql,"select protocol_revision_id from wcs_task where id=500")).isEqualTo(revision);
            assertThat(text(sql,"select status from wcs_task where id=500")).isEqualTo("executing");assertThat(number(sql,"select version from wcs_task where id=500")).isEqualTo(9);
            assertThat(number(sql,"select native_number from wcs_device_task_number_lease where task_id=500")).isEqualTo(12);
            var pinned=new ObjectMapper().readTree(text(sql,"select segments from wcs_task_chain where id=500")).get(0).path("pinnedRevisionIds");
            assertThat(pinned.path("200").longValue()).isEqualTo(101);assertThat(pinned.path("201").longValue()).isEqualTo(revision);
            assertThat(text(sql,"select app_secret from wcs_application where id=201")).isEqualTo("unchanged-secret");assertThat(number(sql,"select configuration_epoch from wcs_application where id=201")).isEqualTo(8);
            assertThat(text(sql,"select desired_state from wcs_application where id=201")).isEqualTo("open");assertThat(text(sql,"select conn_params from wcs_application where id=201")).isEqualTo("{\"preserved\":true}");
            assertThat(number(sql,"select count(*) from wcs_protocol where id=110 and app_id is null and legacy_template=true")).isEqualTo(1);
            assertThatThrownBy(()->sql.executeUpdate("update wcs_application set protocol_id=100,protocol_revision_id=101 where id=201")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("update wcs_application set protocol_id=110,protocol_revision_id=null where id=201")).isInstanceOf(SQLException.class);
        }
    }
    private static long number(Statement sql,String query)throws SQLException{try(var rs=sql.executeQuery(query)){rs.next();return rs.getLong(1);}}
    private static String text(Statement sql,String query)throws SQLException{try(var rs=sql.executeQuery(query)){rs.next();return rs.getString(1);}}
}
