package com.cloudchunk.core.admin.dto;

import com.cloudchunk.core.admin.entity.SystemSetting;

import java.time.LocalDateTime;

public record SettingView(String key, String value, Long updatedBy, LocalDateTime updatedAt) {
    public static SettingView from(SystemSetting setting) {
        return new SettingView(setting.getSettingKey(), setting.getSettingValue(), setting.getUpdatedBy(), setting.getUpdatedAt());
    }
}
