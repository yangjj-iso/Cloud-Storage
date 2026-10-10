package com.cloudchunk.api.filter;

import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.audit.service.OperationAuditService;
import com.cloudchunk.core.audit.service.DriveOperationHistory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@Component
@Order(20)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OperationAuditFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final OperationAuditService auditService;

    public OperationAuditFilter(OperationAuditService auditService) { this.auditService = auditService; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = request.getRequestURI();
        if (!MUTATING.contains(method) || path == null || !path.startsWith("/api/v1/")) return true;
        if (path.startsWith("/api/v1/auth/")) return true; // never persist authentication payload context
        if (path.equals("/api/v1/upload/chunk") || path.equals("/api/v1/upload/confirm")
                || path.startsWith("/api/v1/upload/dedup/")) return true; // high-volume chunk path
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            long costMs = (System.nanoTime() - started) / 1_000_000L;
            String area = area(request.getRequestURI());
            String opType = DriveOperationHistory.classify(request.getRequestURI(),request.getMethod());
            boolean success = completed && response.getStatus() < 400;
            auditService.record(UserContext.get(), opType, request.getRequestURI(),
                    request.getRemoteAddr(), request.getHeader("User-Agent"), success, costMs);
        }
    }

}
