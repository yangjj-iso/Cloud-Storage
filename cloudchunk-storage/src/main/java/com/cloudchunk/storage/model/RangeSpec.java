package com.cloudchunk.storage.model;

/**
 * HTTP Range 请求头解析结果。
 *
 * <ul>
 *   <li>{@code bytes=0-1023}  -> start=0, end=1023</li>
 *   <li>{@code bytes=1024-}   -> start=1024, end=total-1</li>
 *   <li>{@code bytes=-500}    -> start=total-500, end=total-1</li>
 * </ul>
 */
public record RangeSpec(long start, long end, boolean valid, boolean isFull) {

    public static RangeSpec full(long total) {
        return new RangeSpec(0, Math.max(total - 1, 0), true, true);
    }

    public static RangeSpec invalid() {
        return new RangeSpec(0, 0, false, false);
    }

    public static RangeSpec parse(String header, long total) {
        if (header == null || header.isBlank()) return full(total);
        if (!header.startsWith("bytes=")) return invalid();

        String value = header.substring(6).trim();
        int comma = value.indexOf(',');
        if (comma > 0) value = value.substring(0, comma).trim(); // 仅取第一段

        String[] parts = value.split("-", -1);
        if (parts.length != 2) return invalid();

        long start;
        long end;
        try {
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1].trim());
                if (suffix <= 0) return invalid();
                start = Math.max(total - suffix, 0);
                end = total - 1;
            } else if (parts[1].isEmpty()) {
                start = Long.parseLong(parts[0].trim());
                end = total - 1;
            } else {
                start = Long.parseLong(parts[0].trim());
                end = Math.min(Long.parseLong(parts[1].trim()), total - 1);
            }
        } catch (NumberFormatException e) {
            return invalid();
        }

        if (total <= 0 || start < 0 || start > end || start >= total) {
            return invalid();
        }
        return new RangeSpec(start, end, true, false);
    }

    public long length() {
        return end - start + 1;
    }
}
