package com.cloudchunk.core.auth.dto;

import com.cloudchunk.core.auth.entity.UserAccount;

import java.time.LocalDateTime;

public class AuthUserResponse {

    private Long id;
    private String username;
    private String email;
    private String role;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public static AuthUserResponse from(UserAccount user) {
        AuthUserResponse r = new AuthUserResponse();
        r.setId(user.getId());
        r.setUsername(user.getUsername());
        r.setEmail(user.getEmail());
        r.setRole(user.getRole() == null ? "user" : user.getRole());
        r.setLastLoginAt(user.getLastLoginAt());
        r.setCreatedAt(user.getCreatedAt());
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
