package com.cloudchunk.core.admin;

import com.cloudchunk.core.admin.service.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把持久化的可热更新设置（当前：下载限速）加载并应用到运行时组件。
 * DB 未就绪则安静跳过。
 */
@Component
@Order(100)
public class SettingsInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsInitializer.class);

    private final AdminService adminService;

    public SettingsInitializer(AdminService adminService) {
        this.adminService = adminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String v = adminService.getSetting(AdminService.KEY_DOWNLOAD_SPEED_LIMIT);
            if (v != null && !v.isBlank()) {
                adminService.applyLiveSetting(AdminService.KEY_DOWNLOAD_SPEED_LIMIT, v);
                log.info("applied persisted setting {}={}", AdminService.KEY_DOWNLOAD_SPEED_LIMIT, v);
            }
        } catch (Exception e) {
            log.warn("skip loading persisted settings (db not ready?): {}", e.getMessage());
        }
    }
}
