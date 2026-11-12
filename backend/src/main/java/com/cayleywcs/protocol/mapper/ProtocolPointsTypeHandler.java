package com.cayleywcs.protocol.mapper;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Snapshot list mapping is typed and supports audit timestamps without changing a global Jackson mapper. */
public class ProtocolPointsTypeHandler extends BaseTypeHandler<List<ProtocolPointEntity>> {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<ProtocolPointEntity>> TYPE = new TypeReference<>() { };
    @Override public void setNonNullParameter(PreparedStatement statement, int index, List<ProtocolPointEntity> points, JdbcType jdbcType) throws SQLException {
        try { statement.setString(index, JSON.writeValueAsString(points)); }
        catch (java.io.IOException ex) { throw new SQLException("协议快照序列化失败", ex); }
    }
    private List<ProtocolPointEntity> parse(String value) throws SQLException {
        if (value == null) return null;
        try { return JSON.readValue(value, TYPE); }
        catch (java.io.IOException ex) { throw new SQLException("协议快照读取失败", ex); }
    }
    @Override public List<ProtocolPointEntity> getNullableResult(ResultSet result, String name) throws SQLException { return parse(result.getString(name)); }
    @Override public List<ProtocolPointEntity> getNullableResult(ResultSet result, int index) throws SQLException { return parse(result.getString(index)); }
    @Override public List<ProtocolPointEntity> getNullableResult(CallableStatement result, int index) throws SQLException { return parse(result.getString(index)); }
}
