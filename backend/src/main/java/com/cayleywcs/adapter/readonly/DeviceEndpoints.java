package com.cayleywcs.adapter.readonly;

import com.cayleywcs.common.exception.WcsException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Deployment config owns network locations; database rows contain only a reference. */
@Component
public class DeviceEndpoints {
    private final Environment environment;
    public DeviceEndpoints(Environment environment) { this.environment = environment; }
    public record Endpoint(String host, int port, int timeoutMs, boolean allowMissingRetCode) { }
    public Endpoint resolve(String reference) {
        return find(reference).orElseThrow(() -> new WcsException(4223,"端点尚未配置: " + reference));
    }
    public Endpoint resolveForTenant(String reference,Long tenantId) {
        return findForTenant(reference,tenantId).orElseThrow(() -> new WcsException(4223,"端点尚未配置: " + reference));
    }
    public java.util.Optional<Endpoint> findForTenant(String reference,Long tenantId) {
        var endpoint=find(reference);
        long owner=environment.getProperty("cayleywcs.endpoints."+reference+".tenant-id",Long.class,1L);
        if(!Long.valueOf(owner).equals(tenantId))
            throw new WcsException(404,"端点不存在或不可访问");
        return endpoint;
    }
    public java.util.Optional<Endpoint> find(String reference) {
        if (reference == null || !reference.matches("[a-z][a-z0-9-]{0,47}"))
            throw new WcsException(4223, "端点引用只能包含小写字母、数字和短横线");
        String prefix = "cayleywcs.endpoints." + reference + ".";
        String host = environment.getProperty(prefix + "host", "");
        int port = environment.getProperty(prefix + "port", Integer.class, 0);
        int timeout = environment.getProperty(prefix + "timeout-ms", Integer.class, 1000);
        if (host.isBlank() && port == 0) return java.util.Optional.empty();
        if (host.isBlank() || port < 1 || port > 65535 || timeout < 100 || timeout > 10000)
            throw new WcsException(4223, "端点运行配置不存在或无效: " + reference);
        return java.util.Optional.of(new Endpoint(host, port, timeout,
                environment.getProperty(prefix + "allow-missing-ret-code", Boolean.class, false)));
    }
}
