package com.cloudchunk.core.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudchunk.core.auth.entity.UserAccount;
import com.cloudchunk.core.auth.mapper.UserAccountMapper;
import com.cloudchunk.core.auth.service.PasswordHasher;
import com.cloudchunk.core.quota.service.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时可选创建一个超级管理员账号。默认关闭，生产环境必须通过环境变量显式提供初始账号。
 * 若数据库尚未就绪则安静跳过，不影响应用启动。
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserAccountMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final QuotaService quotaService;
    private final boolean seedEnabled;
    private final String seedUsername;
    private final String seedEmail;
    private final String seedPassword;

    public AdminSeeder(UserAccountMapper userMapper,
                       PasswordHasher passwordHasher,
                       QuotaService quotaService,
                       @Value("${cloudchunk.admin.seed-enabled:false}") boolean seedEnabled,
                       @Value("${cloudchunk.admin.seed-username:}") String seedUsername,
                       @Value("${cloudchunk.admin.seed-email:admin@cloudchunk.local}") String seedEmail,
                       @Value("${cloudchunk.admin.seed-password:}") String seedPassword) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.quotaService = quotaService;
        this.seedEnabled = seedEnabled;
        this.seedUsername = seedUsername;
        this.seedEmail = seedEmail;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            return;
        }
        if (seedUsername == null || seedUsername.isBlank()
                || seedPassword == null || seedPassword.length() < 12) {
            log.warn("admin seed enabled but username/password are invalid; password must be at least 12 chars");
            return;
        }
        try {
            Long count = userMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                    .eq(UserAccount::getRole, "admin"));
            if (count != null && count > 0) {
                return;
            }
            UserAccount admin = new UserAccount();
            admin.setUsername(seedUsername.trim().toLowerCase());
            admin.setEmail(seedEmail == null || seedEmail.isBlank() ? null : seedEmail.trim().toLowerCase());
            admin.setPasswordHash(passwordHasher.hash(seedPassword));
            admin.setRole("admin");
            admin.setStatus(1);
            admin.setLastLoginAt(LocalDateTime.now());
            userMapper.insert(admin);
            quotaService.getOrDefault(admin.getId());
            log.info("seeded super-admin account: {}", admin.getUsername());
        } catch (Exception e) {
            log.warn("skip admin seed (db not ready?): {}", e.getMessage());
        }
    }
}
