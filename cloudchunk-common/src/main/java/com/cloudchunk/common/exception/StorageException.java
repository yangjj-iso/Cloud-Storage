package com.cloudchunk.common.exception;

public class StorageException extends BizException {

    public StorageException(String detail) {
        super(ErrorCode.STORAGE_UNAVAILABLE, detail);
    }

    public StorageException(String detail, Throwable cause) {
        super(ErrorCode.STORAGE_UNAVAILABLE, detail, cause);
    }

    public StorageException(ErrorCode code, String detail) {
        super(code, detail);
    }

    public StorageException(ErrorCode code, String detail, Throwable cause) {
        super(code, detail, cause);
    }
}
