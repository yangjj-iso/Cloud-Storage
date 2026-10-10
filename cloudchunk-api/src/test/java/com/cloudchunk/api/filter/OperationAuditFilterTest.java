package com.cloudchunk.api.filter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationAuditFilterTest {
    @Test void normalizedOperationsNeverEmbedUserFileIdsOrNames() {
        assertEquals("DRIVE_MKDIR",OperationAuditFilter.operation("/api/v1/drive/mkdir","POST"));
        assertEquals("DRIVE_RENAME",OperationAuditFilter.operation("/api/v1/drive/rename","POST"));
        assertEquals("DRIVE_MOVE",OperationAuditFilter.operation("/api/v1/drive/move","POST"));
        assertEquals("DRIVE_RECYCLE",OperationAuditFilter.operation("/api/v1/drive/123456","DELETE"));
        assertEquals("UPLOAD_POST",OperationAuditFilter.operation("/api/v1/upload/init","POST"));
    }
}
