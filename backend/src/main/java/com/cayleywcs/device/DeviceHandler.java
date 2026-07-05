package com.cayleywcs.device;

import com.cayleywcs.task.entity.TaskEntity;
import java.util.List;

/**
 * 设备类型 SPI（M8）：每类设备一个 handler，封装该设备的任务交互语义（握手/命令时序/完成判定）。
 * 范式对齐 ProtocolAdapterProvider：Spring 注入全部实现，{@link DeviceHandlerRegistry} 按
 * wcs_application.device_type 路由，新增设备类型零改任务引擎。
 */
public interface DeviceHandler {

    /** 主设备类型码（字典 device_type）。 */
    String deviceType();

    /** 本 handler 服务的全部设备类型（默认仅主类型；如堆垛机 handler 同时服务码垛机）。 */
    default List<String> deviceTypes() {
        return List.of(deviceType());
    }

    /**
     * 推进任务一步（点位读写经 ConnectionManager）。返回 true=仍在进行；false=已到终态(DONE/FAILED)。
     * 约定：实现必须步步可重入——「点位已写、任务行未保存」（崩溃或乐观锁冲突窗口）后重放本步不得卡死或产生副作用。
     */
    boolean advance(TaskEntity task);
}
