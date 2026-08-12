package com.cloudchunk.api.filter;

import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.auth.dto.AuthenticatedPrincipal;
import com.cloudchunk.core.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Resolves the Redis-backed bearer session and owns the per-request user context lifecycle. */
public class AuthenticationContextFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public AuthenticationContextFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null && !token.isBlank()) {
            authService.resolvePrincipal(token).ifPresent(principal -> authenticate(principal, token));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(AuthenticatedPrincipal principal, String token) {
        UserContext.set(principal.userId());
        String role = principal.role() == null ? "USER" : principal.role().toUpperCase(Locale.ROOT);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal.userId(), token, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String bearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) return null;
        if (!auth.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return auth.substring(7).trim();
    }
}
