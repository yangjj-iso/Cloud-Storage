package com.cloudchunk.storage.model;

public record PutResult(String objectKey, String etag, long size) {}
