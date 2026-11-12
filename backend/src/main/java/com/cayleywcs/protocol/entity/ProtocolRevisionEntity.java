package com.cayleywcs.protocol.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cayleywcs.protocol.mapper.ProtocolPointsTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.util.List;

/** Published revisions are append-only and never reconstructed from mutable draft rows. */
@TableName(value = "\"wcs_protocol_revision\"", autoResultMap = true)
public class ProtocolRevisionEntity extends BaseEntity {
    private Long protocol_id;
    private Long revision;
    private String protocol_type;
    private String fingerprint;
    @TableField(typeHandler = ProtocolPointsTypeHandler.class)
    private List<ProtocolPointEntity> points;
    public Long getProtocol_id() { return protocol_id; }
    public void setProtocol_id(Long value) { protocol_id = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public String getProtocol_type() { return protocol_type; }
    public void setProtocol_type(String value) { protocol_type = value; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String value) { fingerprint = value; }
    public List<ProtocolPointEntity> getPoints() { return points; }
    public void setPoints(List<ProtocolPointEntity> value) { points = value; }
}
