package org.cn.liuwt.llmwiki.common.util.exception;

public class AuthenticationException extends BusinessException {
    public AuthenticationException(String message) {
        super("AUTH_FAILED", message);
    }

    public AuthenticationException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}