-- AI 告警自动归因分析系统建表脚本 (对应详细设计文档第 9 章)

-- 告警案卷台账
CREATE TABLE IF NOT EXISTS alert_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  fingerprint VARCHAR(128) NOT NULL COMMENT '告警指纹',
  service_name VARCHAR(128) NOT NULL COMMENT '服务名',
  alert_source VARCHAR(64) NOT NULL COMMENT '告警来源(CLS/GALILEO/PROMETHEUS)',
  alert_type VARCHAR(32) COMMENT '告警类型(LOG_THRESHOLD/LATENCY/RESOURCE/OTHER)',
  status VARCHAR(32) NOT NULL COMMENT 'OPEN/ANALYZING/ANALYZED/ANALYZE_FAILED/ACKNOWLEDGED/RESOLVED/FALSE_POSITIVE/CASCADED',
  first_seen_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL,
  merged_alert_count INT DEFAULT 1 COMMENT '归并的原始告警数量',
  root_cause_case_id BIGINT COMMENT '级联合并时指向根因案卷id, 自身为根因案卷时为NULL',
  root_cause_summary TEXT COMMENT '根因结论摘要',
  confidence_level VARCHAR(16) COMMENT 'HIGH/MEDIUM/LOW',
  degrade_note VARCHAR(512) COMMENT '降级/兜底说明, 记录哪些数据源或环节失败',
  report_url VARCHAR(512) COMMENT '报告存储地址',
  version INT DEFAULT 0 COMMENT '乐观锁版本号',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_fingerprint (fingerprint),
  KEY idx_service_time (service_name, first_seen_at)
) COMMENT '告警案卷台账';

-- 告警抑制规则配置
CREATE TABLE IF NOT EXISTS suppression_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_type VARCHAR(32) NOT NULL COMMENT 'TIME_WINDOW/MAINTENANCE/CASCADE/DEPENDENCY/MANUAL_SILENCE',
  match_condition JSON NOT NULL COMMENT '匹配条件(服务/接口/标签等)',
  effective_start DATETIME,
  effective_end DATETIME,
  enabled TINYINT DEFAULT 1,
  created_by VARCHAR(64),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '告警抑制规则配置';

-- 报告推送记录
CREATE TABLE IF NOT EXISTS notification_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id BIGINT NOT NULL COMMENT '关联 alert_case.id',
  channel_type VARCHAR(32) NOT NULL COMMENT '推送渠道: WECOM/EMAIL/SMS/TICKET',
  send_status VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED/RETRYING',
  fail_reason VARCHAR(512) COMMENT '失败原因',
  retry_count INT DEFAULT 0,
  sent_at DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_case (case_id)
) COMMENT '报告推送记录';

-- 人工反馈台账 (在线自我完善方案 07 文档 2.3)
CREATE TABLE IF NOT EXISTS alert_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id BIGINT NOT NULL COMMENT '关联 alert_case.id',
  fingerprint VARCHAR(128) COMMENT '告警指纹, 用于同类告警经验复用',
  service_name VARCHAR(128) COMMENT '服务名, 冗余自案卷, 用于按服务聚合',
  feedback_type VARCHAR(32) NOT NULL COMMENT 'CONFIRMED/PARTIAL/REJECTED/FALSE_POSITIVE',
  ai_root_cause_summary TEXT COMMENT 'AI 当时给出的根因结论快照',
  ai_confidence VARCHAR(16) COMMENT 'AI 当时给出的置信度 HIGH/MEDIUM/LOW',
  actual_root_cause_direction VARCHAR(32) COMMENT '真实根因方向 REDIS/DB/DOWNSTREAM/GC/NETWORK/RESOURCE/UNKNOWN',
  actual_root_cause_summary TEXT COMMENT '真实根因描述',
  correct_evidence_hint VARCHAR(512) COMMENT '正确的证据线索',
  error_category VARCHAR(32) COMMENT '错误类型 WRONG_DIRECTION/INCOMPLETE/WRONG_EVIDENCE/MISSED_ROUTING/OVER_CONFIDENT/HALLUCINATION',
  suggestion VARCHAR(1024) COMMENT '反馈人自由建议',
  feedback_by VARCHAR(64) COMMENT '反馈人标识',
  feedbacker_level INT DEFAULT 1 COMMENT '反馈人级别 1~3, 资深 SRE 权重更高',
  adopted_to_experience TINYINT DEFAULT 0 COMMENT '是否已提炼为经验资产',
  feedback_at DATETIME NOT NULL COMMENT '反馈时间',
  KEY idx_case (case_id),
  KEY idx_type_time (feedback_type, feedback_at),
  KEY idx_fingerprint (fingerprint)
) COMMENT '人工反馈台账';
