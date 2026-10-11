package com.cloudchunk.api.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/** HTTP acceptance metrics are distinct from asynchronous file availability. */
@Component
@Order(20)
public class BusinessSliHttpFilter extends OncePerRequestFilter {
    private static final Pattern MERGE = Pattern.compile("^/api/v1/upload/merge/[0-9a-fA-F]{32}$");
    private static final Pattern URL = Pattern.compile("^/api/v1/file/[0-9a-fA-F]{32}/url$");
    private final BusinessSliMetrics metrics;

    public BusinessSliHttpFilter(BusinessSliMetrics metrics) { this.metrics = metrics; }

    static String operationFor(String method, String path) {
        if ("POST".equals(method) && MERGE.matcher(path).matches()) return "upload_merge";
        if ("GET".equals(method) && URL.matcher(path).matches()) return "download_url";
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String operation = operationFor(request.getMethod(), request.getRequestURI());
        if (operation == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean thrown = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            thrown = true;
            throw ex;
        } finally {
            int status = response.getStatus();
            String result = thrown || status >= 500 ? "server_error"
                    : status >= 400 ? "client_error" : "accepted";
            metrics.operation(operation, result);
        }
    }
}
