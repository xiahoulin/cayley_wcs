package com.cayleywcs.protocol;

import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.List;

public interface ProtocolService {

    PageData<ProtocolEntity> page(PageSearch pageSearch);

    List<ProtocolEntity> listAll();

    ProtocolEntity getById(Long id);

    ProtocolEntity create(ProtocolEntity entity);

    ProtocolEntity update(ProtocolEntity entity);

    boolean delete(Long id);

    List<ProtocolPointEntity> listPoints(Long protocolId);

    ProtocolPointEntity createPoint(ProtocolPointEntity entity);

    ProtocolPointEntity updatePoint(ProtocolPointEntity entity);

    boolean deletePoint(Long id);
}
