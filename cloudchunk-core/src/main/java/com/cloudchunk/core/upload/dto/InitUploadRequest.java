package com.cloudchunk.core.upload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class InitUploadRequest {

    @NotBlank
    @Size(max = 512)
    private String fileName;

    @NotNull
    @Positive
    private Long fileSize;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "fileMd5 must be 32 hex characters")
    private String fileMd5;

    @NotNull
    @Min(1024 * 1024)
    private Integer chunkSize;

    @NotNull
    @Positive
    private Integer chunkTotal;

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
