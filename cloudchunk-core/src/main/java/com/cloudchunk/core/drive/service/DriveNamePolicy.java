package com.cloudchunk.core.drive.service;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;

/** Same rules for explicit names and uploaded/saved entries; no implicit file-system paths. */
public final class DriveNamePolicy {
    private DriveNamePolicy(){}
    public static String check(String value) {
        if(value==null || value.isBlank() || value.length()>512 ||
            !value.equals(value.strip()) || value.equals(".") || value.equals("..") ||
            value.chars().anyMatch(c -> c<32 || c==127 || "\\/:*?\"<>|".indexOf(c)>=0))
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"文件名必须为1至512字符，不能含路径或控制字符");
        return value;
    }

    /** Preserve the extension while allocating a distinct display name for an automatic import. */
    public static String suffix(String original,int attempt) {
        check(original);
        if(attempt<1 || attempt>100)throw new IllegalArgumentException("invalid suffix attempt");
        int dot=original.lastIndexOf('.');
        String stem=dot>0?original.substring(0,dot):original;
        String ext=dot>0?original.substring(dot):"";
        String postfix=" ("+attempt+")";
        int available=512-postfix.length()-ext.length();
        if(available<1)throw BizException.of(ErrorCode.INVALID_PARAMETER,"文件名后缀空间不足");
        return check(stem.substring(0,Math.min(stem.length(),available))+postfix+ext);
    }
}
