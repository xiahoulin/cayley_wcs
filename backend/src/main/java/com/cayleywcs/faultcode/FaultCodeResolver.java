package com.cayleywcs.faultcode;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.faultcode.entity.FaultCodeEntity;
import com.cayleywcs.faultcode.mapper.FaultCodeMapper;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 故障码解析（需求 6）：命中返回维护的信息，未维护返回统一兜底 {@link FaultInfo#unknown}。
 * 进程内缓存，故障码 CRUD 时调用 {@link #invalidate} 失效。
 */
@Component
public class FaultCodeResolver {
    private final FaultCodeMapper faultCodeMapper;
    private final ConcurrentHashMap<String, FaultInfo> cache = new ConcurrentHashMap<>();

    public FaultCodeResolver(FaultCodeMapper faultCodeMapper) {
        this.faultCodeMapper = faultCodeMapper;
    }

    public FaultInfo resolve(Long protocolId, long code) {
        if (protocolId == null) {
            return FaultInfo.unknown(code);
        }
        return cache.computeIfAbsent(key(protocolId, code), k -> load(protocolId, code));
    }

    public void invalidate() {
        cache.clear();
    }

    private FaultInfo load(Long protocolId, long code) {
        FaultCodeEntity row = faultCodeMapper.selectOne(new QueryWrapper<FaultCodeEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"protocol_id\"", protocolId)
                .eq("\"code\"", code)
                .last("limit 1"));
        if (row == null) {
            return FaultInfo.unknown(code);
        }
        return new FaultInfo(code, row.getLevel(), row.getName(), row.getMessage(), row.getSuggestion(), true);
    }

    private static String key(Long protocolId, long code) {
        return protocolId + ":" + code;
    }
}
