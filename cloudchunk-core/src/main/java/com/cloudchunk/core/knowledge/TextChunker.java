package com.cloudchunk.core.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Offsets address extracted UTF-16 text, NOT PDF pages or DOCX layout. No character is discarded. */
public final class TextChunker {
    public record Chunk(int index, int startOffset, int endOffset, String content) {}
    private TextChunker() {}

    public static List<Chunk> split(String text, int maxChars) {
        if (maxChars < 2 || maxChars > 8192) throw new IllegalArgumentException("invalid chunk size");
        if (text == null || text.isBlank()) return List.of();
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int paragraph = text.lastIndexOf('\n', end - 1);
                if (paragraph >= start + maxChars / 2) end = paragraph + 1;
                if (Character.isHighSurrogate(text.charAt(end - 1))
                        && Character.isLowSurrogate(text.charAt(end))) end--;
            }
            result.add(new Chunk(result.size(), start, end, text.substring(start, end)));
            start = end;
        }
        return List.copyOf(result);
    }
}
