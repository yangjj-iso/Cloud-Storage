package com.cloudchunk.core.preview.service;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.drive.entity.UserFile;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.preview.dto.PreviewResult;
import org.springframework.stereotype.Service;

/**
 * 文件预览：按网盘节点 id 聚合物理文件的元数据 + 转码产物，给前端选择预览方式。
 */
@Service
public class PreviewService {

    private final UserFileService userFileService;
    private final FileMetaService fileMetaService;

    public PreviewService(UserFileService userFileService, FileMetaService fileMetaService) {
        this.userFileService = userFileService;
        this.fileMetaService = fileMetaService;
    }

    public PreviewResult preview(long userId, long userFileId) {
        UserFile uf = userFileService.findByIdActive(userFileId, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        if (uf.dir()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "cannot preview a directory");
        }
        if (uf.getFileId() == null || uf.getFileId().isEmpty()) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        }

        FileMeta fm = fileMetaService.getAvailableForUserOrThrow(uf.getFileId(), userId);
        String mime = fm.getMimeType() == null ? "" : fm.getMimeType();

        String thumbnailUrl = (fm.getThumbnailUrl() != null && !fm.getThumbnailUrl().isBlank())
                ? "/api/v1/file/" + fm.getFileId() + "/thumbnail" : null;

        return new PreviewResult(
                fm.getFileId(),
                uf.getFileName(),
                fm.getMimeType(),
                fm.getFileSize(),
                fm.getStatus() == null ? null : fm.getStatus().name(),
                fm.getTranscodeStatus() == null ? null : fm.getTranscodeStatus().name(),
                previewType(mime),
                thumbnailUrl,
                "/api/v1/file/" + fm.getFileId() + "/download",
                fm.getExtra()
        );
    }

    private static String previewType(String mime) {
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        if (mime.startsWith("text/")) return "text";
        if (mime.equals("application/pdf")) return "pdf";
        return "other";
    }
}
