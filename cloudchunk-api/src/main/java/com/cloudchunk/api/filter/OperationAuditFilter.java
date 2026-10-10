package com.cloudchunk.api.filter;

import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.audit.service.OperationAuditService;
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
            String opType = operation(request.getRequestURI(),request.getMethod());
            boolean success = completed && response.getStatus() < 400;
            // No request path, file IDs or filenames in audit rows. Operation names are fixed.
            auditService.record(UserContext.get(), opType, null,
                    request.getRemoteAddr(), request.getHeader("User-Agent"), success, costMs);
        }
    }

    static String operation(String path,String method) {
        if(path==null)return "API_OTHER";
        if(path.equals("/api/v1/drive/mkdir"))return "DRIVE_MKDIR";
        if(path.equals("/api/v1/drive/rename"))return "DRIVE_RENAME";
        if(path.equals("/api/v1/drive/move"))return "DRIVE_MOVE";
        if(path.matches("/api/v1/drive/[0-9]+") && "DELETE".equalsIgnoreCase(method))
            return "DRIVE_RECYCLE";
        if(path.matches("/api/v1/recycle/[0-9]+/restore"))return "DRIVE_RESTORE";
        if(path.matches("/api/v1/recycle/[0-9]+") && "DELETE".equalsIgnoreCase(method))
            return "DRIVE_PURGE";
        String remainder=path.startsWith("/api/v1/")?path.substring("/api/v1/".length()):"API";
        String area=remainder.split("/")[0].replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);
        return (area.isBlank()?"API":area)+"_"+method.toUpperCase(Locale.ROOT);
    }
    private String area(String path) {
        if (path == null) return "API";
        String remainder = path.substring(Math.min(path.length(), "/api/v1/".length()));
        int slash = remainder.indexOf('/');
        String area = slash < 0 ? remainder : remainder.substring(0, slash);
        String normalized = area.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "API" : normalized;
    }
}
