package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep identities and wire definitions; split shared profiles without widening existing grants. */
public class V25__application_owned_protocols extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private record Key(long app, long oldId) { }
    @Override public void migrate(Context context) throws Exception { migrate(context.getConnection()); }

    public void migrate(Connection db) throws Exception {
        execute(db, "alter table wcs_application alter column protocol_id drop not null",
                "alter table wcs_protocol add column app_id bigint",
                "alter table wcs_protocol add column legacy_template boolean not null default false");
        var apps = indexed(rows(db, "select * from wcs_application order by id"));
        var protocols = indexed(rows(db, "select * from wcs_protocol order by id"));
        var revisions = indexed(rows(db, "select * from wcs_protocol_revision order by id"));
        Map<Long, SortedSet<Long>> owners = new LinkedHashMap<>();
        for (var app : apps.values()) addOwner(owners, protocols, apps, id(app,"protocol_id"), id(app,"id"));
        // Historical versions may no longer be selected, but still belong to the application that used them.
        for (var task : rows(db,"select app_id,protocol_revision_id from wcs_task"))
            revisionOwner(owners,protocols,apps,revisions,id(task,"protocol_revision_id"),id(task,"app_id"));
        var bindings = rows(db,"select * from wcs_app_binding order by id");
        for (var binding : bindings) {
            revisionOwner(owners,protocols,apps,revisions,id(binding,"upstream_revision_id"),id(binding,"upstream_app_id"));
            revisionOwner(owners,protocols,apps,revisions,id(binding,"downstream_revision_id"),id(binding,"downstream_app_id"));
        }
        var chains=rows(db,"select id,segments from wcs_task_chain order by id");
        for (var chain:chains) for (var segment:JSON.readTree((String)chain.get("segments"))) {
            var pinned=segment.path("pinnedRevisionIds");
            if(pinned.isObject()) for(var entry: iterable(pinned.fields()))
                revisionOwner(owners,protocols,apps,revisions,entry.getValue().longValue(),Long.parseLong(entry.getKey()));
        }
        Map<Key,Long> protocolIds = new HashMap<>(), revisionIds = new HashMap<>();
        for (var protocol : protocols.values()) {
            long pid=id(protocol,"id");
            var candidates=owners.getOrDefault(pid,new TreeSet<>());
            if(candidates.isEmpty()) { update(db,"update wcs_protocol set legacy_template=true where id=?",pid); continue; }
            // Prefer a current selection so its protocol/revision IDs remain unchanged.
            long primary=candidates.stream().filter(a->Objects.equals(id(apps.get(a),"protocol_id"),pid)).findFirst().orElse(candidates.first());
            update(db,"update wcs_protocol set app_id=? where id=?",primary,pid);
            for(long appId:candidates) {
                boolean original=appId==primary;
                long newPid=original?pid:insertCopy(db,"wcs_protocol",protocol,Map.of("app_id",appId,"legacy_template",false),Set.of("published_revision_id"));
                protocolIds.put(new Key(appId,pid),newPid);
                Map<Long,Long> pointIds=new HashMap<>();
                if(!original) {
                    for(var point:rows(db,"select * from wcs_protocol_point where protocol_id=? order by id",pid))
                        pointIds.put(id(point,"id"),insertCopy(db,"wcs_protocol_point",point,Map.of("protocol_id",newPid),Set.of()));
                    for(var fault:rows(db,"select * from wcs_fault_code where protocol_id=? order by id",pid))
                        insertCopy(db,"wcs_fault_code",fault,Map.of("protocol_id",newPid),Set.of());
                }
                for(var revision:revisions.values()) if(Objects.equals(id(revision,"protocol_id"),pid)) {
                    long rid=id(revision,"id"),newRid=rid;
                    if(!original) {
                        var points=JSON.readTree((String)revision.get("points"));
                        if(!points.isArray()) throw new SQLException("Invalid protocol snapshot: "+rid);
                        for(var point:points) {
                            var object=(ObjectNode)point;object.put("protocol_id",newPid);
                            if(pointIds.containsKey(point.path("id").asLong())) object.put("id",pointIds.get(point.path("id").asLong()));
                        }
                        // Preserve the published definition fingerprint: only ownership metadata changes.
                        newRid=insertCopy(db,"wcs_protocol_revision",revision,Map.of("protocol_id",newPid,"points",JSON.writeValueAsString(points)),Set.of());
                    }
                    revisionIds.put(new Key(appId,rid),newRid);
                }
                if(!original && id(protocol,"published_revision_id")!=null)
                    update(db,"update wcs_protocol set published_revision_id=? where id=?",revisionIds.get(new Key(appId,id(protocol,"published_revision_id"))),newPid);
            }
        }
        for(var app:apps.values()) if(id(app,"protocol_id")!=null)
            update(db,"update wcs_application set protocol_id=?,protocol_revision_id=? where id=?",
                    protocolIds.get(new Key(id(app,"id"),id(app,"protocol_id"))),mapped(revisionIds,id(app,"id"),id(app,"protocol_revision_id")),id(app,"id"));
        for(var task:rows(db,"select id,app_id,protocol_revision_id from wcs_task where protocol_revision_id is not null"))
            update(db,"update wcs_task set protocol_revision_id=? where id=?",mapped(revisionIds,id(task,"app_id"),id(task,"protocol_revision_id")),id(task,"id"));
        for(var binding:bindings) {
            Long up=id(binding,"upstream_revision_id"),down=id(binding,"downstream_revision_id");
            Long newUp=mapped(revisionIds,id(binding,"upstream_app_id"),up),newDown=mapped(revisionIds,id(binding,"downstream_app_id"),down);
            String oldFingerprint=(String)binding.get("contract_fingerprint"),newFingerprint=oldFingerprint;
            if(up!=null && down!=null && (!Objects.equals(up,newUp)||!Objects.equals(down,newDown))) {
                String type=(String)apps.get(id(binding,"downstream_app_id")).get("device_type");
                String template="palletizer".equals(type)?"stacker":type;
                String suffix=":"+template+":"+revisions.get(up).get("fingerprint")+":"+revisions.get(down).get("fingerprint");
                if(Objects.equals(oldFingerprint,hash(up+":"+down+suffix))) {
                    newFingerprint=hash(newUp+":"+newDown+suffix);
                    update(db,"update wcs_task set contract_fingerprint=? where binding_id=? and contract_fingerprint=?",newFingerprint,id(binding,"id"),oldFingerprint);
                }
            }
            update(db,"update wcs_app_binding set upstream_revision_id=?,downstream_revision_id=?,contract_fingerprint=? where id=?",newUp,newDown,newFingerprint,id(binding,"id"));
        }
        for(var chain:chains) {
            var segments=JSON.readTree((String)chain.get("segments"));boolean changed=false;
            for(var segment:segments) if(segment.path("pinnedRevisionIds") instanceof ObjectNode pinned)
                for(var entry: iterable(pinned.fields())) {
                    long old=entry.getValue().longValue(),next=mapped(revisionIds,Long.parseLong(entry.getKey()),old);
                    if(old!=next){pinned.put(entry.getKey(),next);changed=true;}
                }
            if(changed)update(db,"update wcs_task_chain set segments=? where id=?",JSON.writeValueAsString(segments),id(chain,"id"));
        }
        execute(db,
            "alter table wcs_application add constraint uq_app_tenant unique(id,tenant_id)",
            "alter table wcs_protocol add constraint uq_protocol_tenant unique(id,tenant_id)",
            "alter table wcs_protocol add constraint uq_protocol_app_tenant unique(id,app_id,tenant_id)",
            "alter table wcs_protocol_revision add constraint uq_revision_protocol_tenant unique(id,protocol_id,tenant_id)",
            "alter table wcs_protocol add constraint ck_protocol_owner check ((app_id is not null and legacy_template=false) or (app_id is null and legacy_template=true))",
            "alter table wcs_protocol add constraint fk_protocol_app foreign key(app_id,tenant_id) references wcs_application(id,tenant_id)",
            "alter table wcs_application add constraint fk_app_owned_protocol foreign key(protocol_id,id,tenant_id) references wcs_protocol(id,app_id,tenant_id)",
            "alter table wcs_application add constraint ck_app_revision_parent check(protocol_revision_id is null or protocol_id is not null)",
            "alter table wcs_application add constraint fk_app_protocol_revision foreign key(protocol_revision_id,protocol_id,tenant_id) references wcs_protocol_revision(id,protocol_id,tenant_id)",
            "alter table wcs_protocol_point add constraint fk_point_protocol foreign key(protocol_id,tenant_id) references wcs_protocol(id,tenant_id)",
            "alter table wcs_protocol_revision add constraint fk_revision_protocol foreign key(protocol_id,tenant_id) references wcs_protocol(id,tenant_id)",
            "alter table wcs_protocol add constraint fk_protocol_publication foreign key(published_revision_id,id,tenant_id) references wcs_protocol_revision(id,protocol_id,tenant_id)",
            "create index ix_protocol_app on wcs_protocol(app_id,tenant_id,is_valid)");
    }
    private static void revisionOwner(Map<Long,SortedSet<Long>> owners,Map<Long,Map<String,Object>> protocols,Map<Long,Map<String,Object>> apps,Map<Long,Map<String,Object>> revisions,Long revision,Long app) throws SQLException {
        if(revision==null)return;
        if(!revisions.containsKey(revision))throw new SQLException("Missing pinned revision: "+revision);
        addOwner(owners,protocols,apps,id(revisions.get(revision),"protocol_id"),app);
    }
    private static void addOwner(Map<Long,SortedSet<Long>> owners,Map<Long,Map<String,Object>> protocols,Map<Long,Map<String,Object>> apps,Long protocol,Long app) throws SQLException {
        if(protocol==null)return;
        if(!protocols.containsKey(protocol)||!apps.containsKey(app)||!Objects.equals(protocols.get(protocol).get("tenant_id"),apps.get(app).get("tenant_id")))
            throw new SQLException("Cannot migrate ambiguous protocol ownership: protocol="+protocol+", app="+app);
        owners.computeIfAbsent(protocol,key->new TreeSet<>()).add(app);
    }
    private static Long mapped(Map<Key,Long> ids,Long app,Long old) throws SQLException {
        if(old==null)return null;Long result=ids.get(new Key(app,old));
        if(result==null)throw new SQLException("Missing protocol revision ownership: app="+app+", revision="+old);return result;
    }
    private static Long id(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:((Number)value).longValue();}
    private static Map<Long,Map<String,Object>> indexed(List<Map<String,Object>> rows){Map<Long,Map<String,Object>> result=new LinkedHashMap<>();for(var row:rows)result.put(id(row,"id"),row);return result;}
    private static List<Map<String,Object>> rows(Connection db,String sql,Object...args)throws SQLException {
        try(var statement=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);try(var rs=statement.executeQuery()){
            List<Map<String,Object>> result=new ArrayList<>();var meta=rs.getMetaData();while(rs.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++){Object value=rs.getObject(i);if(value instanceof Clob clob)value=clob.getSubString(1,(int)clob.length());row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),value);}result.add(row);}return result;
        }}
    }
    private static long insertCopy(Connection db,String table,Map<String,Object> source,Map<String,Object> overrides,Set<String> omitted)throws SQLException {
        var values=new LinkedHashMap<>(source);values.remove("id");omitted.forEach(values::remove);values.putAll(overrides);
        String columns=String.join(",",values.keySet().stream().map(k->"\""+k+"\"").toList());
        try(var statement=db.prepareStatement("insert into "+table+" ("+columns+") values ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",Statement.RETURN_GENERATED_KEYS)){
            int i=0;for(Object value:values.values())statement.setObject(++i,value);statement.executeUpdate();try(var keys=statement.getGeneratedKeys()){if(!keys.next())throw new SQLException("No generated id: "+table);return keys.getLong(1);}
        }
    }
    private static void execute(Connection db,String...sql)throws SQLException{try(var statement=db.createStatement()){for(String text:sql)statement.execute(text);}}
    private static void update(Connection db,String sql,Object...args)throws SQLException{try(var statement=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);statement.executeUpdate();}}
    private static <T> Iterable<T> iterable(Iterator<T> iterator){List<T> copy=new ArrayList<>();iterator.forEachRemaining(copy::add);return copy;}
    private static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
