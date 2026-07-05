package com.cloudchunk.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class ObjectKeyUtils {

    private static final DateTimeFormatter YEAR_MONTH_DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 分片上传临时 key: upload/{fileId}/part.{index:06d} */
    public static String partKey(String fileId, int chunkIndex) {
        // 分片对象只在上传会话期间存在；合并后会异步删除。
        // index 用 6 位补零，便于人眼查看和按序排查。
        return "upload/" + fileId + "/part." + String.format("%06d", chunkIndex);
    }

    /** 分片上传 prefix */
    public static String partPrefix(String fileId) {
        // 用于列出某次上传会话下的所有临时分片，例如 Redis 进度丢失后的重建。
        return "upload/" + fileId + "/";
    }

    /** 最终对象 key: {yyyy/MM/dd}/{fileId}/{fileName} */
    public static String finalKey(String fileId, String fileName) {
        // 最终对象保留原始文件名，但会替换路径分隔符，防止文件名伪造成目录路径。
        String safe = fileName == null ? "file" : fileName.replaceAll("[\\\\/]+", "_");
        return LocalDate.now().format(YEAR_MONTH_DAY) + "/" + fileId + "/" + safe;
    }

    /** 解析 part.{index} 中的 index，不匹配返回 -1 */
    public static int parsePartIndex(String key) {
        // list(storagePrefix) 返回的是对象 key 字符串，重建进度时需要反解出 chunkIndex。
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
