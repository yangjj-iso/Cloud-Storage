package com.cloudchunk.core.preview.dto;

/**
 * 文件预览聚合信息：供前端根据 previewType 选择渲染方式（图片/视频/文本/PDF/其它）。
 */
public record PreviewResult(
        String fileId,
        String fileName,
        String mimeType,
        Long fileSize,
        String status,
        String transcodeStatus,
        String previewType,
        String thumbnailUrl,
        String downloadUrl,
        String extra
) {
}
