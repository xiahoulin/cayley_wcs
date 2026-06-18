package com.cayleywcs.alarm;

import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import java.util.List;

public interface AlarmService {

    /** 产生报警（去重：同 app + fault_code 的 raised 报警已存在则复用）。 */
    AlarmEntity raise(Long appId, long faultCode, String level, String message, String suggestion);

    AlarmEntity ack(Long id, String ackBy);

    boolean clear(Long id);

    /** 设备故障恢复：清除该应用所有 raised 的设备故障(fault_code>0)。 */
    void clearActiveFaults(Long appId);

    /** 连接恢复：清除该应用所有 raised 的通讯类报警(fault_code<=0)。 */
    void clearCommAlarms(Long appId);

    PageData<AlarmEntity> page(PageSearch pageSearch);

    List<AlarmEntity> listActive(Long appId);
}
