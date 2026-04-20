package com.cloudchunk.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Md5Utils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int BUF_SIZE = 1024 * 1024;

    /** 对字节数组计算 MD5 */
    public static String md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return hex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对输入流流式计算 MD5（关闭调用方负责） */
    public static String md5(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buf = new byte[BUF_SIZE];
                while (dis.read(buf) != -1) {
                    // just read
                }
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(chars);
    }

    private Md5Utils() {}
}
