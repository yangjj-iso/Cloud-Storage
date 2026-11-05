package com.cloudchunk.core.audit.service;

import com.cloudchunk.core.audit.entity.OperationLog;
import com.cloudchunk.core.audit.mapper.OperationLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
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

    /** Read only the requesting user's bounded, metadata-only operation history. */
    public record AuditItem(String operation,boolean success,int costMs,LocalDateTime at) {}
    public List<AuditItem> driveHistory(long userId) {
        return mapper.selectList(new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getUserId,userId)
                .in(OperationLog::getOpType,"DRIVE_MKDIR","DRIVE_RENAME","DRIVE_MOVE",
                    "DRIVE_RECYCLE","DRIVE_RESTORE","DRIVE_PURGE")
                .orderByDesc(OperationLog::getCreatedAt)
                .last("LIMIT 30")).stream()
            .map(row->new AuditItem(row.getOpType(),row.getResult()!=null&&row.getResult()==1,
                row.getCostMs()==null?0:row.getCostMs(),row.getCreatedAt()))
            .toList();
    }
    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
