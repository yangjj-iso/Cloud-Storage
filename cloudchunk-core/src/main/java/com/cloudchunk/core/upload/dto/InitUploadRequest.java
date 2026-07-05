package com.cloudchunk.core.upload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class InitUploadRequest {

    /** 原始文件名，仅作为最终对象 key 和展示信息的一部分，不代表服务器本地路径。 */
    @NotBlank
    @Size(max = 512)
    private String fileName;

    /** 整个文件的字节数，后端用它做配额检查和分片数量校验。 */
    @NotNull
    @Positive
    private Long fileSize;

    /** 前端本地计算的整文件 MD5，用于秒传、续传匹配和最终校验。 */
    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "fileMd5 must be 32 hex characters")
    private String fileMd5;

    /** 前端计划使用的分片大小，后端会校验是否落在配置允许范围内。 */
    @NotNull
    @Min(1024 * 1024)
    private Integer chunkSize;

    /** 前端按 fileSize/chunkSize 计算出的分片总数，后端会重新校验。 */
    @NotNull
    @Positive
    private Integer chunkTotal;

    /** 浏览器推断的 MIME 类型；为空时后端会按 octet-stream 处理。 */
    private String mimeType;

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getFileMd5() { return fileMd5; }
    public void setFileMd5(String fileMd5) { this.fileMd5 = fileMd5 == null ? null : fileMd5.toLowerCase(); }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getChunkTotal() { return chunkTotal; }
    public void setChunkTotal(Integer chunkTotal) { this.chunkTotal = chunkTotal; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
