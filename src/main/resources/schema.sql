-- ============================================
-- QIntelipass 数据库 Schema
-- ============================================

-- 创建数据库（如不存在）
CREATE DATABASE IF NOT EXISTS qintelipass
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE qintelipass;

-- ============================================
-- 用户表 (users)
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT COMMENT '用户唯一标识',
    name            VARCHAR(50)     NOT NULL                 COMMENT '用户姓名',
    phone           VARCHAR(20)     NOT NULL UNIQUE          COMMENT '手机号码（登录账号）',
    password        VARCHAR(200)    NOT NULL                 COMMENT 'BCrypt加密后的密码',
    department      VARCHAR(100)    NOT NULL                 COMMENT '所在部门',
    email           VARCHAR(100)    NULL                     COMMENT '邮箱地址',
    wechat          VARCHAR(50)     NULL                     COMMENT '微信号',
    status          VARCHAR(20)     NOT NULL DEFAULT 'NORMAL' COMMENT '账户状态: NORMAL / FROZEN / CANCELLED',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    cancelled_at    DATETIME        NULL                     COMMENT '注销时间',
    restored        BOOLEAN         NOT NULL DEFAULT FALSE   COMMENT '是否为恢复的历史用户'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 索引
CREATE INDEX IF NOT EXISTS idx_users_phone   ON users(phone);
CREATE INDEX IF NOT EXISTS idx_users_status  ON users(status);
CREATE INDEX IF NOT EXISTS idx_users_wechat  ON users(wechat);

-- ============================================
-- Token 用量记录表 (token_usage)
-- ============================================
CREATE TABLE IF NOT EXISTS token_usage (
    id               BIGINT          PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    user_id          BIGINT          NOT NULL                 COMMENT '用户ID',
    usage_date       DATE            NOT NULL                 COMMENT '用量日期（按天清零）',
    model            VARCHAR(50)     NOT NULL                 COMMENT '大模型名称',
    prompt_tokens    BIGINT          NOT NULL DEFAULT 0       COMMENT '输入token数',
    completion_tokens BIGINT         NOT NULL DEFAULT 0       COMMENT '输出token数',
    total_tokens     BIGINT          NOT NULL DEFAULT 0       COMMENT '总token数',
    UNIQUE KEY uk_user_date_model (user_id, usage_date, model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工每日Token用量表';

CREATE INDEX IF NOT EXISTS idx_token_date      ON token_usage(usage_date);
CREATE INDEX IF NOT EXISTS idx_token_user_date ON token_usage(user_id, usage_date);

-- ============================================
-- 全局配置表 (global_config)
-- ============================================
CREATE TABLE IF NOT EXISTS global_config (
    config_key      VARCHAR(100)    PRIMARY KEY               COMMENT '配置键',
    config_value    TEXT            NOT NULL                 COMMENT '配置值'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全局键值配置表';

-- 默认统一 token 限额（管理员可在后台修改）
INSERT IGNORE INTO global_config (config_key, config_value)
VALUES ('global_token_quota', '100000');

-- ============================================
-- 用户个性化 Token 配额表 (user_token_quota)
-- 若员工在此表有记录，则使用个性化值；
-- 否则沿用 global_token_quota 全局默认值
-- ============================================
CREATE TABLE IF NOT EXISTS user_token_quota (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    user_id         BIGINT          NOT NULL UNIQUE            COMMENT '目标员工ID（FK → users）',
    daily_quota     BIGINT          NOT NULL                   COMMENT '每日Token限额',
    updated_by      BIGINT          NOT NULL                   COMMENT '操作人（管理员）ID（FK → users）',
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    INDEX idx_user_quota_user (user_id),
    INDEX idx_user_quota_updated_by (updated_by),
    CONSTRAINT fk_quota_user     FOREIGN KEY (user_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_quota_operator FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工个性化Token配额表';

-- ============================================
-- 配额调整操作日志表 (quota_adjust_log)
-- 管理员每次调整员工上限时记录一条
-- 日志保留至少90天（由每日定时任务清理）
-- ============================================
CREATE TABLE IF NOT EXISTS quota_adjust_log (
    id                  BIGINT      PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    operator_id         BIGINT      NOT NULL                   COMMENT '操作人（管理员）ID（FK → users）',
    target_user_id      BIGINT      NOT NULL                   COMMENT '目标员工ID（FK → users）',
    target_user_name    VARCHAR(50) NOT NULL                   COMMENT '目标员工姓名（冗余，便于查询展示）',
    old_quota           BIGINT      NOT NULL                   COMMENT '调整前上限值',
    new_quota           BIGINT      NOT NULL                   COMMENT '调整后上限值',
    current_consumption BIGINT      NOT NULL                   COMMENT '该员工当时的已消耗量',
    operated_at         TIMESTAMP   NOT NULL                   COMMENT '操作时间（精确到秒）',
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    INDEX idx_log_operator  (operator_id),
    INDEX idx_log_target    (target_user_id),
    INDEX idx_log_time      (operated_at),
    CONSTRAINT fk_log_operator FOREIGN KEY (operator_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_log_target   FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配额调整操作日志表';
