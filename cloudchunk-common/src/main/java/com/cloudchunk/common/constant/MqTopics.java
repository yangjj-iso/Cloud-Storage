package com.cloudchunk.common.constant;

public final class MqTopics {

    public static final String TRANSCODE = "cloudchunk-transcode";
    public static final String CHECKSUM  = "cloudchunk-checksum";
    public static final String BROKEN    = "cloudchunk-broken";

    public static final String TAG_IMG   = "img";
    public static final String TAG_VIDEO = "video";
    public static final String TAG_DOC   = "doc";

    public static final String PG_TRANSCODE = "PG-transcode";
    public static final String PG_CHECKSUM  = "PG-checksum";
    public static final String PG_BROKEN    = "PG-broken";

    public static final String CG_TRANSCODE_IMG   = "CG-transcode-img";
    public static final String CG_TRANSCODE_VIDEO = "CG-transcode-video";
    public static final String CG_TRANSCODE_DOC   = "CG-transcode-doc";
    public static final String CG_CHECKSUM        = "CG-checksum";
    public static final String CG_BROKEN_NOTIFY   = "CG-broken-notify";

    public static final String CG_TRANSCODE_DLQ  = "CG-transcode-dlq";

    /** RocketMQ DLQ topic 命名规则：%DLQ% + 原 ConsumerGroup */
    public static final String DLQ_TRANSCODE_IMG   = "%DLQ%" + CG_TRANSCODE_IMG;
    public static final String DLQ_TRANSCODE_VIDEO = "%DLQ%" + CG_TRANSCODE_VIDEO;
    public static final String DLQ_TRANSCODE_DOC   = "%DLQ%" + CG_TRANSCODE_DOC;

    /** 组合 Topic:Tag 表达式 */
    public static String transcodeDestination(String tag) {
        return TRANSCODE + ":" + tag;
    }

    private MqTopics() {}
}
