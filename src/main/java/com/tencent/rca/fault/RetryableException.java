package com.tencent.rca.fault;

import com.tencent.rca.common.exception.RcaException;

/**
 * 标记为"可重试"的异常 (网络抖动、5xx、限流等). 详细设计文档 7.3.
 */
public class RetryableException extends RcaException {

    public RetryableException(String message) {
        super(message);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
