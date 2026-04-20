package com.cloudchunk.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum TranscodeStatus {

    NONE(0),
    RUNNING(1),
    SUCCESS(2),
    FAILED(3),
    SKIP(4);

    @EnumValue
    private final int code;

    TranscodeStatus(int code) { this.code = code; }

    public int getCode() { return code; }

    public static TranscodeStatus of(int code) {
        for (TranscodeStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("unknown TranscodeStatus: " + code);
    }
}
