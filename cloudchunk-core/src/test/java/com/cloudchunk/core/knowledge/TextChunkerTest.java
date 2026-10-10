package com.cloudchunk.core.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {
    @Test void preservesOffsetsAndUnicode() {
        String text = ("需求文档😀\n".repeat(1500)) + "最后一段";
        var chunks = TextChunker.split(text, 1200);
        StringBuilder rebuilt = new StringBuilder();
        for (var c : chunks) {
            assertEquals(text.substring(c.startOffset(),c.endOffset()),c.content());
            assertTrue(c.content().length() <= 1200);
            assertFalse(Character.isHighSurrogate(c.content().charAt(c.content().length()-1)));
            assertFalse(Character.isLowSurrogate(c.content().charAt(0)));
            rebuilt.append(c.content());
        }
        assertEquals(text,rebuilt.toString());
        assertEquals(chunks,TextChunker.split(text,1200));
    }
    @Test void handlesEmptyAndRejectsUnsafeLimits() {
        assertTrue(TextChunker.split(null,1200).isEmpty());
        assertTrue(TextChunker.split("  ",1200).isEmpty());
        assertThrows(IllegalArgumentException.class,() -> TextChunker.split("x",1));
        assertThrows(IllegalArgumentException.class,() -> TextChunker.split("x",100000));
    }
    @Test void acceptsOnlyExplicitDocumentMimes() {
        assertTrue(KnowledgePolicy.supported("Text/Plain; charset=UTF-8"));
        assertFalse(KnowledgePolicy.supported("application/zip"));
        assertFalse(KnowledgePolicy.supported(null));
    }
}
