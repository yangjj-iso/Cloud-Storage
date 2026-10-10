package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundedAnswerServiceTest {
    private static final SourceRef REF = new SourceRef("101",3,0);
    private static final Evidence E = new Evidence("101","private.txt",3,0,0,12,"来源正文","READY");
    private static AnswerRequest request(boolean consent) {
        return new AnswerRequest("文件中的结论是什么？",List.of(REF),consent);
    }
    private static GroundedAnswerService assistant(KnowledgeService knowledge, ChatGateway gateway,boolean on) {
        Environment env = mock(Environment.class);
        when(env.getProperty("cloudchunk.ai.chat.enabled","false")).thenReturn(String.valueOf(on));
        return new GroundedAnswerService(knowledge,gateway,env,mock(AiDailyCallBudget.class));
    }
    @Test void disabledDoesNotReadOrCallTheModel() {
        var knowledge=mock(KnowledgeService.class);var gateway=mock(ChatGateway.class);
        var service=assistant(knowledge,gateway,false);
        assertFalse(service.capabilities().enabled());
        assertEquals(ErrorCode.AI_DISABLED,assertThrows(BizException.class,
                () -> service.answer(7,request(true))).getErrorCode());
        verifyNoInteractions(knowledge);
        verify(gateway,never()).completeWithUsage(anyString(),anyList());
    }
    @Test void lackOfConsentAndRevokedEvidenceBlockExternalCalls() {
        var knowledge=mock(KnowledgeService.class);var gateway=mock(ChatGateway.class);
        when(gateway.configured()).thenReturn(true);
        var service=assistant(knowledge,gateway,true);
        assertEquals(ErrorCode.INVALID_PARAMETER,assertThrows(BizException.class,
                () -> service.answer(7,request(false))).getErrorCode());
        assertEquals(ErrorCode.AI_SOURCE_CHANGED,assertThrows(BizException.class,
                () -> service.answer(7,request(true))).getErrorCode());
        verify(gateway,never()).completeWithUsage(anyString(),anyList());
    }
    @Test void requiresAllowedCitations() {
        var knowledge=mock(KnowledgeService.class);var gateway=mock(ChatGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(knowledge.source(7L,101L,3L,0)).thenReturn(E);
        var service=assistant(knowledge,gateway,true);
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new ChatGateway.Completion("没有引用",ModelTokenUsage.unknown()));
        assertEquals(ErrorCode.AI_UNGROUNDED,assertThrows(BizException.class,
                () -> service.answer(7,request(true))).getErrorCode());
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new ChatGateway.Completion("无效零编号[S0]和有效编号[S1]",ModelTokenUsage.unknown()));
        assertEquals(ErrorCode.AI_UNGROUNDED,assertThrows(BizException.class,
                () -> service.answer(7,request(true))).getErrorCode());
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new ChatGateway.Completion("错误编号[S8]",ModelTokenUsage.unknown()));
        assertEquals(ErrorCode.AI_UNGROUNDED,assertThrows(BizException.class,
                () -> service.answer(7,request(true))).getErrorCode());
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new ChatGateway.Completion("证据支持的内容[S1]",ModelTokenUsage.unknown()));
        var answer=service.answer(7,request(true));
        assertEquals("MODEL_WITH_CITATIONS",answer.mode());
        assertEquals("S1",answer.citations().getFirst().citationId());
    }
    @Test void exhaustedChatBudgetNeverCallsTheModel() {
        var knowledge=mock(KnowledgeService.class);
        var gateway=mock(ChatGateway.class);
        var budget=mock(AiDailyCallBudget.class);
        var env=mock(Environment.class);
        when(env.getProperty("cloudchunk.ai.chat.enabled","false")).thenReturn("true");
        when(gateway.configured()).thenReturn(true);
        when(knowledge.source(7L,101L,3L,0)).thenReturn(E);
        doThrow(BizException.of(ErrorCode.AI_BUDGET_EXHAUSTED))
            .when(budget).reserve(7L,AiDailyCallBudget.Kind.CHAT);
        var service=new GroundedAnswerService(knowledge,gateway,env,budget);
        assertEquals(ErrorCode.AI_BUDGET_EXHAUSTED,assertThrows(BizException.class,
            () -> service.answer(7,request(true))).getErrorCode());
        verify(gateway,never()).completeWithUsage(anyString(),anyList());
    }
    @Test void reauthorizationAfterInferenceDetectsRevocation() {
        var knowledge=mock(KnowledgeService.class);var gateway=mock(ChatGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(knowledge.source(7L,101L,3L,0)).thenReturn(E,(Evidence)null);
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new ChatGateway.Completion("基于资料[S1]",ModelTokenUsage.unknown()));
        assertEquals(ErrorCode.AI_SOURCE_CHANGED,assertThrows(BizException.class,
                () -> assistant(knowledge,gateway,true).answer(7,request(true))).getErrorCode());
    }
}
