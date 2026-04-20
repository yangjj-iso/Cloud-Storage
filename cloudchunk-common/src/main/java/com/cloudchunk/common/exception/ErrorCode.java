package com.cloudchunk.common.exception;

public enum ErrorCode {

    OK(0, 200, "ok"),

    INVALID_PARAMETER(100001, 400, "invalid parameter"),
    UNAUTHORIZED(100002, 401, "unauthorized"),
    FORBIDDEN(100003, 403, "forbidden"),
    NOT_FOUND(100004, 404, "not found"),
    TOO_MANY_REQUESTS(100005, 429, "too many requests"),
    INTERNAL_ERROR(100500, 500, "internal server error"),

    UPLOAD_SESSION_EXPIRED(200001, 400, "upload session expired"),
    CHUNK_INDEX_INVALID(200002, 400, "chunk index out of range"),
    CHUNK_MD5_MISMATCH(200003, 400, "chunk md5 mismatch"),
    UPLOAD_IN_PROGRESS(200004, 409, "same upload in progress"),
    FILE_MD5_MISMATCH(200005, 400, "whole file md5 mismatch"),
    COMPOSE_FAILED(200006, 500, "storage compose failed"),
    CHUNK_NOT_COMPLETE(200007, 400, "chunks not complete"),

    FILE_NOT_FOUND(300001, 404, "file not found"),
    FILE_BROKEN(300002, 410, "file broken"),
    RANGE_NOT_SATISFIABLE(300003, 416, "range not satisfiable"),

    TRANSCODE_NOT_FOUND(400001, 404, "transcode record not found"),
    TRANSCODE_IN_PROGRESS(400002, 409, "transcode in progress"),

    STORAGE_UNAVAILABLE(500001, 500, "storage unavailable"),
    STORAGE_INSUFFICIENT(500002, 507, "storage insufficient"),

    QUOTA_EXCEEDED(600001, 403, "user quota exceeded");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public String getMessage() { return message; }
}
