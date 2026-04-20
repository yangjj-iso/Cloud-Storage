package com.cloudchunk.core.upload.dto;

import java.util.List;

public class UploadProgress {

    private String fileId;
    private int chunkTotal;
    private List<Integer> uploaded;
    private List<Integer> missing;
    private double percent;

    public UploadProgress() {}

    public UploadProgress(String fileId, int chunkTotal, List<Integer> uploaded, List<Integer> missing) {
        this.fileId = fileId;
        this.chunkTotal = chunkTotal;
        this.uploaded = uploaded;
        this.missing = missing;
        this.percent = chunkTotal == 0 ? 0 :
                Math.round(uploaded.size() * 10000.0 / chunkTotal) / 100.0;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public int getChunkTotal() { return chunkTotal; }
    public void setChunkTotal(int chunkTotal) { this.chunkTotal = chunkTotal; }
    public List<Integer> getUploaded() { return uploaded; }
    public void setUploaded(List<Integer> uploaded) { this.uploaded = uploaded; }
    public List<Integer> getMissing() { return missing; }
    public void setMissing(List<Integer> missing) { this.missing = missing; }
    public double getPercent() { return percent; }
    public void setPercent(double percent) { this.percent = percent; }
}
