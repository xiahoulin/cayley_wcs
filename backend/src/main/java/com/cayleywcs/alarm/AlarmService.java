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

    /** 单码恢复：清除该应用指定 fault_code 的 raised/ack 报警（支持多故障逐个恢复）。 */
    void clearFault(Long appId, long faultCode);

    /** 连接恢复：清除该应用所有 raised 的通讯类报警(fault_code<=0)。 */
    void clearCommAlarms(Long appId);

    PageData<AlarmEntity> page(PageSearch pageSearch);

    List<AlarmEntity> listActive(Long appId);

    /** 对账用：返回 last_update_time >= sinceMillis 的报警（sinceMillis=0 即全量），按更新时间升序。 */
    List<AlarmEntity> queryReconcile(Long appId, long sinceMillis, int limit);
}
