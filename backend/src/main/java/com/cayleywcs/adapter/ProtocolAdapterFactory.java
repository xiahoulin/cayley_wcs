package com.cayleywcs.adapter;

import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 适配器工厂（需求 5 · Factory）：按协议类型选择 {@link ProtocolAdapterProvider} 并创建适配器。
 * Spring 自动注入所有 Provider Bean，新增协议只需新增一个 Provider，无需改工厂。
 */
@Component
public class ProtocolAdapterFactory {
    private final Map<String, ProtocolAdapterProvider> providers;

    public ProtocolAdapterFactory(List<ProtocolAdapterProvider> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(p -> p.protocolType().toLowerCase(), p -> p, (a, b) -> a));
    }

    public ProtocolAdapter create(AdapterContext ctx) {
        String type = ctx.protocolType() == null ? "" : ctx.protocolType().toLowerCase();
        ProtocolAdapterProvider provider = providers.get(type);
        if (provider == null) {
            throw new WcsException(ErrorCode.PROTOCOL_UNSUPPORTED, "不支持的协议类型: " + ctx.protocolType());
        }
        return provider.create(ctx);
    }

    public boolean supports(String protocolType) {
        return protocolType != null && providers.containsKey(protocolType.toLowerCase());
    }
}
