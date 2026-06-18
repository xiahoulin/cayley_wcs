package com.cayleywcs.faultcode;

import com.cayleywcs.faultcode.entity.FaultCodeEntity;
import java.util.List;

public interface FaultCodeService {

    List<FaultCodeEntity> listByProtocol(Long protocolId);

    FaultCodeEntity create(FaultCodeEntity entity);

    FaultCodeEntity update(FaultCodeEntity entity);

    boolean delete(Long id);
}
