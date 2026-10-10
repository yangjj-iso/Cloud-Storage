package com.cloudchunk.core.knowledge;

import com.cloudchunk.core.knowledge.KnowledgeModels.CitedSource;
import java.util.List;

/** Models receive only selected evidence, not storage credentials, file IDs or tools. */
public interface ChatGateway {
    boolean configured();
    String complete(String question, List<CitedSource> sources);
    record Completion(String text, ModelTokenUsage usage) {}
    /** Backwards-compatible adapter for alternate implementations without reported usage. */
    default Completion completeWithUsage(String question,List<CitedSource> sources) {
        return new Completion(complete(question,sources),ModelTokenUsage.unknown());
    }
}
