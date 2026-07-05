package com.cloudchunk.api.filter;

import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.common.trace.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!requiresAuth(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (UserContext.get() == null) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean requiresAuth(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return false;
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) return false;
        if (path.equals("/api/v1/ping")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/send-code")
                || path.equals("/api/v1/auth/reset-password")) {
            return false;
        }
        // 公开分享访问（需提取码，不需要登录）：GET /api/v1/share/{id}[/children|/download-zip]。
        // /api/v1/share/list 是“我的分享列表”，需登录，故排除。
        if (isPublicShare(request.getMethod(), path)) {
            return false;
        }
        return true;
    }

    private boolean isPublicShare(String method, String path) {
        if (!"GET".equalsIgnoreCase(method)) return false;
        if (!path.startsWith("/api/v1/share/")) return false;
        return !path.equals("/api/v1/share/list");
    }

    private void reject(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.UNAUTHORIZED;
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        String traceId = TraceContext.get();
        response.getWriter().write("{\"code\":" + code.getCode()
                + ",\"message\":\"" + code.getMessage() + "\""
                + (traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"")
                + "}");
    }
}
