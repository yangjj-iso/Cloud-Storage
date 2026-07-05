package com.cloudchunk.api.filter;

import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.auth.entity.UserAccount;
import com.cloudchunk.core.auth.mapper.UserAccountMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理端鉴权：/api/v1/admin/** 仅允许 role=admin。运行在 AuthFilter 之后，
 * 此时 UserContext 已由 TraceFilter 填充、AuthFilter 已确保登录。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final UserAccountMapper userMapper;

    public AdminAuthFilter(UserAccountMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/admin")) {
            chain.doFilter(request, response);
            return;
        }
        Long userId = UserContext.get();
        boolean ok = false;
        if (userId != null) {
            UserAccount u = userMapper.selectById(userId);
            ok = u != null
                    && u.getStatus() != null
                    && u.getStatus() == 1
                    && "admin".equals(u.getRole());
        }
        if (!ok) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.FORBIDDEN;
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        String traceId = TraceContext.get();
        response.getWriter().write("{\"code\":" + code.getCode()
                + ",\"message\":\"admin access required\""
                + (traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"")
                + "}");
    }
}
