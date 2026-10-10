package com.cloudchunk.core.drive.service;

import com.cloudchunk.common.exception.BizException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriveNamePolicyTest {
    @Test void limitsNamesAndPreservesExtensionsWhenGeneratingUniqueImports() {
        assertEquals("report (1).txt",DriveNamePolicy.suffix("report.txt",1));
        assertEquals("report (10).txt",DriveNamePolicy.suffix("report.txt",10));
        assertEquals("folder (1)",DriveNamePolicy.suffix("folder",1));
        assertEquals("单据.md",DriveNamePolicy.check("单据.md"));
        assertEquals(512,DriveNamePolicy.suffix("a".repeat(508)+".txt",100).length());
        for(String invalid:new String[]{""," "," bad.txt","bad.txt ",".", "..",
             "../bad","C:\\secret","bad/name","bad\nname","bad?file"}) {
            assertThrows(BizException.class,()->DriveNamePolicy.check(invalid),invalid);
        }
        assertThrows(BizException.class,()->DriveNamePolicy.check("x".repeat(513)));
    }
}
