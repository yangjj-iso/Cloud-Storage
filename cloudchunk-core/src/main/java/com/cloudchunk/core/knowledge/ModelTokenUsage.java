package com.cloudchunk.core.knowledge;

import com.fasterxml.jackson.databind.JsonNode;

/** A provider-reported count, never guessed from character length. */
public record ModelTokenUsage(long inputTokens, long outputTokens, boolean reported) {
    private static final long MAX_TOKENS=100_000_000L;
    public static ModelTokenUsage unknown() { return new ModelTokenUsage(0,0,false); }

    public static ModelTokenUsage fromChat(JsonNode response) {
        JsonNode usage=response.path("usage");
        Long input=valid(usage.path("prompt_tokens"));
        Long output=valid(usage.path("completion_tokens"));
        return input==null || output==null ? unknown() : new ModelTokenUsage(input,output,true);
    }

    public static ModelTokenUsage fromEmbedding(JsonNode response) {
        Long input=valid(response.path("usage").path("prompt_tokens"));
        return input==null ? unknown() : new ModelTokenUsage(input,0,true);
    }

    private static Long valid(JsonNode node) {
        if(!node.isIntegralNumber() || !node.canConvertToLong())return null;
        long number=node.asLong();
        return number>=0 && number<=MAX_TOKENS ? number:null;
    }
}
