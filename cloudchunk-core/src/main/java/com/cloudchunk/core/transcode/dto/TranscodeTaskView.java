package com.cloudchunk.core.transcode.dto;

import com.cloudchunk.core.transcode.entity.TranscodeRecord;

import java.time.LocalDateTime;

public record TranscodeTaskView(
        Long id,
        String taskType,
        Integer status,
        Integer retryCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt) {

    public static TranscodeTaskView from(TranscodeRecord record) {
        return new TranscodeTaskView(record.getId(), record.getTaskType(), record.getStatus(),
                record.getRetryCount(), record.getStartedAt(), record.getFinishedAt(), record.getCreatedAt());
    }
}
