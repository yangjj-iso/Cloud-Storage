package com.cloudchunk.core.transcode.dto;

import com.cloudchunk.common.enums.TranscodeStatus;

import java.util.List;

public record TranscodeStatusView(
        String fileId,
        TranscodeStatus transcodeStatus,
        List<TranscodeTaskView> records) {
}
