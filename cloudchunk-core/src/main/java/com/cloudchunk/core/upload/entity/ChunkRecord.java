package com.cloudchunk.core.upload.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudchunk.common.enums.ChunkStatus;

import java.time.LocalDateTime;

@TableName("chunk_record")
public class ChunkRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String fileId;
    private Integer chunkIndex;
    private String chunkMd5;
    private Integer chunkSize;
    private String etag;
    private ChunkStatus status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getChunkMd5() { return chunkMd5; }
    public void setChunkMd5(String chunkMd5) { this.chunkMd5 = chunkMd5; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public ChunkStatus getStatus() { return status; }
    public void setStatus(ChunkStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
