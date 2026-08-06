package org.cn.liuwt.llmwiki.common.util;

public final class FileFormatValidator {

    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] OLE_MAGIC = {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0};

    private FileFormatValidator() {}

    public static boolean isValidFormat(String format, byte[] header) {
        if (header == null || header.length < 4) {
            return false;
        }
        return switch (format.toLowerCase()) {
            case "pdf" -> matches(header, PDF_MAGIC);
            case "docx", "xlsx", "pptx" -> matches(header, ZIP_MAGIC);
            case "doc", "xls", "ppt" -> matches(header, OLE_MAGIC);
            case "md", "txt", "csv", "json" -> true;
            default -> false;
        };
    }

    public static boolean isParsableFormat(String format) {
        if (format == null) return false;
        return switch (format.toLowerCase()) {
            case "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "md", "txt", "csv", "json" -> true;
            default -> false;
        };
    }

    private static boolean matches(byte[] header, byte[] magic) {
        if (header.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (header[i] != magic[i]) return false;
        }
        return true;
    }
}
