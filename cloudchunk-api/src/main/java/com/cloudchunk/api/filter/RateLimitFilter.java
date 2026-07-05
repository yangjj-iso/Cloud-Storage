package com.cloudchunk.api.filter;

import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.infra.redis.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 基于 Redis 令牌桶的 per-user 限流过滤器。
 */
@Component
@Order(10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final CloudchunkProperties.RateLimit cfg;
    private final Counter uploadRejected;
    private final Counter downloadRejected;

    public RateLimitFilter(RateLimiter rateLimiter,
                           CloudchunkProperties props,
                           MeterRegistry registry) {
        this.rateLimiter = rateLimiter;
        this.cfg = props.getRateLimit();
        this.uploadRejected = Counter.builder("cloudchunk.rate_limit.rejected")
                .tag("endpoint", "upload_chunk")
                .description("Upload chunk requests rejected by rate limiter")
                .register(registry);
        this.downloadRejected = Counter.builder("cloudchunk.rate_limit.rejected")
                .tag("endpoint", "download")
                .description("Download requests rejected by rate limiter")
                .register(registry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!cfg.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // 上传分片限流
        if ("POST".equalsIgnoreCase(method) && path.contains("/upload/chunk")) {
            Long userId = UserContext.get();
            if (userId == null) {
                rejectUnauthorized(response);
                return;
            }
            if (!rateLimiter.tryAcquire(
                    RedisKeys.rateUpload(userId),
                    cfg.getUploadChunkRps(),
                    cfg.getUploadChunkBurst())) {
                uploadRejected.increment();
                log.debug("rate-limit upload userId={}", userId);
                reject(response);
                return;
            }
        }

        // 下载限流
        if ("GET".equalsIgnoreCase(method) && path.contains("/file/") && path.endsWith("/download")) {
            Long userId = UserContext.get();
            if (userId == null) {
                rejectUnauthorized(response);
                return;
            }
            if (!rateLimiter.tryAcquire(
                    RedisKeys.rateDownload(userId),
                    cfg.getDownloadRps(),
                    cfg.getDownloadBurst())) {
                downloadRejected.increment();
                log.debug("rate-limit download userId={}", userId);
                reject(response);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"Too Many Requests\"}");
    }

    private void rejectUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"Unauthorized\"}");
    }
}
