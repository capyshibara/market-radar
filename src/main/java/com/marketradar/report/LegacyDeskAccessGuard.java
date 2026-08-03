package com.marketradar.report;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * marketradar.legacy-desks.enabled=false (default): turns off the Product/Sales/
 * Compliance desk pipeline's routes (404) without deleting the code, per Strategy's
 * (CFO's) explicit ask to inactivate rather than remove it. Unlike demo-mode, which
 * only hides sidebar links while every route still serves, this actually stops the
 * routes from responding — flip the flag back to true to restore them unchanged.
 */
public class LegacyDeskAccessGuard implements HandlerInterceptor {

    private final boolean enabled;

    public LegacyDeskAccessGuard(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (enabled) return true;
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return false;
    }
}
