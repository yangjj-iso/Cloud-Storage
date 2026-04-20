package com.cloudchunk.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum UploadSessionStatus {

    RUNNING(0),
    MERGING(1),
    COMPLETED(2),
    FAILED(3),
    EXPIRED(4);

    @EnumValue
    private final int code;

    UploadSessionStatus(int code) { this.code = code; }

    public int getCode() { return code; }

    public static UploadSessionStatus of(int code) {
        for (UploadSessionStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("unknown UploadSessionStatus: " + code);
    }
}
