package com.cloudchunk.common.enums;

public enum StorageType {
    MINIO("minio"),
    LOCAL("local"),
    OSS("oss");

    private final String value;

    StorageType(String value) { this.value = value; }

    public String getValue() { return value; }

    public static StorageType of(String v) {
        for (StorageType s : values()) {
            if (s.value.equalsIgnoreCase(v)) return s;
        }
        throw new IllegalArgumentException("unknown StorageType: " + v);
    }
}
