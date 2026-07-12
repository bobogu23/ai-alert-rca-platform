package com.tencent.rca.fault;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 超时控制器, 基于虚拟线程为任意调用施加超时上限 (详细设计文档 7.2).
 * 超时优先原则: 宁可超时降级也不无限等待.
 */
@Slf4j
@Component
public class TimeLimiter {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 在指定超时内执行任务, 超时抛出 TimeLimitExceededException.
     *
     * @param task       任务
     * @param timeoutMs  超时毫秒
     * @param taskName   任务名, 用于日志
     * @param <T>        返回类型
     * @return 任务结果
     */
    public <T> T callWithTimeout(Callable<T> task, long timeoutMs, String taskName) {
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("任务 {} 超过 {}ms 超时, 已取消", taskName, timeoutMs);
            throw new TimeLimitExceededException("任务 " + taskName + " 执行超时");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new TimeLimitExceededException("任务 " + taskName + " 执行异常", cause);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TimeLimitExceededException("任务 " + taskName + " 被中断", ex);
        }
    }
}
