package com.cloudchunk.knowledge;

import com.cloudchunk.core.knowledge.KnowledgePolicy;
import com.cloudchunk.transcode.knowledge.KnowledgeExtractor;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeExtractorTest {
    private final KnowledgeExtractor extractor=new KnowledgeExtractor();
    @Test void extractsPlainTextWithoutAModel() throws Exception {
        var result=extractor.extract(stream("Hello CloudChunk knowledge"),"text/plain");
        assertEquals("READY",result.status());
        assertTrue(result.text().contains("Hello CloudChunk"));
    }
    @Test void reportsTheTextBudgetAsPartialNotSuccessOrRetry() throws Exception {
        var result=extractor.extract(stream("text ".repeat(KnowledgePolicy.MAX_CHARS)),"text/plain");
        assertEquals("PARTIAL",result.status());
        assertTrue(result.text().length() <= KnowledgePolicy.MAX_CHARS);
        assertFalse(result.text().isBlank());
    }
    @Test void refusesArchiveDispatch() {
        assertThrows(IllegalArgumentException.class,() -> extractor.extract(stream("archive"),"application/zip"));
    }
    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
