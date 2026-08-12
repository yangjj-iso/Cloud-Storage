package com.cloudchunk.core.audit.service;

import com.cloudchunk.core.audit.entity.OperationLog;
import com.cloudchunk.core.audit.mapper.OperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OperationAuditService {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditService.class);
    private final OperationLogMapper mapper;

    public OperationAuditService(OperationLogMapper mapper) { this.mapper = mapper; }

    /** Audit failures never change the user-facing business result, but are visible in application logs. */
    public void record(Long userId, String opType, String targetId, String ip, String userAgent,
                       boolean success, long costMs) {
        OperationLog row = new OperationLog();
        row.setUserId(userId);
        row.setOpType(limit(opType, 32));
        row.setTargetId(limit(targetId, 64));
        row.setIp(limit(ip, 64));
        row.setUa(limit(userAgent, 512));
        row.setResult(success ? 1 : 0);
        row.setCostMs((int) Math.min(Math.max(costMs, 0), Integer.MAX_VALUE));
        row.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(row);
        } catch (Exception e) {
            log.error("operation audit persistence failed: type={}, target={}, user={}",
                    row.getOpType(), row.getTargetId(), userId, e);
        }
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
