package com.cloudchunk.core.email.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.auth.entity.UserAccount;
import com.cloudchunk.core.auth.mapper.UserAccountMapper;
import com.cloudchunk.core.auth.service.PasswordHasher;
import com.cloudchunk.core.email.entity.EmailVerification;
import com.cloudchunk.core.email.mapper.EmailVerificationMapper;
import com.cloudchunk.infra.redis.RedisService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 邮箱验证码：注册 / 找回密码 / 换绑邮箱。
 */
@Service
public class EmailService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_VERIFY_FAILURES = 10;
    private static final Duration VERIFY_FAILURE_TTL = Duration.ofMinutes(10);

    private final EmailVerificationMapper mapper;
    private final UserAccountMapper userMapper;
    private final RedisService redis;
    private final Mailer mailer;
    private final PasswordHasher passwordHasher;

    public EmailService(EmailVerificationMapper mapper,
                        UserAccountMapper userMapper,
                        RedisService redis,
                        Mailer mailer,
                        PasswordHasher passwordHasher) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.redis = redis;
        this.mailer = mailer;
        this.passwordHasher = passwordHasher;
    }

    public void sendCode(String email, String type) {
        String em = normalize(email);
        if (em == null) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "email is required");
        }
        if (!"register".equals(type) && !"reset".equals(type) && !"bind".equals(type)) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid code type");
        }

        // 限流：同一邮箱同一用途 60 秒一次。用 SETNX 原子占位，避免 get-then-set 竞态。
        String rlKey = RedisKeys.emailRateLimit(type, em);
        try {
            Boolean acquired = redis.setIfAbsent(rlKey, "1", Duration.ofSeconds(60));
            if (Boolean.FALSE.equals(acquired)) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "please wait 60 seconds before requesting another code");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception ignored) {
            // Redis 不可用时不阻断发码
        }

        UserAccount byEmail = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, em).last("limit 1"));
        if (("register".equals(type) || "bind".equals(type)) && byEmail != null) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "email already registered");
        }
        if ("reset".equals(type) && byEmail == null) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "email not found");
        }

        String code = genDigits(6);
        EmailVerification ev = new EmailVerification();
        ev.setEmail(em);
        ev.setCode(code);
        ev.setType(type);
        ev.setUsed(false);
        ev.setExpireAt(LocalDateTime.now().plusMinutes(10));
        mapper.insert(ev);

        mailer.sendCode(em, code, type);
    }

    public void verifyCode(String email, String code, String type) {
        String em = normalize(email);
        if (em == null || code == null || code.isBlank()
                || (!"register".equals(type) && !"reset".equals(type) && !"bind".equals(type))) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid verification request");
        }
        String failKey = RedisKeys.emailVerifyFail(type, em);
        rejectIfVerifyLocked(failKey);
        EmailVerification ev = mapper.selectOne(new LambdaQueryWrapper<EmailVerification>()
                .eq(EmailVerification::getEmail, em)
                .eq(EmailVerification::getCode, code)
                .eq(EmailVerification::getType, type)
                .eq(EmailVerification::getUsed, false)
                .gt(EmailVerification::getExpireAt, LocalDateTime.now())
                .orderByDesc(EmailVerification::getId)
                .last("limit 1"));
        if (ev == null) {
            recordVerifyFailure(failKey);
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid or expired verification code");
        }
        int updated = mapper.update(null, new LambdaUpdateWrapper<EmailVerification>()
                .eq(EmailVerification::getId, ev.getId())
                .eq(EmailVerification::getUsed, false)
                .set(EmailVerification::getUsed, true));
        if (updated <= 0) {
            recordVerifyFailure(failKey);
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid or expired verification code");
        }
        clearVerifyFailures(failKey);
    }

    public void changeEmail(long userId, String newEmail, String code) {
        String em = normalize(newEmail);
        verifyCode(em, code, "bind");
        UserAccount byEmail = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, em).last("limit 1"));
        if (byEmail != null) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "email already registered");
        }
        userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, userId)
                .set(UserAccount::getEmail, em));
    }

    public void resetPassword(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "new password must be at least 8 characters");
        }
        String em = normalize(email);
        verifyCode(em, code, "reset");
        UserAccount user = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, em).last("limit 1"));
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "email not found");
        }
        userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, user.getId())
                .set(UserAccount::getPasswordHash, passwordHasher.hash(newPassword)));
    }

    private static String normalize(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void rejectIfVerifyLocked(String key) {
        try {
            String value = redis.get(key);
            if (value != null && Long.parseLong(value) >= MAX_VERIFY_FAILURES) {
                throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                        "too many verification attempts; request a new code later");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception ignored) {
            // Redis 不可用或计数异常时不阻断验证码校验。
        }
    }

    private void recordVerifyFailure(String key) {
        try {
            Long failures = redis.increment(key);
            if (failures != null && failures == 1L) {
                redis.expire(key, VERIFY_FAILURE_TTL);
            }
            if (failures != null && failures >= MAX_VERIFY_FAILURES) {
                throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                        "too many verification attempts; request a new code later");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception ignored) {
            // Redis 不可用时仍返回普通验证码错误。
        }
    }

    private void clearVerifyFailures(String key) {
        try {
            redis.delete(key);
        } catch (Exception ignored) {
            // 清理失败不影响验证码消费成功。
        }
    }

    private static String genDigits(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('0' + RNG.nextInt(10)));
        }
        return sb.toString();
    }
}
