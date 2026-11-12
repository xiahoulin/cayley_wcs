package com.cayleywcs.protocol;

import static org.assertj.core.api.Assertions.*;
import com.cayleywcs.protocol.mapper.ProtocolPointsTypeHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Exercise the real V19 -> V20 upgrade with published bindings and task identities already present. */
class PointRwRemovalMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String SNAPSHOT = """
            [{"id":901,"protocol_id":1,"field_name":"cmd_TaskNum","rw":"RW","direction":"request",
              "point_scope":"internal","template_code":"stacker","template_version":1,"operation":"control",
              "logical_type":"UINT16","data_type":"UINT","address":"ns=2;s=cmd_TaskNum","required":true,"nullable":false,
              "constraints":{"min":1,"max":65534,"reserved_values":[0,65535],"unit":"毫米","precision":0.12345678901234567890123456789},
              "default_value":null,"sort":1,"row_version":8,"create_time":"2025-06-01T10:00:00","last_update_time":"2025-06-01T10:01:00"},
             {"id":902,"field_name":"status_TaskNum","rw":"R","direction":"response","point_scope":"business",
              "constraints":{"min":0,"max":9223372036854775807,"rw":"preserve-unrelated-nested-key"},"sort":2}]
            """;

    @Test void actualUpgradeDropsColumnAndSnapshotPropertyWhileKeepingEveryStoredIdentity() throws Exception {
        String url="jdbc:h2:mem:remove_rw_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("19").load().migrate();
        try (Connection connection=DriverManager.getConnection(url,"sa","")) {
            long protocol=scalar(connection,"select id from wcs_protocol where protocol_code='PALLET_SIM_OPCUA'");
            long downstream=scalar(connection,"select id from wcs_application where app_code='PALLET_SIM'");
            long upstream=scalar(connection,"select id from wcs_application where app_code='WMS-MAIN'");
            long revision=insertRevision(connection,protocol,1,"published-fingerprint-kept",SNAPSHOT);
            String unchanged="  [{\"field_name\":\"cmd_Open\",\"direction\":\"request\",\"point_scope\":\"business\",\"constraints\":{\"min\":0,\"max\":1}}]  ";
            long cleanRevision=insertRevision(connection,protocol,2,"already-clean-fingerprint",unchanged);
            execute(connection,"update wcs_protocol set lifecycle_state='published',published_revision_id=?,row_version=17 where id=?",revision,protocol);
            execute(connection,"update wcs_application set protocol_revision_id=?,configuration_epoch=4 where id=?",revision,downstream);
            execute(connection,"update wcs_app_binding set upstream_revision_id=?,downstream_revision_id=?,contract_fingerprint='binding-fingerprint-kept',row_version=6 where upstream_app_id=? and downstream_app_id=?",cleanRevision,revision,upstream,downstream);
            execute(connection,"insert into wcs_task(task_no,app_id,status,handshake_step,payload,result,caller_app_id,binding_id,protocol_revision_id,execution_epoch,native_task_num,physical_started,contract_fingerprint,request_fingerprint,idempotency_scope,version,create_time,last_update_time) values('NO-RW-HISTORY',?,'completed','DONE','{\"cmd_TaskType\":1}','{\"quality\":\"good\",\"payload\":{\"status_Task\":2}}',?,1,?,4,123,true,'binding-fingerprint-kept','request-fingerprint-kept','caller:4',9,timestamp '2025-06-01 10:00:00',timestamp '2025-06-01 10:01:00')",downstream,upstream,revision);
            List<Map<String,Object>> pointRows=rows(connection,"select * from wcs_protocol_point order by id");
            pointRows.forEach(row -> row.remove("rw"));
            List<Map<String,Object>> protocols=rows(connection,"select * from wcs_protocol order by id");
            List<Map<String,Object>> applications=rows(connection,"select * from wcs_application order by id");
            List<Map<String,Object>> bindings=rows(connection,"select * from wcs_app_binding order by id");
            List<Map<String,Object>> tasks=rows(connection,"select * from wcs_task order by id");
            List<Map<String,Object>> revisions=rows(connection,"select * from wcs_protocol_revision order by id"); revisions.forEach(row -> row.remove("points"));
            List<Map<String,Object>> otherTypes=rows(connection,"select * from wcs_dict_type where type_code<>'point_rw' order by id");
            List<Map<String,Object>> otherItems=rows(connection,"select * from wcs_dict_item where type_code<>'point_rw' order by id");
            assertThat(scalar(connection,"select count(*) from wcs_dict_item where type_code='point_rw'")).isEqualTo(3);

            var upgrade=Flyway.configure().dataSource(url,"sa","").target("20").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(scalar(connection,"select count(*) from information_schema.columns where table_name='wcs_protocol_point' and column_name='rw'")).isZero();
            assertThat(rows(connection,"select * from wcs_protocol_point order by id")).isEqualTo(pointRows);
            assertThat(rows(connection,"select * from wcs_protocol order by id")).isEqualTo(protocols);
            assertThat(rows(connection,"select * from wcs_application order by id")).isEqualTo(applications);
            assertThat(rows(connection,"select * from wcs_app_binding order by id")).isEqualTo(bindings);
            assertThat(rows(connection,"select * from wcs_task order by id")).isEqualTo(tasks);
            List<Map<String,Object>> upgradedRevisions=rows(connection,"select * from wcs_protocol_revision order by id"); upgradedRevisions.forEach(row -> row.remove("points"));
            assertThat(upgradedRevisions).isEqualTo(revisions);
            assertThat(rows(connection,"select * from wcs_dict_type where type_code<>'point_rw' order by id")).isEqualTo(otherTypes);
            assertThat(rows(connection,"select * from wcs_dict_item where type_code<>'point_rw' order by id")).isEqualTo(otherItems);
            assertThat(scalar(connection,"select count(*) from wcs_dict_type where type_code='point_rw'")).isZero();
            assertThat(scalar(connection,"select count(*) from wcs_dict_item where type_code='point_rw'")).isZero();
            JsonNode expected=JSON.readTree(SNAPSHOT); for(JsonNode point:expected) ((ObjectNode)point).remove("rw");
            try(PreparedStatement query=connection.prepareStatement("select points from wcs_protocol_revision where id=?")) {
                query.setLong(1,revision); try(ResultSet result=query.executeQuery()) {
                    assertThat(result.next()).isTrue(); assertThat(JSON.readTree(result.getString("points"))).isEqualTo(expected);
                    var typed=new ProtocolPointsTypeHandler().getNullableResult(result,"points");
                    assertThat(typed).hasSize(2); assertThat(typed.getFirst().getField_name()).isEqualTo("cmd_TaskNum");
                    assertThat(typed.get(1).getConstraints()).containsEntry("rw","preserve-unrelated-nested-key");
                }
                query.setLong(1,cleanRevision); try(ResultSet result=query.executeQuery()) {result.next();assertThat(result.getString(1)).isEqualTo(unchanged);}
            }
        }
    }

    @Test void malformedSnapshotAbortsBeforeRemovingAnyColumnOrChangingAnySnapshot() throws Exception {
        String url="jdbc:h2:mem:bad_rw_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("19").load().migrate();
        try(Connection connection=DriverManager.getConnection(url,"sa","")) {
            long protocol=scalar(connection,"select id from wcs_protocol where protocol_code='PALLET_SIM_OPCUA'");
            insertRevision(connection,protocol,1,"good",SNAPSHOT); insertRevision(connection,protocol,2,"bad","{}");
            var before=rows(connection,"select * from wcs_protocol_revision order by id");
            assertThatThrownBy(() -> new db.migration.V20__remove_point_rw().migrate(connection)).hasMessageContaining("不是数组");
            assertThat(rows(connection,"select * from wcs_protocol_revision order by id")).isEqualTo(before);
            assertThat(scalar(connection,"select count(*) from information_schema.columns where table_name='wcs_protocol_point' and column_name='rw'")).isEqualTo(1);
        }
    }

    private static long insertRevision(Connection connection,long protocol,long number,String fingerprint,String snapshot) throws Exception {
        try(PreparedStatement query=connection.prepareStatement("insert into wcs_protocol_revision(protocol_id,revision,protocol_type,fingerprint,points,creator,create_time,last_update_time) values(?,?,'opcua',?,?, 'migration-test',timestamp '2025-06-01 10:00:00',timestamp '2025-06-01 10:01:00')",Statement.RETURN_GENERATED_KEYS)) {
            query.setLong(1,protocol);query.setLong(2,number);query.setString(3,fingerprint);query.setString(4,snapshot);query.executeUpdate();
            try(ResultSet result=query.getGeneratedKeys()){result.next();return result.getLong(1);}
        }
    }
    private static void execute(Connection connection,String sql,Object... arguments) throws Exception {
        try(PreparedStatement query=connection.prepareStatement(sql)) {for(int index=0;index<arguments.length;index++)query.setObject(index+1,arguments[index]);query.executeUpdate();}
    }
    private static long scalar(Connection connection,String sql) throws Exception {
        try(Statement query=connection.createStatement();ResultSet result=query.executeQuery(sql)){result.next();return result.getLong(1);}
    }
    private static List<Map<String,Object>> rows(Connection connection,String sql) throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        try(Statement query=connection.createStatement();ResultSet result=query.executeQuery(sql)) {
            ResultSetMetaData columns=result.getMetaData();
            while(result.next()) {Map<String,Object> row=new LinkedHashMap<>();for(int index=1;index<=columns.getColumnCount();index++)row.put(columns.getColumnLabel(index),result.getObject(index));rows.add(row);}
        }
        return rows;
    }
}
