package com.cloudchunk.core.audit.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriveOperationHistoryTest {
    @Test void classifiesAllUserMutationsWithStableNames() {
        assertEquals("DRIVE_MKDIR",DriveOperationHistory.classify("/api/v1/drive/mkdir","POST"));
        assertEquals("DRIVE_RENAME",DriveOperationHistory.classify("/api/v1/drive/rename","POST"));
        assertEquals("DRIVE_MOVE",DriveOperationHistory.classify("/api/v1/drive/move","POST"));
        assertEquals("DRIVE_RECYCLE",DriveOperationHistory.classify("/api/v1/drive/101","DELETE"));
        assertEquals("DRIVE_RESTORE",DriveOperationHistory.classify("/api/v1/recycle/101/restore","POST"));
        assertEquals("DRIVE_PURGE",DriveOperationHistory.classify("/api/v1/recycle/101","DELETE"));
        assertEquals("SHARE_SAVE",DriveOperationHistory.classify("/api/v1/share/save","POST"));
        assertEquals("AI_RENAME_SUGGEST",DriveOperationHistory.classify("/api/v1/knowledge/assistant/rename/suggest","POST"));
        assertEquals("AI_MOVE_SUGGEST",DriveOperationHistory.classify("/api/v1/knowledge/assistant/move/batch/suggest","POST"));
        String id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        assertEquals("AI_MOVE_APPLY",DriveOperationHistory.classify("/api/v1/knowledge/assistant/move/"+id+"/apply","POST"));
        assertEquals("AI_MOVE_UNDO",DriveOperationHistory.classify("/api/v1/knowledge/assistant/move/"+id+"/undo","POST"));
        assertEquals("AI_RENAME_APPLY",DriveOperationHistory.classify("/api/v1/knowledge/assistant/rename/"+id+"/apply","POST"));
    }

    @Test void arbitraryPathsCannotImpersonateAnAuditedMutation() {
        assertEquals("DRIVE_GET",DriveOperationHistory.classify("/api/v1/drive/rename","GET"));
        assertEquals("KNOWLEDGE_POST",DriveOperationHistory.classify(
            "/api/v1/knowledge/assistant/move/not-a-uuid/apply","POST"));
        assertEquals("API_POST",DriveOperationHistory.classify(null,"POST"));
    }
}
