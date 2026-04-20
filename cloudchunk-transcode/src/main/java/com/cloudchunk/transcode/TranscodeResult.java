package com.cloudchunk.transcode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转码结果 — 以 Map 形式承载不同类型产物，最终序列化为 file_meta.extra。
 */
public class TranscodeResult {

    private final String type;
    private final Map<String, Object> data = new LinkedHashMap<>();
    private String thumbnailUrl;

    private TranscodeResult(String type) { this.type = type; }

    public static TranscodeResult image() { return new TranscodeResult("image"); }
    public static TranscodeResult video() { return new TranscodeResult("video"); }
    public static TranscodeResult doc()   { return new TranscodeResult("doc"); }

    public TranscodeResult put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public TranscodeResult thumbnail(String url) {
        this.thumbnailUrl = url;
        return this;
    }

    public String getType() { return type; }
    public Map<String, Object> getData() { return data; }
    public String getThumbnailUrl() { return thumbnailUrl; }

    public static class Thumb {
        public String label;
        public String key;
        public int size;
        public Thumb() {}
        public Thumb(String label, String key, int size) {
            this.label = label; this.key = key; this.size = size;
        }
    }

    public static List<Thumb> newThumbList() { return new ArrayList<>(); }
}
