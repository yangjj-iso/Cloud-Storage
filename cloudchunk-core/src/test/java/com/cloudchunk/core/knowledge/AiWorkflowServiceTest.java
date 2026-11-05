package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiWorkflowServiceTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";

    private static AiWorkflowService.Step step(int number,String state) {
        return new AiWorkflowService.Step(number,number==1?A:B,"101","name.txt",
                "Dest","2099-01-01T00:00:00",state);
    }

    @Test void batchMustBeExplicitBoundedUniqueAndCanonicalUuid() {
        assertDoesNotThrow(() -> AiWorkflowService.validateCreate(
            new AiWorkflowService.CreateRequest(List.of(A,B),true)));
        assertThrows(BizException.class,() -> AiWorkflowService.validateCreate(
            new AiWorkflowService.CreateRequest(List.of(A),false)));
        assertThrows(BizException.class,() -> AiWorkflowService.validateCreate(
            new AiWorkflowService.CreateRequest(List.of(A,A),true)));
        assertThrows(BizException.class,() -> AiWorkflowService.validateCreate(
            new AiWorkflowService.CreateRequest(List.of(A,"../../admin"),true)));
        assertThrows(BizException.class,() -> AiWorkflowService.validateCreate(
            new AiWorkflowService.CreateRequest(List.of(),true)));
        assertThrows(BizException.class,() -> AiWorkflowService.validateCreate(
            new AiWorkflowService.CreateRequest(null,true)));
        assertThrows(BizException.class,() -> AiWorkflowService.validateCreate(
            new AiWorkflowService.CreateRequest(List.of(A,B,A,B,A),true)));
    }

    @Test void nextStepBlockedUntilPreviousExecutedSkippedOrReverted() {
        assertThrows(BizException.class,() -> AiWorkflowService.eligible(
            List.of(step(1,"PENDING"),step(2,"PENDING")),2));
        assertThrows(BizException.class,() -> AiWorkflowService.eligible(
            List.of(step(1,"APPROVED"),step(2,"PENDING")),2));
        for (String state: List.of("APPLIED","REVERTED","SKIPPED")) {
            assertEquals(2,AiWorkflowService.eligible(
                List.of(step(1,state),step(2,"PENDING")),2).stepNo());
        }
        assertThrows(BizException.class,() ->
            AiWorkflowService.eligible(List.of(step(1,"PENDING")),0));
        assertThrows(BizException.class,() ->
            AiWorkflowService.eligible(List.of(step(1,"PENDING")),5));
    }
}
