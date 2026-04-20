package com.cloudchunk.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ChunkStatus {

    PENDING(0),
    DONE(1),
    FAILED(2);

    @EnumValue
    private final int code;

    ChunkStatus(int code) { this.code = code; }

    public int getCode() { return code; }
}
