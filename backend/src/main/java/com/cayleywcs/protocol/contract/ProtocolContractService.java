package com.cayleywcs.protocol.contract;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.List;
import java.util.Map;

public interface ProtocolContractService {
    record Snapshot(Long id, Long protocolId, long version, String protocolType, String templateCode,
                    List<ProtocolPointEntity> points, String fingerprint) { }
    record Difference(String field, String direction, String expected, String actual, String reason) { }
    record Match(boolean compatible, String fingerprint, Long upstreamSnapshotId, Long downstreamSnapshotId,
                 List<Difference> differences) { }
    Snapshot resolve(ApplicationEntity app);
    Snapshot resolve(ApplicationEntity app, String templateCode);
    Snapshot resolveRevision(Long revisionId, String templateCode);
    Map<String, Object> validatePayload(ApplicationEntity app, Map<String, Object> payload);
    Map<String, Object> validatePayload(Snapshot snapshot, Map<String, Object> payload);
    Map<String, Object> validateResponse(ApplicationEntity app, Map<String, Object> payload);
    Map<String, Object> validateResponse(Snapshot snapshot, Map<String, Object> payload);
    Match match(ApplicationEntity upstream, ApplicationEntity downstream);
    Match requireMatch(ApplicationEntity upstream, ApplicationEntity downstream);
}
