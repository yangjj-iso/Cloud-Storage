package com.cloudchunk.common.model;

import com.cloudchunk.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public R() {}

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static <T> R<T> fail(ErrorCode code) {
        return new R<>(code.getCode(), code.getMessage(), null);
    }

    public static <T> R<T> fail(ErrorCode code, String detail) {
        String msg = detail == null || detail.isBlank()
                ? code.getMessage()
                : code.getMessage() + ": " + detail;
        return new R<>(code.getCode(), msg, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
