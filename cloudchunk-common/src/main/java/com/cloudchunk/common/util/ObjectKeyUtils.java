package com.cloudchunk.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class ObjectKeyUtils {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter YEAR_MONTH_DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 分片上传临时 key: upload/{yyyyMMdd}/{fileId}/part.{index:06d} */
    public static String partKey(String fileId, int chunkIndex) {
        return "upload/" + LocalDate.now().format(DATE) + "/" + fileId
                + "/part." + String.format("%06d", chunkIndex);
    }

    /** 分片上传 prefix */
    public static String partPrefix(String fileId) {
        return "upload/" + LocalDate.now().format(DATE) + "/" + fileId + "/";
    }

    /** 最终对象 key: {yyyy/MM/dd}/{fileId}/{fileName} */
    public static String finalKey(String fileId, String fileName) {
        String safe = fileName == null ? "file" : fileName.replaceAll("[\\\\/]+", "_");
        return LocalDate.now().format(YEAR_MONTH_DAY) + "/" + fileId + "/" + safe;
    }

    /** 解析 part.{index} 中的 index，不匹配返回 -1 */
    public static int parsePartIndex(String key) {
        if (key == null) return -1;
        int i = key.lastIndexOf("/part.");
        if (i < 0) return -1;
        try {
            return Integer.parseInt(key.substring(i + 6));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private ObjectKeyUtils() {}
}
