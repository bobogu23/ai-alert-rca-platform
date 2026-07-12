package com.tencent.rca.fault;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 统一重试执行器, 对可重试异常做指数退避 + jitter 重试 (详细设计文档 7.3).
 * 不可重试异常直接抛出, 避免无差别重试放大故障.
 */
@Slf4j
@Component
public class RetryExecutor {

    private static final long BASE_BACKOFF_MS = 200L;

    /**
     * 执行带重试的调用.
     *
     * @param action     业务动作
     * @param maxRetries 最大重试次数 (不含首次)
     * @param actionName 动作名称, 用于日志
     * @param <T>        返回类型
     * @return 动作结果
     */
    public <T> T execute(Supplier<T> action, int maxRetries, String actionName) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (RetryableException ex) {
                attempt++;
                if (attempt > maxRetries) {
                    log.warn("动作 {} 重试 {} 次后仍失败", actionName, maxRetries, ex);
                    throw ex;
                }
                long backoff = computeBackoff(attempt);
                log.warn("动作 {} 第 {} 次失败, {}ms 后重试: {}", actionName, attempt, backoff, ex.getMessage());
                sleep(backoff);
            }
        }
    }

    private long computeBackoff(int attempt) {
        long exponential = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MS);
        return exponential + jitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RetryableException("重试等待被中断", ex);
        }
    }
}
