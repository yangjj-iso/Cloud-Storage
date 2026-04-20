package com.cloudchunk.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum FileStatus {

    UPLOADING(0),
    MERGED(1),
    AVAILABLE(2),
    BROKEN(3),
    DELETED(4);

    @EnumValue
    private final int code;

    FileStatus(int code) { this.code = code; }

    public int getCode() { return code; }

    public static FileStatus of(int code) {
        for (FileStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("unknown FileStatus: " + code);
    }
}
