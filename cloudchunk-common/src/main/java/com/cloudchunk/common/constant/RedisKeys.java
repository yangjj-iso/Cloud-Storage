package com.cloudchunk.common.constant;

public final class RedisKeys {

    /** 分片上传进度 Hash: field=chunkIndex -> value="1" */
    public static final String UPLOAD_PROGRESS = "cc:upload:progress:%s";

    /** 上传会话热缓存 Hash */
    public static final String UPLOAD_SESSION = "cc:upload:session:%s";

    /** 秒传并发幂等锁 SETNX */
    public static final String UPLOAD_INSTANT_LOCK = "cc:upload:lock:%s";

    /** 合并分布式锁 */
    public static final String UPLOAD_MERGE_LOCK = "cc:upload:merge-lock:%s";

    /** 下载预签名 URL 缓存 */
    public static final String FILE_URL = "cc:file:url:%s";

    /** 文件元数据热缓存 Hash */
    public static final String FILE_META = "cc:file:meta:%s";

    /** md5 -> fileId 反查 */
    public static final String FILE_MD5 = "cc:file:md5:%s";

    /** 转码已发送幂等 */
    public static final String TRANSCODE_SENT = "cc:transcode:sent:%s";

    /** 转码已完成幂等 */
    public static final String TRANSCODE_DONE = "cc:transcode:done:%s:%s";

    /** 上传限流（令牌桶，per-user） */
    public static final String RATE_UPLOAD = "cc:rate:upload:%s";

    /** 下载限流（令牌桶，per-user） */
    public static final String RATE_DOWNLOAD = "cc:rate:download:%s";

    public static String uploadProgress(String fileId) {
        return String.format(UPLOAD_PROGRESS, fileId);
    }

    public static String uploadSession(String fileId) {
        return String.format(UPLOAD_SESSION, fileId);
    }

    public static String uploadInstantLock(String fileMd5) {
        return String.format(UPLOAD_INSTANT_LOCK, fileMd5);
    }

    public static String uploadMergeLock(String fileId) {
        return String.format(UPLOAD_MERGE_LOCK, fileId);
    }

    public static String fileUrl(String fileId) {
        return String.format(FILE_URL, fileId);
    }

    public static String fileMeta(String fileId) {
        return String.format(FILE_META, fileId);
    }

    public static String fileMd5(String md5) {
        return String.format(FILE_MD5, md5);
    }

    public static String transcodeSent(String fileId) {
        return String.format(TRANSCODE_SENT, fileId);
    }

    public static String transcodeDone(String fileId, String type) {
        return String.format(TRANSCODE_DONE, fileId, type);
    }

    public static String rateUpload(long userId) {
        return String.format(RATE_UPLOAD, userId);
    }

    public static String rateDownload(long userId) {
        return String.format(RATE_DOWNLOAD, userId);
    }

    private RedisKeys() {}
}
