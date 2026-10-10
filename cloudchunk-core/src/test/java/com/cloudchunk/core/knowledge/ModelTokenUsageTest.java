package com.cloudchunk.core.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelTokenUsageTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void parsesOnlyRealReportedCountsWithoutEstimatingFromText() throws Exception {
        var chat=ModelTokenUsage.fromChat(mapper.readTree(
            "{\"usage\":{\"prompt_tokens\":150,\"completion_tokens\":38}}"));
        assertTrue(chat.reported());
        assertEquals(150,chat.inputTokens());
        assertEquals(38,chat.outputTokens());

        var embedding=ModelTokenUsage.fromEmbedding(mapper.readTree(
            "{\"data\":[],\"usage\":{\"prompt_tokens\":22,\"total_tokens\":22}}"));
        assertEquals(22,embedding.inputTokens());
        assertEquals(0,embedding.outputTokens());
        assertTrue(embedding.reported());

        assertFalse(ModelTokenUsage.fromChat(mapper.readTree("{}")).reported());
        assertFalse(ModelTokenUsage.fromChat(mapper.readTree(
            "{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":3}}")).reported());
        assertFalse(ModelTokenUsage.fromChat(mapper.readTree(
            "{\"usage\":{\"prompt_tokens\":\"200\",\"completion_tokens\":3}}")).reported());
        assertFalse(ModelTokenUsage.fromEmbedding(mapper.readTree(
            "{\"usage\":{\"prompt_tokens\":999999999999}}")).reported());
    }
}
