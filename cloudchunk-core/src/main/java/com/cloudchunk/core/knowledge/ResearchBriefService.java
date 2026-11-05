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
        record Pointer(long userFileId, long generation, int chunkIndex) {}
        List<Pointer> pointers = new ArrayList<>();
        // Validate the WHOLE set before reading any private document content.
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
            pointers.add(new Pointer(id, ref.generation(), ref.chunkIndex()));
        }
        List<Evidence> fresh = new ArrayList<>();
        for (Pointer pointer : pointers) {
            Evidence evidence = knowledge.source(userId, pointer.userFileId(), pointer.generation(), pointer.chunkIndex());
            if (evidence == null) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
            fresh.add(evidence);
        }
        return new Brief(List.copyOf(fresh), "EXACT_AUTHORIZED_EXCERPTS", true);
    }
}
