package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.util.Md5Utils;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.micrometer.observation.annotation.Observed;

import java.io.InputStream;

/**
 * 合并后的整文件 MD5 校验；校验通过则投递转码消息，失败则标记文件损坏。
 */
@Service
public class ChecksumService {

    private static final Logger log = LoggerFactory.getLogger(ChecksumService.class);

    private final FileMetaService fileMetaService;
    private final StorageStrategyFactory storageFactory;
    private final ChecksumTransitionService transitionService;

    public ChecksumService(FileMetaService fileMetaService,
                           StorageStrategyFactory storageFactory,
                           ChecksumTransitionService transitionService) {
        this.fileMetaService = fileMetaService;
        this.storageFactory = storageFactory;
        this.transitionService = transitionService;
    }

    @Observed(name = "cloudchunk.checksum.verify", contextualName = "checksum-verify")
    public void verify(ChecksumRequested msg) {
        FileMeta meta = fileMetaService.findById(msg.getFileId()).orElse(null);
        if (meta == null) {
            log.warn("checksum: file meta missing, skip: {}", msg.getFileId());
            return;
        }
        if (meta.getStatus() == FileStatus.AVAILABLE) {
            log.debug("checksum: already verified: {}", msg.getFileId());
            return;
        }
        if (meta.getStatus() != FileStatus.MERGED) {
            log.debug("checksum: skip non-merged status, fileId={}, status={}",
                    msg.getFileId(), meta.getStatus());
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
            transitionService.markBroken(msg, "md5 mismatch");
            return;
        }

        transitionService.markAvailableAndRequestTranscode(msg);
    }
}
