package com.cloudchunk.common.exception;

public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getMessage() + ": " + detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public static BizException of(ErrorCode code) {
        return new BizException(code);
    }

    public static BizException of(ErrorCode code, String detail) {
        return new BizException(code, detail);
    }
}
