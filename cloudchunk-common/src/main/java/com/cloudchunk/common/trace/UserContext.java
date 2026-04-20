package com.cloudchunk.common.trace;

import com.cloudchunk.common.constant.CommonConstants;

/**
 * 最简用户上下文：通过 ThreadLocal 承载 userId。
 * 生产环境应接入鉴权体系；此处仅供演示。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    public static void set(Long userId) { CURRENT.set(userId); }

    public static Long get() { return CURRENT.get(); }

    public static long getOrDefault() {
        Long v = CURRENT.get();
        return v == null ? CommonConstants.DEV_USER_ID : v;
    }

    public static void clear() { CURRENT.remove(); }

    private UserContext() {}
}
