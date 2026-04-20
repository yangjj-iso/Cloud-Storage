package com.cloudchunk.common.util;

import java.util.UUID;

public final class IdUtils {

    /** 生成无横线 32 位 UUID */
    public static String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private IdUtils() {}
}
