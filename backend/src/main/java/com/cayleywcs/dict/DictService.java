package com.cayleywcs.dict;

import com.cayleywcs.dict.entity.DictItemEntity;
import com.cayleywcs.dict.entity.DictTypeEntity;
import java.util.List;

public interface DictService {

    List<DictTypeEntity> listTypes();

    DictTypeEntity createType(DictTypeEntity entity);

    DictTypeEntity updateType(DictTypeEntity entity);

    boolean deleteType(Long id);

    List<DictItemEntity> listItems(String typeCode);

    DictItemEntity createItem(DictItemEntity entity);

    DictItemEntity updateItem(DictItemEntity entity);

    boolean deleteItem(Long id);
}
