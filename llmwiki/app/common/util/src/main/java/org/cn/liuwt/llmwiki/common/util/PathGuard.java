package org.cn.liuwt.llmwiki.common.util;

import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;

public final class PathGuard {

    public static final String ERR_CODE_RAW_IMMUTABLE = "PATH_RAW_IMMUTABLE";
    public static final String ERR_CODE_PATH_TRAVERSAL = "PATH_TRAVERSAL";
    public static final String ERR_CODE_INVALID_PATH = "PATH_INVALID";

    private PathGuard() {
    }

    public static void assertWritable(String path) {
        if (path == null || path.isBlank()) {
            throw new BusinessException(ERR_CODE_INVALID_PATH, "路径不能为空");
        }
        String normalized = normalize(path);
        if (normalized.contains("..")) {
            throw new BusinessException(ERR_CODE_PATH_TRAVERSAL, "路径包含非法的父目录引用: " + path);
        }
        if (normalized.startsWith("raw/") || normalized.equals("raw")) {
            throw new BusinessException(ERR_CODE_RAW_IMMUTABLE,
                "raw/ 目录为不可变的原始来源区，禁止写入或修改: " + path);
        }
    }

    public static boolean isWritable(String path) {
        try {
            assertWritable(path);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
