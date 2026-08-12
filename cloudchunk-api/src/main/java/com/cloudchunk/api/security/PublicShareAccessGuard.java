package com.cloudchunk.api.security;

import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.infra.redis.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
public class PublicShareAccessGuard {
    private static final Pattern SHARE_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{16,32}$");
    private final RateLimiter rateLimiter;

    public PublicShareAccessGuard(RateLimiter rateLimiter) { this.rateLimiter = rateLimiter; }

    public void check(String shareId, HttpServletRequest request) {
        if (shareId == null || !SHARE_ID_PATTERN.matcher(shareId).matches()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid shareId");
        }
        String client = fingerprint(resolveClientAddress(request));
        if (!rateLimiter.tryAcquire(RedisKeys.rateShare(shareId, client), 1, 5)) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String resolveClientAddress(HttpServletRequest request) {
        if (request == null) return "unknown";
        String remote = clean(request.getRemoteAddr());
        if (trustedProxy(remote)) {
            String forwarded = firstForwardedFor(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) return forwarded;
            String realIp = clean(request.getHeader("X-Real-IP"));
            if (realIp != null) return realIp;
        }
        return remote == null ? "unknown" : remote;
    }

    private String firstForwardedFor(String header) {
        if (header == null || header.isBlank()) return null;
        for (String part : header.split(",")) {
            String cleaned = clean(part);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() || cleaned.length() > 128 ? null : cleaned;
    }

    private boolean trustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return false;
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            return address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
