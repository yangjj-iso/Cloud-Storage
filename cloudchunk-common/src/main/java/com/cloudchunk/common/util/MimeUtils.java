package com.cloudchunk.common.util;

import java.util.Locale;

public final class MimeUtils {

    public static final String OCTET_STREAM = "application/octet-stream";

    public static String extOf(String fileName) {
        if (fileName == null) return null;
        int i = fileName.lastIndexOf('.');
        if (i < 0 || i == fileName.length() - 1) return null;
        return fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** 判定 MIME 类型大类，用于 MQ Tag 分发 */
    public static String tag(String mimeType) {
        if (mimeType == null) return null;
        String m = mimeType.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) return "img";
        if (m.startsWith("video/")) return "video";
        if (m.startsWith("application/pdf")
                || m.startsWith("application/msword")
                || m.startsWith("application/vnd.openxmlformats-officedocument")
                || m.startsWith("application/vnd.ms-")
                || m.startsWith("text/")) {
            return "doc";
        }
        return null;
    }

    private MimeUtils() {}
}
