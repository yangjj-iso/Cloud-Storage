package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.Evidence;
import com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchBriefServiceTest {
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ResearchBriefService brief = new ResearchBriefService(knowledge);

    @Test void exportContainsOnlyFreshOwnedEvidenceAndNoModelCalls() {
        when(knowledge.source(11L, 102L, 3, 1)).thenReturn(
            new Evidence("102", "authorized.txt", 3, 1, 0, 100, "fresh text", "READY"));
        var result = brief.prepare(11, List.of(new SourceRef("102", 3, 1)));
        assertEquals("EXACT_AUTHORIZED_EXCERPTS", result.mode());
        assertEquals("fresh text", result.evidence().getFirst().content());
        verify(knowledge).source(11L, 102L, 3, 1);
        verifyNoMoreInteractions(knowledge);
    }

    @Test void unauthorizedRevokedOrStalePointerBlocksAllEvidence() {
        when(knowledge.source(11L, 102L, 3, 1)).thenReturn(
            new Evidence("102", "allowed.txt", 3, 1, 0, 100, "one", "READY"));
        assertEquals(ErrorCode.AI_SOURCE_CHANGED,
            assertThrows(BizException.class, () -> brief.prepare(11, List.of(
                new SourceRef("102",3,1), new SourceRef("103",3,1)))).getErrorCode());
        // Never return the first result in a partially completed export.
        verify(knowledge).source(11L, 103L, 3, 1);
    }

    @Test void rejectsAliasOverflowDuplicatesAndTooManyWithoutDbLookup() {
        for (String bad : List.of("001", "-2", "9223372036854775808", "1e2", "0")) {
            assertThrows(BizException.class, () ->
                brief.prepare(11, List.of(new SourceRef(bad, 2, 0))));
        }
        var key = new SourceRef("102",3,1);
        assertThrows(BizException.class, () -> brief.prepare(11, List.of(key,key)));
        assertThrows(BizException.class, () -> brief.prepare(11, List.of()));
        assertThrows(BizException.class, () -> brief.prepare(11, List.of(new SourceRef("102",3,64))));
        assertThrows(BizException.class, () -> brief.prepare(11,
            java.util.Collections.nCopies(13,new SourceRef("102",3,0))));
        verifyNoInteractions(knowledge);
    }
}
