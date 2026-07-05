package com.cloudchunk.core.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.util.IdUtils;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.auth.dto.AuthResponse;
import com.cloudchunk.core.auth.dto.AuthUserResponse;
import com.cloudchunk.core.auth.dto.LoginRequest;
import com.cloudchunk.core.auth.dto.RegisterRequest;
import com.cloudchunk.core.auth.entity.UserAccount;
import com.cloudchunk.core.auth.mapper.UserAccountMapper;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.infra.redis.RedisService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private static final int STATUS_ENABLED = 1;
    private static final int MAX_LOGIN_FAILURES = 10;
    private static final Duration LOGIN_FAILURE_TTL = Duration.ofMinutes(15);

    private final UserAccountMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final RedisService redis;
    private final QuotaService quotaService;
    private final CloudchunkProperties.Auth authProps;

    public AuthService(UserAccountMapper userMapper,
                       PasswordHasher passwordHasher,
                       RedisService redis,
                       QuotaService quotaService,
                       CloudchunkProperties properties) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.redis = redis;
        this.quotaService = quotaService;
        this.authProps = properties.getAuth();
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse register(RegisterRequest req) {
        String username = normalizeUsername(req.getUsername());
        String email = normalizeEmail(req.getEmail());
        ensureUnique(username, email);

        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHasher.hash(req.getPassword()));
        user.setRole("user");
        user.setStatus(STATUS_ENABLED);
        user.setLastLoginAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "username or email already exists");
        }
        quotaService.getOrDefault(user.getId());
        return issueToken(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse login(LoginRequest req) {
        String account = normalizeAccount(req.getAccount());
        String failKey = RedisKeys.authLoginFail(account == null ? "unknown" : account);
        rejectIfLoginLocked(failKey);
        UserAccount user = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, account)
                .or()
                .eq(UserAccount::getEmail, account)
                .last("limit 1"));
        if (user == null || user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            rejectBadCredentials(failKey);
        }
        if (!passwordHasher.matches(req.getPassword(), user.getPasswordHash())) {
            rejectBadCredentials(failKey);
        }
        clearLoginFailures(failKey);
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, user.getId())
                .set(UserAccount::getLastLoginAt, user.getLastLoginAt()));
        // 透明迁移：旧 PBKDF2 哈希在校验通过后重算为 Argon2id 并落库，逐步淘汰旧算法。
        if (passwordHasher.needsUpgrade(user.getPasswordHash())) {
            try {
                userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                        .eq(UserAccount::getId, user.getId())
                        .set(UserAccount::getPasswordHash, passwordHasher.hash(req.getPassword())));
            } catch (Exception ignored) {
                // 升级失败不影响本次登录
            }
        }
        return issueToken(user);
    }

    public Optional<Long> resolveToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String userId = redis.get(RedisKeys.authToken(token));
        if (userId == null || userId.isBlank()) return Optional.empty();
        try {
            long id = Long.parseLong(userId);
            UserAccount user = userMapper.selectById(id);
            if (user == null || user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
                redis.delete(RedisKeys.authToken(token));
                return Optional.empty();
            }
            redis.expire(RedisKeys.authToken(token), tokenTtl());
            return Optional.of(id);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            redis.delete(RedisKeys.authToken(token));
        }
    }

    public AuthUserResponse me(long userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return AuthUserResponse.from(user);
    }

    private AuthResponse issueToken(UserAccount user) {
        String token = IdUtils.uuid32() + IdUtils.uuid32();
        Duration ttl = tokenTtl();
        redis.set(RedisKeys.authToken(token), String.valueOf(user.getId()), ttl);
        return new AuthResponse(token, ttl.toSeconds(), AuthUserResponse.from(user));
    }

    private void ensureUnique(String username, String email) {
        LambdaQueryWrapper<UserAccount> w = new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username);
        if (email != null) {
            w.or().eq(UserAccount::getEmail, email);
        }
        Long count = userMapper.selectCount(w);
        if (count != null && count > 0) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "username or email already exists");
        }
    }

    private Duration tokenTtl() {
        Duration ttl = authProps == null ? null : authProps.getTokenTtl();
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofDays(7) : ttl;
    }

    private void rejectIfLoginLocked(String key) {
        try {
            String value = redis.get(key);
            if (value != null && Long.parseLong(value) >= MAX_LOGIN_FAILURES) {
                throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                        "too many failed login attempts; try again later");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception ignored) {
            // Redis 不可用或计数异常时不阻断登录流程。
        }
    }

    private void rejectBadCredentials(String key) {
        try {
            Long failures = redis.increment(key);
            if (failures != null && failures == 1L) {
                redis.expire(key, LOGIN_FAILURE_TTL);
            }
            if (failures != null && failures >= MAX_LOGIN_FAILURES) {
                throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                        "too many failed login attempts; try again later");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception ignored) {
            // Redis 不可用时仍返回普通认证失败，避免登录服务硬依赖缓存。
        }
        throw BizException.of(ErrorCode.UNAUTHORIZED, "bad credentials");
    }

    private void clearLoginFailures(String key) {
        try {
            redis.delete(key);
        } catch (Exception ignored) {
            // 清理失败不影响成功登录。
        }
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeAccount(String account) {
        return account == null ? null : account.trim().toLowerCase(Locale.ROOT);
    }
}
