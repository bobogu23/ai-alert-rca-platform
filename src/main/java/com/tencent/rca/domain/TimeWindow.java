package com.tencent.rca.domain;

import java.time.OffsetDateTime;

/**
 * 分析时间窗口.
 *
 * @param start 起始时间
 * @param end   结束时间
 */
public record TimeWindow(OffsetDateTime start, OffsetDateTime end) {
}
