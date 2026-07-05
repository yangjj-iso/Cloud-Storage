package com.cloudchunk.core.auth.dto;

public class AuthResponse {

    private String token;
    private String tokenType;
    private long expiresInSeconds;
    private AuthUserResponse user;

    public AuthResponse() {}

    public AuthResponse(String token, long expiresInSeconds, AuthUserResponse user) {
        this.token = token;
        this.tokenType = "Bearer";
        this.expiresInSeconds = expiresInSeconds;
        this.user = user;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public long getExpiresInSeconds() { return expiresInSeconds; }
    public void setExpiresInSeconds(long expiresInSeconds) { this.expiresInSeconds = expiresInSeconds; }
    public AuthUserResponse getUser() { return user; }
    public void setUser(AuthUserResponse user) { this.user = user; }
}
