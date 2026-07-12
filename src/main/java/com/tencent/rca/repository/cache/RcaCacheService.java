package com.tencent.rca.repository.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Redis 缓存服务 (详细设计文档 6.1、7.7).
 * 承担两类职责: (1) 告警指纹判重(短窗内同指纹只触发一次分析);
 * (2) Agent 中间结果缓存(重入时复用, 避免重复外部调用).
 * 缓存序列化统一使用 JSON, key 命名统一加 rca 前缀. 缓存读写失败不应阻断主流程, 因此异常仅记录并降级.
 */
@Slf4j
@Service
public class RcaCacheService {

    private static final String DEDUP_KEY_PREFIX = "rca:dedup:";
    private static final String AGENT_KEY_PREFIX = "rca:agent:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RcaCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 尝试占用指纹判重位. 仅当该指纹在冷却窗内不存在时占用成功.
     *
     * @param fingerprint 告警指纹
     * @param caseId      案卷 ID
     * @param ttlSeconds  冷却窗秒数
     * @return 占用成功(即为新告警)返回 true, 已存在返回 false
     */
    public boolean acquireFingerprint(String fingerprint, Long caseId, long ttlSeconds) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(DEDUP_KEY_PREFIX + fingerprint, String.valueOf(caseId), Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 查询指纹当前归属的活跃案卷 ID.
     *
     * @param fingerprint 告警指纹
     * @return 命中时返回案卷 ID
     */
    public Optional<Long> getFingerprintCaseId(String fingerprint) {
        String value = redisTemplate.opsForValue().get(DEDUP_KEY_PREFIX + fingerprint);
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            log.warn("指纹缓存值非法, 忽略: {}", value);
            return Optional.empty();
        }
    }

    /**
     * 缓存 Agent 中间结果, 供重入复用 (详细设计文档 7.7).
     *
     * @param caseId     案卷 ID
     * @param agentName  Agent 名称
     * @param value      结果对象
     * @param ttlSeconds 缓存有效秒数
     * @param <T>        结果类型
     */
    public <T> void putAgentResult(Long caseId, String agentName, T value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue()
                    .set(agentKey(caseId, agentName), json, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Agent 结果缓存写入失败, 跳过缓存: case={}, agent={}, err={}",
                    caseId, agentName, ex.getMessage());
        }
    }

    /**
     * 读取缓存的 Agent 中间结果.
     *
     * @param caseId    案卷 ID
     * @param agentName Agent 名称
     * @param type      结果类型
     * @param <T>       结果类型
     * @return 命中且反序列化成功时返回结果
     */
    public <T> Optional<T> getAgentResult(Long caseId, String agentName, Class<T> type) {
        String json = redisTemplate.opsForValue().get(agentKey(caseId, agentName));
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Agent 结果缓存反序列化失败, 视为未命中: case={}, agent={}, err={}",
                    caseId, agentName, ex.getMessage());
            return Optional.empty();
        }
    }

    private String agentKey(Long caseId, String agentName) {
        return AGENT_KEY_PREFIX + caseId + ":" + agentName;
    }
}
