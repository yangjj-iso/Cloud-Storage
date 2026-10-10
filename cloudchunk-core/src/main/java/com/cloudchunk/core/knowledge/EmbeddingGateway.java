package com.cloudchunk.core.knowledge;

/** A model sees only explicitly consented text; it cannot access files directly. */
public interface EmbeddingGateway {
    boolean configured();
    String modelKey();
    int dimensions();
    double[] embed(String text);
    record Embedding(double[] vector, ModelTokenUsage usage) {}
    default Embedding embedWithUsage(String text) {
        return new Embedding(embed(text),ModelTokenUsage.unknown());
    }
}
