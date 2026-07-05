package com.cayleywcs.openapi;

import com.cayleywcs.application.security.AppKeyAuthFilter;
import com.cayleywcs.binding.BindingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * /open/** 越权防护（F8）：以 AppKey 验签得到的调用方真实身份为准，校验其对 body.appId 的访问权。
 * - 指挥类（dispatch）：必须存在启用中的绑定（wcs_app_binding）；自指挥不放行（绑定本就禁自绑定）。
 * - 查询类（query/snapshot）：查自己放行，否则须存在启用中的绑定。
 * - appkey 关闭（cayleywcs.openapi.appkey-enabled=false）时无身份可校验：WARN 后旁路（与既有 dispatch 行为一致）。
 */
@Component
public class OpenAccessGuard {
    private static final Logger log = LoggerFactory.getLogger(OpenAccessGuard.class);

    private final BindingService bindingService;

    public OpenAccessGuard(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    /** 指挥类动作：调用方必须被授权指挥目标应用，否则 FORBIDDEN。 */
    public void assertCanCommand(HttpServletRequest http, Long appId, String action) {
        Long caller = caller(http);
        if (caller == null) {
            warnBypass(action, appId);
            return;
        }
        // 越权判定按调用方租户隔离（与唯一索引含 tenant 对齐），防止跨租户绑定误放行。
        bindingService.assertAllowed(caller, appId, tenant(http));
    }

    /** 查询类动作：查自己放行，否则须有绑定授权。 */
    public void assertCanQuery(HttpServletRequest http, Long appId, String action) {
        Long caller = caller(http);
        if (caller == null) {
            warnBypass(action, appId);
            return;
        }
        if (caller.equals(appId)) {
            return;
        }
        bindingService.assertAllowed(caller, appId, tenant(http));
    }

    /**
     * appId 缺省时的可见集合：自身 + 已授权下位应用（按调用方租户过滤，与显式 appId 的 assertAllowed 口径一致，
     * 防止跨租户绑定经缺省路径泄读）。返回 null 表示不限（appkey 关闭旁路）。
     */
    public List<Long> visibleAppIds(HttpServletRequest http, String action) {
        Long caller = caller(http);
        if (caller == null) {
            warnBypass(action, null);
            return null;
        }
        List<Long> ids = new ArrayList<>(bindingService.grantedDownstreamIds(caller, tenant(http)));
        if (!ids.contains(caller)) {
            ids.add(caller);
        }
        return ids;
    }

    /** 验签得到的调用方身份；null=appkey 鉴权关闭（旁路）。 */
    public Long resolveCaller(HttpServletRequest http) {
        return caller(http);
    }

    private Long caller(HttpServletRequest http) {
        Object v = http.getAttribute(AppKeyAuthFilter.ATTR_APP_ID);
        return v instanceof Long l ? l : null;
    }

    private Long tenant(HttpServletRequest http) {
        Object v = http.getAttribute(AppKeyAuthFilter.ATTR_APP_TENANT_ID);
        return v instanceof Long l ? l : null;
    }

    /** caller 仅在 appkey 鉴权整体关闭时为 null：显式 WARN，避免「关 appkey=静默关越权防护」。 */
    private void warnBypass(String action, Long appId) {
        log.warn("AppKey 鉴权已关闭，/open {} 跳过越权校验 (appId={})。生产环境请勿关闭 cayleywcs.openapi.appkey-enabled。",
                action, appId);
    }
}
