package com.cayleywcs.system;

import com.cayleywcs.auth.JwtUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public JwtUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtUser user) {
            return user;
        }
        throw new IllegalStateException("authenticated user is required");
    }

    /** 系统/调度场景下的兜底用户（无 HTTP 上下文，如连接工作线程、定时任务）。 */
    public JwtUser systemUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtUser user) {
            return user;
        }
        return new JwtUser(0L, "system", "system", "system", 1L, 0L);
    }
}
