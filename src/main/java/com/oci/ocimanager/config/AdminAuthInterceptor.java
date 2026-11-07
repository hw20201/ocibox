package com.oci.ocimanager.config;

import com.oci.ocimanager.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 🛡️ 全局后台集中鉴权拦截器 (Defense-in-Depth)
 * 拦截所有 /api/admin/** 敏感运维接口，提供请求头级快速熔断拦截。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AdminAuthService adminAuthService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 纵深防御：对会改状态的请求做同源检查。浏览器同源请求会带与本站一致的 Origin/Referer；
        // 非浏览器调用（如 curl）通常不带 Origin/Referer，放行（Token 鉴权仍在后面）。
        // 只比对 host（含端口），不比对 scheme：反代终止 TLS 时后端看到的 scheme 是 http。
        if (isStateChanging(request.getMethod()) && !isSameOrigin(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"跨站请求已被拒绝\"}");
            return false;
        }

        String token = request.getHeader("X-Admin-Token");
        if (token == null || token.isBlank()) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                token = authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader.trim();
            }
        }

        // 🔒 强制拦截所有受保护后台接口：缺失请求头 Token 或会话无效一律拒绝，消除空 Header 绕过隐患
        if (token == null || token.isBlank() || !adminAuthService.checkAuth(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"会话已过期或未授权，请重新登录\"}");
            return false;
        }

        return true;
    }

    private static boolean isStateChanging(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    }

    private static boolean isSameOrigin(HttpServletRequest request) {
        String hostHeader = request.getHeader("Host");
        if (hostHeader == null || hostHeader.isBlank()) return true;
        String host = stripPort(hostHeader.trim());
        String originHost = extractHost(request.getHeader("Origin"));
        if (originHost != null) return originHost.equalsIgnoreCase(host);
        String refererHost = extractHost(request.getHeader("Referer"));
        if (refererHost != null) return refererHost.equalsIgnoreCase(host);
        return true;
    }

    private static String extractHost(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = new java.net.URI(url.trim()).getHost();
            return (host == null || host.isBlank()) ? null : host;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripPort(String hostHeader) {
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            return end > 0 ? hostHeader.substring(1, end) : hostHeader;
        }
        int colon = hostHeader.indexOf(':');
        return colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
    }
}

