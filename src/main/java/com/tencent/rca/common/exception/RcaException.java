package com.tencent.rca.common.exception;

/**
 * 归因系统业务异常基类.
 */
public class RcaException extends RuntimeException {

    public RcaException(String message) {
        super(message);
    }

    public RcaException(String message, Throwable cause) {
        super(message, cause);
    }
}
