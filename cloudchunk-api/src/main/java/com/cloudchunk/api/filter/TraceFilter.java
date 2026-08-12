package com.cloudchunk.api.filter;

import com.cloudchunk.common.constant.CommonConstants;
import com.cloudchunk.common.trace.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class TraceFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public TraceFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(CommonConstants.HEADER_TRACE_ID);
        Span currentSpan = tracer == null ? null : tracer.currentSpan();
        String traceId = currentSpan == null || currentSpan.isNoop()
                ? TraceContext.set(incoming)
                : TraceContext.set(currentSpan.context().traceId());
        response.setHeader(CommonConstants.HEADER_TRACE_ID, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
