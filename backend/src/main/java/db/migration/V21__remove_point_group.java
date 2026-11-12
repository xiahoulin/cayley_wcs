package db.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Retire display groups; point direction and purpose remain the protocol contract definition. */
public class V21__remove_point_group extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private record SnapshotUpdate(long id, String points) { }

    @Override public void migrate(Context context) throws Exception { migrate(context.getConnection()); }

    public void migrate(Connection connection) throws Exception {
        // Parse stored JSON before loading typed entities, which no longer have this property.
        List<SnapshotUpdate> updates = new ArrayList<>();
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("select id,points from wcs_protocol_revision order by id")) {
            while (rows.next()) {
                long id = rows.getLong("id"); JsonNode points = JSON.readTree(rows.getString("points"));
                if (points == null || !points.isArray()) throw new SQLException("协议快照点位不是数组: " + id);
                boolean changed = false;
                for (JsonNode point : points) {
                    if (!(point instanceof ObjectNode object)) throw new SQLException("协议快照点位不是对象: " + id);
                    changed |= object.remove("point_group") != null;
                }
                if (changed) updates.add(new SnapshotUpdate(id, JSON.writeValueAsString(points)));
            }
        }
        // Only points change. Keep the publication fingerprint and all pinned binding/task identities.
        // Native address strings, including From_WCS/To_WCS path parts, remain untouched.
        try (PreparedStatement update = connection.prepareStatement("update wcs_protocol_revision set points=? where id=?")) {
            for (SnapshotUpdate snapshot : updates) {
                update.setString(1, snapshot.points()); update.setLong(2, snapshot.id());
                if (update.executeUpdate() != 1) throw new SQLException("协议快照在升级时不存在: " + snapshot.id());
            }
        }
        try (Statement ddl = connection.createStatement()) {
            ddl.executeUpdate("delete from wcs_dict_item where type_code='point_group'");
            ddl.executeUpdate("delete from wcs_dict_type where type_code='point_group'");
            ddl.execute("alter table wcs_protocol_point drop column point_group");
        }
    }
}
