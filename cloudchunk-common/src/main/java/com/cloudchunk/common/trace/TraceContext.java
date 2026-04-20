package com.cloudchunk.common.trace;

import com.cloudchunk.common.constant.CommonConstants;
import com.cloudchunk.common.util.IdUtils;
import org.slf4j.MDC;

public final class TraceContext {

    public static String get() {
        String id = MDC.get(CommonConstants.MDC_TRACE_ID);
        return id == null ? "-" : id;
    }

    public static String set(String traceId) {
        String id = (traceId == null || traceId.isBlank()) ? IdUtils.uuid32() : traceId;
        MDC.put(CommonConstants.MDC_TRACE_ID, id);
        return id;
    }

    public static String setIfAbsent() {
        String cur = MDC.get(CommonConstants.MDC_TRACE_ID);
        if (cur == null || cur.isBlank()) {
            return set(null);
        }
        return cur;
    }

    public static void clear() {
        MDC.remove(CommonConstants.MDC_TRACE_ID);
    }

    private TraceContext() {}
}
