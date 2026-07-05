package com.cloudchunk.common.constant;

public final class CommonConstants {

    public static final String API_PREFIX = "/api/v1";

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "traceId";

    /** 默认桶名 */
    public static final String DEFAULT_BUCKET = "cloudchunk";

    /** 默认分片大小 5MB */
    public static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;

    private CommonConstants() {}
}
