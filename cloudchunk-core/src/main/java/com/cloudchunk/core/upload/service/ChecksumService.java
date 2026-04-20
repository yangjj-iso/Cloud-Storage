package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.util.Md5Utils;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.mq.message.BrokenMessage;
import com.cloudchunk.mq.message.ChecksumMessage;
import com.cloudchunk.mq.message.TranscodeMessage;
import com.cloudchunk.mq.producer.BrokenProducer;
import com.cloudchunk.mq.producer.TranscodeProducer;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;

/**
 * 合并后的整文件 MD5 校验；校验通过则投递转码消息，失败则标记文件损坏。
 */
@Service
public class ChecksumService {

    private static final Logger log = LoggerFactory.getLogger(ChecksumService.class);

    private final FileMetaService fileMetaService;
    private final StorageStrategyFactory storageFactory;
    private final TranscodeProducer transcodeProducer;
    private final BrokenProducer brokenProducer;

    public ChecksumService(FileMetaService fileMetaService,
                           StorageStrategyFactory storageFactory,
                           TranscodeProducer transcodeProducer,
                           BrokenProducer brokenProducer) {
        this.fileMetaService = fileMetaService;
        this.storageFactory = storageFactory;
        this.transcodeProducer = transcodeProducer;
        this.brokenProducer = brokenProducer;
    }

    public void verify(ChecksumMessage msg) {
        FileMeta meta = fileMetaService.findById(msg.getFileId()).orElse(null);
        if (meta == null) {
            log.warn("checksum: file meta missing, skip: {}", msg.getFileId());
            return;
        }
        if (meta.getStatus() == FileStatus.AVAILABLE) {
            log.debug("checksum: already verified: {}", msg.getFileId());
            return;
        }

        String actual;
        try (InputStream in = storageFactory.current().get(new GetRequest(msg.getBucket(), msg.getObjectKey()))) {
            actual = Md5Utils.md5(in);
        } catch (Exception e) {
            log.error("checksum stream failed: {}", msg.getFileId(), e);
            throw new RuntimeException(e);
        }

        if (!actual.equalsIgnoreCase(msg.getExpectMd5())) {
            log.warn("checksum mismatch: fileId={}, expect={}, actual={}",
                    msg.getFileId(), msg.getExpectMd5(), actual);
            fileMetaService.updateStatus(msg.getFileId(), FileStatus.BROKEN);
            brokenProducer.publish(BrokenMessage.of(msg.getFileId(), "md5 mismatch"));
            // 清理错误对象
            try {
                storageFactory.current().delete(msg.getBucket(), msg.getObjectKey());
            } catch (Exception ignored) {}
            return;
        }

        fileMetaService.updateStatus(msg.getFileId(), FileStatus.AVAILABLE);

        // 投递转码
        TranscodeMessage t = new TranscodeMessage();
        t.setFileId(msg.getFileId());
        t.setMimeType(msg.getMimeType());
        t.setBucket(msg.getBucket());
        t.setObjectKey(msg.getObjectKey());
        t.setFileSize(msg.getFileSize());
        t.setExpectMd5(msg.getExpectMd5());
        t.setProducedAt(Instant.now());
        try {
            transcodeProducer.publish(t);
        } catch (Exception e) {
            log.warn("transcode produce failed (non-fatal): {}", msg.getFileId(), e);
        }
    }
}
