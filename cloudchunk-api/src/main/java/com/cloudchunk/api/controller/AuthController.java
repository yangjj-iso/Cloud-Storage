package com.cloudchunk.api.controller;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.auth.dto.AuthResponse;
import com.cloudchunk.core.auth.dto.AuthUserResponse;
import com.cloudchunk.core.auth.dto.LoginRequest;
import com.cloudchunk.core.auth.dto.RegisterRequest;
import com.cloudchunk.core.auth.service.AuthService;
import com.cloudchunk.core.email.dto.EmailRequests.ChangeEmailRequest;
import com.cloudchunk.core.email.dto.EmailRequests.ResetPasswordRequest;
import com.cloudchunk.core.email.dto.EmailRequests.SendCodeRequest;
import com.cloudchunk.core.email.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailService emailService;

    public AuthController(AuthService authService, EmailService emailService) {
        this.authService = authService;
        this.emailService = emailService;
    }

    @PostMapping("/register")
    public R<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return R.ok(authService.register(req));
    }

    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
    }

    @GetMapping("/me")
    public R<AuthUserResponse> me() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return R.ok(authService.me(userId));
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(extractBearer(authorization));
        return R.ok();
    }

    /** 发送邮箱验证码（register/reset/bind）。公开端点。 */
    @PostMapping("/send-code")
    public R<Void> sendCode(@Valid @RequestBody SendCodeRequest req) {
        emailService.sendCode(req.email(), req.type());
        return R.ok();
    }

    /** 通过邮箱验证码找回密码。公开端点。 */
    @PostMapping("/reset-password")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        emailService.resetPassword(req.email(), req.code(), req.newPassword());
        return R.ok();
    }

    /** 换绑邮箱（先向新邮箱发送 type=bind 验证码）。需登录。 */
    @PostMapping("/change-email")
    public R<Void> changeEmail(@Valid @RequestBody ChangeEmailRequest req) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        emailService.changeEmail(userId, req.newEmail(), req.code());
        return R.ok();
    }

    private String extractBearer(String authorization) {
        if (authorization == null || authorization.isBlank()) return null;
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return authorization.substring(7).trim();
    }
}
