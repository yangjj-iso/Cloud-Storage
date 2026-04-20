package com.cloudchunk.api.handler;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("biz error: {} {} -> {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        ErrorCode code = e.getErrorCode();
        R<Void> body = R.fail(code);
        body.setMessage(e.getMessage());
        body.setTraceId(TraceContext.get());
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return fail(ErrorCode.INVALID_PARAMETER, msg);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return fail(ErrorCode.INVALID_PARAMETER, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleCv(ConstraintViolationException e) {
        return fail(ErrorCode.INVALID_PARAMETER, e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissing(MissingServletRequestParameterException e) {
        return fail(ErrorCode.INVALID_PARAMETER, "missing: " + e.getParameterName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleOversize(MaxUploadSizeExceededException e) {
        return fail(ErrorCode.INVALID_PARAMETER, "request body too large");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> handleIae(IllegalArgumentException e) {
        return fail(ErrorCode.INVALID_PARAMETER, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleAny(Exception e, HttpServletRequest req) {
        log.error("unhandled error: {} {}", req.getMethod(), req.getRequestURI(), e);
        return fail(ErrorCode.INTERNAL_ERROR, e.getMessage());
    }

    private ResponseEntity<R<Void>> fail(ErrorCode code, String detail) {
        R<Void> body = R.fail(code, detail);
        body.setTraceId(TraceContext.get());
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }
}
