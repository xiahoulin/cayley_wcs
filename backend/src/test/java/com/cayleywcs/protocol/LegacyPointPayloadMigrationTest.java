package com.cayleywcs.protocol;

import static org.assertj.core.api.Assertions.*;
import db.migration.V19__legacy_point_payload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class LegacyPointPayloadMigrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    Connection schema() throws Exception {
        String url="jdbc:h2:mem:legacy_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("18").load().migrate(); return DriverManager.getConnection(url,"sa","");
    }
    long app(Connection c,String code)throws Exception{try(PreparedStatement q=c.prepareStatement("select id from wcs_application where app_code=?")){q.setString(1,code);try(ResultSet r=q.executeQuery()){r.next();return r.getLong(1);}}}
    long task(Connection c,String no,long app,String type,String status,String step,String payload)throws Exception {
        try(PreparedStatement q=c.prepareStatement("insert into wcs_task(task_no,app_id,task_type,status,handshake_step,payload,put_row,put_column,put_floor,port_num,create_time,last_update_time)values(?,?,?,?,?,?,1,2,3,1,timestamp '2025-06-01 10:00:00',timestamp '2025-06-01 10:01:00')",Statement.RETURN_GENERATED_KEYS)) {
            q.setString(1,no);q.setLong(2,app);q.setString(3,type);q.setString(4,status);q.setString(5,step);q.setString(6,payload);q.executeUpdate();try(ResultSet r=q.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    JsonNode json(Connection c,String column,long id)throws Exception{try(PreparedStatement q=c.prepareStatement("select "+column+" from wcs_task where id=?")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){r.next();return JSON.readTree(r.getString(1));}}}
    Object column(Connection c,String name,long id)throws Exception{try(PreparedStatement q=c.prepareStatement("select "+name+" from wcs_task where id=?")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){r.next();return r.getObject(1);}}}

    @Test void migrationConvertsKnownDataKeepsSourceAndDoesNotTouchTimeOrTerminalStates()throws Exception {
        try(Connection c=schema()) {
            long stacker=app(c,"PALLET_SIM"),door=app(c,"DOOR_SIM"),conveyor=app(c,"CONVEYOR_SIM");
            long converted=task(c,"IN-2025-001",stacker,"1","completed","DONE","{}");
            long conflict=task(c,"IN-2025-002",stacker,"1","pending","CREATED","{\"cmd_PutCoor_Row\":2}");
            long opened=task(c,"DOOR-1",door,"1","completed","DONE","{\"action\":\"open\"}");
            long transport=task(c,"CONVEYOR-1",conveyor,"1","completed","DONE","{\"toStation\":3,\"fromStation\":1}");
            long unknown=task(c,"DOOR-unknown",door,"1","failed","FAILED","{\"action\":\"other\"}");
            new V19__legacy_point_payload().migrate(c);
            assertThat(json(c,"payload",converted).path("cmd_PutCoor_Row").asInt()).isEqualTo(1);
            assertThat(json(c,"payload",converted).path("cmd_TaskType").asInt()).isEqualTo(1);
            assertThat(json(c,"original_request",converted).path("put_row").asInt()).isEqualTo(1);
            assertThat(column(c,"caller_app_id",converted)).isNull(); assertThat(column(c,"idempotency_scope",converted)).isEqualTo("legacy:"+converted);
            assertThat(column(c,"last_update_time",converted)).isEqualTo(Timestamp.valueOf("2025-06-01 10:01:00")); assertThat(column(c,"status",converted)).isEqualTo("completed");
            assertThat(json(c,"payload",conflict).path("cmd_PutCoor_Row").asInt()).isEqualTo(2); assertThat(column(c,"held_reason",conflict).toString()).contains("冲突");
            assertThat(json(c,"payload",opened).path("cmd_Open").asInt()).isEqualTo(1); assertThat(json(c,"payload",opened).path("cmd_Close").asInt()).isZero();
            assertThat(json(c,"payload",transport).path("cmd_DestStation").asInt()).isEqualTo(3); assertThat(json(c,"payload",transport).has("fromStation")).isFalse();
            assertThat(json(c,"original_request",transport).path("payload").path("fromStation").asInt()).isEqualTo(1);
            assertThat(json(c,"payload",unknown).path("action").asText()).isEqualTo("other"); assertThat(column(c,"held_reason",unknown).toString()).contains("禁止默认开门");
            assertThat(column(c,"physical_started",unknown)).isEqualTo(false);
        }
    }
    @Test void inFlightNumbersAndTrustedChainSourceArePreservedWithoutLeases()throws Exception {
        try(Connection c=schema()) {
            long stacker=app(c,"PALLET_SIM"),up=app(c,"WMS-MAIN");
            long inFlight=task(c,"WA123",stacker,"1","executing","EXECUTING","{}");
            long ambiguous=task(c,"TASK-NO-DIGITS",stacker,"1","executing","EXECUTING","{}");
            long child=task(c,"CHAIN-77-1",stacker,"1","pending","CREATED","{}");
            long chain;
            try(PreparedStatement q=c.prepareStatement("insert into wcs_task_chain(chain_no,upstream_app_id,status,segments)values('OLD-CHAIN',?,'running',?)",Statement.RETURN_GENERATED_KEYS)) {
                q.setLong(1,up);q.setString(2,"[{\"seq\":1,\"appId\":"+stacker+",\"taskType\":1,\"putRow\":1,\"putColumn\":2,\"putFloor\":3,\"portNum\":1}]");q.executeUpdate();try(ResultSet r=q.getGeneratedKeys()){r.next();chain=r.getLong(1);}
            }
            try(PreparedStatement q=c.prepareStatement("update wcs_task set chain_id=? where id=?")){q.setLong(1,chain);q.setLong(2,child);q.executeUpdate();}
            new V19__legacy_point_payload().migrate(c);
            assertThat(column(c,"native_task_num",inFlight)).isEqualTo(123L); assertThat(column(c,"physical_started",inFlight)).isEqualTo(true);
            assertThat(column(c,"native_task_num",ambiguous)).isNull(); assertThat(column(c,"held_reason",ambiguous).toString()).contains("禁止重新编号");
            assertThat(column(c,"caller_app_id",child)).isEqualTo(up);
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("select count(*) from wcs_device_task_number_lease")){r.next();assertThat(r.getLong(1)).isZero();}
            try(PreparedStatement q=c.prepareStatement("select original_segments,segments,held_reason,status from wcs_task_chain where id=?")){q.setLong(1,chain);try(ResultSet r=q.executeQuery()){r.next();assertThat(r.getString(1)).contains("taskType");assertThat(r.getString(2)).contains("cmd_TaskType").doesNotContain("\"taskType\"");assertThat(r.getString(3)).contains("禁止在新地址自动物化");assertThat(r.getString(4)).isEqualTo("running");}}
        }
    }
    @Test void unknownPointAndConflictingLegacyAliasesAreNotSilentlyDropped() {
        assertThatThrownBy(()->V19__legacy_point_payload.convert("conveyor",Map.of(),Map.of("toStation",1,"cmd_DestStation",2))).hasMessageContaining("冲突");
        assertThatThrownBy(()->V19__legacy_point_payload.convert("auto_door",Map.of(),Map.of("cmd_Open",1,"cmd_Close",1))).hasMessageContaining("恰好一个");
        assertThatThrownBy(()->V19__legacy_point_payload.convert("lift",Map.of(),Map.of("targetFloor",2,"vendorUnknown",1))).hasMessageContaining("未定义");
    }
}
