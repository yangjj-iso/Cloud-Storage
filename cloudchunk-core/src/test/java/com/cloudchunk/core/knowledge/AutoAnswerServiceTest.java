package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoAnswerServiceTest {
    private final HybridSearchService search = mock(HybridSearchService.class);
    private final GroundedAnswerService answer = mock(GroundedAnswerService.class);
    private final AutoAnswerService auto = new AutoAnswerService(search, answer);

    @Test void disabledOrUnconsentedNeverSearchesOrCallsModel() {
        assertEquals(ErrorCode.AI_DISABLED, assertThrows(BizException.class,
            () -> auto.ask(7, new AutoAskRequest("上传完整性如何保证",true))).getErrorCode());
        when(answer.capabilities()).thenReturn(new AssistantCapabilities(true,"SELECTED_KEYWORD_EVIDENCE"));
        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(BizException.class,
            () -> auto.ask(7, new AutoAskRequest("上传完整性如何保证",false))).getErrorCode());
        verifyNoInteractions(search);
        verify(answer, never()).answer(anyLong(), any());
    }

    @Test void selectsAtMostFourRealPointersAndNoClientSuppliedText() {
        when(answer.capabilities()).thenReturn(new AssistantCapabilities(true,"SELECTED_KEYWORD_EVIDENCE"));
        List<Evidence> evidence = new ArrayList<>();
        for (int i=0; i<6; i++) evidence.add(new Evidence(String.valueOf(101+i),
                "owned.txt",2,i,0,80,"source "+i,"READY"));
        when(search.search(7,"如何实现续传",true)).thenReturn(
                new SearchResult(evidence,false,"HYBRID_RRF_LIMITED"));
        when(answer.answer(eq(7L),any(AnswerRequest.class))).thenReturn(
                new AnswerResponse("支持续传。[S1]",List.of(),"MODEL_WITH_CITATIONS"));
        AutoAskResponse result = auto.ask(7,new AutoAskRequest("如何实现续传",true));
        assertEquals(4,result.selectedCount());
        assertEquals("HYBRID_RRF_LIMITED",result.retrievalMode());
        var captured = org.mockito.ArgumentCaptor.forClass(AnswerRequest.class);
        verify(answer).answer(eq(7L),captured.capture());
        assertEquals(4,captured.getValue().sources().size());
        assertEquals("101",captured.getValue().sources().getFirst().userFileId());
        assertEquals(2,captured.getValue().sources().getFirst().generation());
        assertTrue(captured.getValue().consent());
    }

    @Test void noEvidenceReturnsInsufficientWithoutCallingModel() {
        when(answer.capabilities()).thenReturn(new AssistantCapabilities(true,"SELECTED_KEYWORD_EVIDENCE"));
        when(search.search(7,"如何保证完整性",true)).thenReturn(
                new SearchResult(List.of(),false,"SEMANTIC_NOT_INDEXED"));
        AutoAskResponse result = auto.ask(7,new AutoAskRequest("如何保证完整性",true));
        assertEquals(0,result.selectedCount());
        assertEquals("INSUFFICIENT_EVIDENCE",result.response().mode());
        verify(answer,never()).answer(anyLong(),any());
    }
}
