package com.cloudchunk.api.filter;

import com.cloudchunk.common.constant.CommonConstants;
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(CommonConstants.HEADER_TRACE_ID);
        String traceId = TraceContext.set(incoming);
        response.setHeader(CommonConstants.HEADER_TRACE_ID, traceId);

        String userIdHeader = request.getHeader(CommonConstants.HEADER_USER_ID);
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            try {
                UserContext.set(Long.parseLong(userIdHeader));
            } catch (NumberFormatException ignored) {}
        }

        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            TraceContext.clear();
        }
    }
}
