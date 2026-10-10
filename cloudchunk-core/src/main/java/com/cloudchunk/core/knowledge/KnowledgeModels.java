package com.cloudchunk.core.knowledge;

import java.util.List;

public final class KnowledgeModels {
    private KnowledgeModels() {}
    public record Document(String userFileId, String fileName, String mimeType, String status,
                           long generation, int contentChars, int chunkCount, String reason) {}
    public record Library(List<Document> documents, boolean hasMore, int page) {}
    public record Evidence(String userFileId, String fileName, long generation, int chunkIndex,
                           int startOffset, int endOffset, String content, String status) {}
    public record SearchResult(List<Evidence> evidence, boolean hasMore, String mode) {}
    public record BlockPage(List<Evidence> blocks, boolean hasMore, int page) {}
}
