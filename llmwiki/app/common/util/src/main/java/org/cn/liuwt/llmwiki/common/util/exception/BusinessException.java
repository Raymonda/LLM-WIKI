package org.cn.liuwt.llmwiki.common.util.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final String code;
    private final Object[] args;

    public BusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode.getDefaultMessage());
        this.code = errorCode.getCode();
        this.args = args;
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
        this.args = new Object[0];
    }

    public BusinessException(String message) {
        super(message);
        this.code = "-1";
        this.args = new Object[0];
    }
}