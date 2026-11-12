package com.cayleywcs.telemetry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cayleywcs.telemetry.entity.TelemetryLatestEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TelemetryLatestMapper extends BaseMapper<TelemetryLatestEntity> { }
