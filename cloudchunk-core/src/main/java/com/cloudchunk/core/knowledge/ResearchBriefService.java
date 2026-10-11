package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.Evidence;
import com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * No model calls or stored summaries. Reauthorize every exact user-file pointer
 * and generation when creating a research excerpt. Fail the whole export if a
 * document has been revoked, recycled, replaced or re-indexed.
 */
@Service
public class ResearchBriefService {
    private final KnowledgeService knowledge;

    public record Brief(List<Evidence> evidence, String mode, boolean complete) {}

    public ResearchBriefService(KnowledgeService knowledge) { this.knowledge = knowledge; }

    public Brief prepare(long userId, List<SourceRef> refs) {
        if (refs == null || refs.isEmpty() || refs.size() > 12)
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "select 1-12 evidence pointers");
        Set<String> seen = new HashSet<>();
        List<Evidence> fresh = new ArrayList<>();
        for (SourceRef ref : refs) {
            if (ref == null || ref.userFileId() == null
                    || !ref.userFileId().matches("[1-9][0-9]{0,18}")
                    || ref.generation() < 1 || ref.chunkIndex() < 0 || ref.chunkIndex() >= 64)
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid evidence pointer");
            long id;
            try { id = Long.parseLong(ref.userFileId()); }
            catch (NumberFormatException ignored) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid evidence pointer");
            }
            String key = id + ":" + ref.generation() + ":" + ref.chunkIndex();
            if (!seen.add(key)) throw BizException.of(ErrorCode.INVALID_PARAMETER, "duplicate evidence");
            Evidence evidence = knowledge.source(userId, id, ref.generation(), ref.chunkIndex());
            if (evidence == null) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
            fresh.add(evidence);
        }
        return new Brief(List.copyOf(fresh), "EXACT_AUTHORIZED_EXCERPTS", true);
    }
}
