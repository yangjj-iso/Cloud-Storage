package com.cloudchunk.core.knowledge;

import com.cloudchunk.core.knowledge.KnowledgeModels.CitedSource;
import java.util.List;

/** Models receive only selected evidence, not storage credentials, file IDs or tools. */
public interface ChatGateway {
    boolean configured();
    String complete(String question, List<CitedSource> sources);
}
