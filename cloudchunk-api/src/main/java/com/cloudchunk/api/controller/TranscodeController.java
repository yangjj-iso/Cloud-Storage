package com.cloudchunk.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.R;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.transcode.entity.TranscodeRecord;
import com.cloudchunk.core.transcode.mapper.TranscodeRecordMapper;
import com.cloudchunk.mq.message.TranscodeMessage;
import com.cloudchunk.mq.producer.TranscodeProducer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transcode")
public class TranscodeController {

    private final TranscodeRecordMapper recordMapper;
    private final FileMetaService fileMetaService;
    private final TranscodeProducer transcodeProducer;

    public TranscodeController(TranscodeRecordMapper recordMapper,
                               FileMetaService fileMetaService,
                               TranscodeProducer transcodeProducer) {
        this.recordMapper = recordMapper;
        this.fileMetaService = fileMetaService;
        this.transcodeProducer = transcodeProducer;
    }

    @GetMapping("/{fileId}")
    public R<Map<String, Object>> status(@PathVariable String fileId) {
        FileMeta meta = fileMetaService.getOrThrow(fileId);
        List<TranscodeRecord> records = recordMapper.selectList(new LambdaQueryWrapper<TranscodeRecord>()
                .eq(TranscodeRecord::getFileId, fileId)
                .orderByDesc(TranscodeRecord::getCreatedAt));
        return R.ok(Map.of(
                "fileId", fileId,
                "transcodeStatus", meta.getTranscodeStatus(),
                "extra", meta.getExtra() == null ? "" : meta.getExtra(),
                "records", records));
    }

    @PostMapping("/{fileId}/retry")
    public R<Void> retry(@PathVariable String fileId) {
        FileMeta meta = fileMetaService.getOrThrow(fileId);
        if (meta.getBucket() == null || meta.getObjectKey() == null) {
            throw BizException.of(ErrorCode.TRANSCODE_NOT_FOUND, fileId);
        }
        TranscodeMessage msg = new TranscodeMessage();
        msg.setFileId(meta.getFileId());
        msg.setMimeType(meta.getMimeType());
        msg.setBucket(meta.getBucket());
        msg.setObjectKey(meta.getObjectKey());
        msg.setFileSize(meta.getFileSize());
        msg.setExpectMd5(meta.getFileMd5());
        msg.setRetryCount(0);
        msg.setProducedAt(Instant.now());
        boolean published = transcodeProducer.publish(msg);
        if (!published) {
            throw BizException.of(ErrorCode.TRANSCODE_IN_PROGRESS, fileId);
        }
        return R.ok();
    }
}
