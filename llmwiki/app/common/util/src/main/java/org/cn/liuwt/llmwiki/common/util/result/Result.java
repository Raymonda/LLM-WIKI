package org.cn.liuwt.llmwiki.common.util.result;

import lombok.Data;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;

import java.util.Map;

@Data
public class Result<T> {
    private T data;
    private boolean success;
    private String msg;
    private String code;
    private Object[] args;
    private Map<String, Object> extra;

    private Result() {}

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setData(data);
        result.setSuccess(true);
        result.setCode("0");
        result.setMsg("success");
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> failed(String msg) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode("-1");
        result.setMsg(msg);
        return result;
    }

    public static <T> Result<T> failed(String code, String msg) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    public static <T> Result<T> failed(String code, String msg, Object[] args) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        result.setArgs(args);
        return result;
    }

    public static <T> Result<T> failed(ErrorCode errorCode, Object... args) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(errorCode.getCode());
        result.setMsg(errorCode.getDefaultMessage());
        result.setArgs(args);
        return result;
    }

    public static <T> Result<T> failedWithExtra(String code, String msg, Map<String, Object> extra) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        result.setExtra(extra);
        return result;
    }
}