package com.cloudchunk.core.auth.dto;

public record AuthenticatedPrincipal(long userId, String role) {
    public boolean admin() { return "admin".equals(role); }
}
