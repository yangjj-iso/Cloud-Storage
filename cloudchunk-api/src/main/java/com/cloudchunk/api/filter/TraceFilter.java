package com.cloudchunk.api.filter;

import com.cloudchunk.common.constant.CommonConstants;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.auth.service.AuthService;
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public TraceFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(CommonConstants.HEADER_TRACE_ID);
        String traceId = TraceContext.set(incoming);
        response.setHeader(CommonConstants.HEADER_TRACE_ID, traceId);

        String token = bearerToken(request);
        if (token != null && !token.isBlank()) {
            authService.resolveToken(token).ifPresent(UserContext::set);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            TraceContext.clear();
        }
    }

    private String bearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) return null;
        if (!auth.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return auth.substring(7).trim();
    }
}
