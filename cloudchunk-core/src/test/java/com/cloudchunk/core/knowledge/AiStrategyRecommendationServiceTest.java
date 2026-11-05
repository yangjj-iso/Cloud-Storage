package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiStrategyRecommendationServiceTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";

    @Test void explicitConsentAndUniquenessPrecedeAnyPrivateRead() {
        var valid = new AiStrategyRecommendationService.SuggestRequest(
                new SourceRef("101",2,0),List.of(A,B),true);
        assertThatCode(() -> AiStrategyRecommendationService.validate(valid)).doesNotThrowAnyException();
        for(var req : List.of(
                new AiStrategyRecommendationService.SuggestRequest(new SourceRef("101",2,0),List.of(A),false),
                new AiStrategyRecommendationService.SuggestRequest(new SourceRef("101",2,0),List.of(A,A),true),
                new AiStrategyRecommendationService.SuggestRequest(new SourceRef("001",2,0),List.of(A),true),
                new AiStrategyRecommendationService.SuggestRequest(new SourceRef("101",2,64),List.of(A),true),
                new AiStrategyRecommendationService.SuggestRequest(new SourceRef("101",2,0),List.of("../admin"),true),
                new AiStrategyRecommendationService.SuggestRequest(new SourceRef("101",2,0),List.of(),true),
                new AiStrategyRecommendationService.SuggestRequest(new SourceRef("101",2,0),
                        java.util.Collections.nCopies(9,A),true))) {
            assertThatThrownBy(() -> AiStrategyRecommendationService.validate(req))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test void noModelCallOnInvalidOrDisabledRequest() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var knowledge=mock(KnowledgeService.class);
        var move=mock(AiMoveAgentService.class);
        var service=new AiStrategyRecommendationService(jdbc,knowledge,move);
        var invalid=new AiStrategyRecommendationService.SuggestRequest(
                new SourceRef("101",2,0),List.of(A),false);
        assertThatThrownBy(() -> service.suggest(1,invalid)).isInstanceOf(BizException.class);
        verifyNoInteractions(knowledge,jdbc,move);
        when(move.capabilities()).thenReturn(new AiMoveAgentService.Capabilities(false,"DISABLED"));
        assertThatThrownBy(() -> service.suggest(1,
                new AiStrategyRecommendationService.SuggestRequest(
                    new SourceRef("101",2,0),List.of(A),true))).isInstanceOf(BizException.class);
        verifyNoInteractions(knowledge,jdbc);
        verify(move,never()).suggest(anyLong(),any());
    }

    @Test void feedbackOnlyAcceptsKnownClosedCategories() {
        // Strict feedback validation is exercised against MySQL in KnowledgeIsolationIT.
        assertThatThrownBy(() -> AiStrategyRecommendationService.validate(
            new AiStrategyRecommendationService.SuggestRequest(
                new SourceRef("9223372036854775808",1,0),List.of(A),true)))
                .isInstanceOf(BizException.class);
    }
}
