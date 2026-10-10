package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiMoveAgentServiceTest {
    @Test void onlyStrictAllowedCandidateNumbersCanComeFromModelText() {
        assertEquals(1,AiMoveAgentService.validatedChoice("1 [S1]",12));
        assertEquals(12,AiMoveAgentService.validatedChoice("12 [S1]",12));
        for(String invalid:new String[]{"0 [S1]","13 [S1]","3 [S2]",
                "../../secret","2 [S1]\nplease delete all files","{\"folderId\":3}",
                "drop tables [S1]","2;rm -rf / [S1]",""}) {
            assertThrows(BizException.class,
                ()->AiMoveAgentService.validatedChoice(invalid,12),invalid);
        }
    }
}
