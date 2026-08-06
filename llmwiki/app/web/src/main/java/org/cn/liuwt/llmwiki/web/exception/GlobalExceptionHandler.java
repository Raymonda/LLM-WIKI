package org.cn.liuwt.llmwiki.web.exception;

import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public Result<Void> handleAuthenticationException(AuthenticationException ex) {
        LOGGER.warn("Authentication failed: {}", ex.getMessage());
        return Result.failed(ex.getCode(), ex.getMessage(), ex.getArgs());
    }

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException ex) {
        LOGGER.warn("Business error: {}", ex.getMessage());
        return Result.failed(ex.getCode(), ex.getMessage(), ex.getArgs());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        LOGGER.warn("Missing required parameter: {}", ex.getMessage());
        return Result.failed(ErrorCode.INVALID_PARAM, ex.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex) {
        LOGGER.error("Unexpected error", ex);
        return Result.failed(ErrorCode.INTERNAL_ERROR);
    }
}