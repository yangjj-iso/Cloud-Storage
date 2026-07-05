package com.cloudchunk.common.trace;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;

/**
 * 最简用户上下文：通过 ThreadLocal 承载 userId。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    public static void set(Long userId) { CURRENT.set(userId); }

    public static Long get() { return CURRENT.get(); }

    public static long requireUserId() {
        Long v = CURRENT.get();
        if (v == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return v;
    }

    public static void clear() { CURRENT.remove(); }

    private UserContext() {}
}
