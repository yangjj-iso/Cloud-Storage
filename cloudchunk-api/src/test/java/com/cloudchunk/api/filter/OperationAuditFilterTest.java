package com.cloudchunk.api.filter;

import com.cloudchunk.core.audit.service.OperationAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationAuditFilterTest {
    @Test void normalizedOperationsNeverEmbedUserFileIdsOrNames() {
        assertEquals("DRIVE_MKDIR",OperationAuditFilter.operation("/api/v1/drive/mkdir","POST"));
        assertEquals("DRIVE_RENAME",OperationAuditFilter.operation("/api/v1/drive/rename","POST"));
        assertEquals("DRIVE_MOVE",OperationAuditFilter.operation("/api/v1/drive/move","POST"));
        assertEquals("DRIVE_RECYCLE",OperationAuditFilter.operation("/api/v1/drive/123456","DELETE"));
        assertEquals("UPLOAD_POST",OperationAuditFilter.operation("/api/v1/upload/init","POST"));
    }

    @Test void extendedAuditKeepsRequestPathsOutOfStoredTargets() throws Exception {
        OperationAuditService audit = mock(OperationAuditService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/knowledge/assistant/move/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/apply");
        when(response.getStatus()).thenReturn(200);
        new OperationAuditFilter(audit).doFilterInternal(request, response, mock(FilterChain.class));
        verify(audit).record(isNull(), eq("AI_MOVE_APPLY"), isNull(), isNull(), isNull(), eq(true), anyLong());
    }
}
