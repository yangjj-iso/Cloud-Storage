package com.cloudchunk.core.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("op_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String opType;
    private String targetId;
    private String ip;
    private String ua;
    private String params;
    private Integer result;
    private Integer costMs;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOpType() { return opType; }
    public void setOpType(String opType) { this.opType = opType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUa() { return ua; }
    public void setUa(String ua) { this.ua = ua; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public Integer getResult() { return result; }
    public void setResult(Integer result) { this.result = result; }
    public Integer getCostMs() { return costMs; }
    public void setCostMs(Integer costMs) { this.costMs = costMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
