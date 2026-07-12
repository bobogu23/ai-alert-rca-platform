package com.tencent.rca.fault;

import com.tencent.rca.common.exception.RcaException;

/**
 * 超时异常, 表示某个环节超过其超时预算 (详细设计文档 7.2).
 */
public class TimeLimitExceededException extends RcaException {

    public TimeLimitExceededException(String message) {
        super(message);
    }

    public TimeLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
