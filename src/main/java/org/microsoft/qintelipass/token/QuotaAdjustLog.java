package org.microsoft.qintelipass.token;

import jakarta.persistence.*;
import org.microsoft.qintelipass.models.User;

import java.time.Instant;

/**
 * 配额调整操作日志。
 * 管理员每次调整员工 Token 上限时记录一条。
 *
 * 规则遵循：
 * - FK 使用实体关联 + @JoinColumn + @ManyToOne，不使用基本数据类型
 * - 日期时间字段使用 Timestamp（Instant → epoch 毫秒，前端可直接序列化）
 */
@Entity
@Table(name = "quota_adjust_log", indexes = {
        @Index(name = "idx_log_operator", columnList = "operator_id"),
        @Index(name = "idx_log_target",   columnList = "target_user_id"),
        @Index(name = "idx_log_time",     columnList = "operated_at")
})
public class QuotaAdjustLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人（管理员）（FK 规则：实体关联） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id", nullable = false)
    private User operator;

    /** 目标员工（FK 规则：实体关联） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id", nullable = false)
    private User targetUser;

    /** 目标员工姓名（冗余字段，便于日志查询展示） */
    @Column(name = "target_user_name", nullable = false, length = 50)
    private String targetUserName;

    /** 调整前上限值 */
    @Column(name = "old_quota", nullable = false)
    private Long oldQuota;

    /** 调整后上限值 */
    @Column(name = "new_quota", nullable = false)
    private Long newQuota;

    /** 该员工当时的已消耗量 */
    @Column(name = "current_consumption", nullable = false)
    private Long currentConsumption;

    /** 操作时间（精确到秒，Timestamp 规则：epoch 毫秒） */
    @Column(name = "operated_at", nullable = false, columnDefinition = "TIMESTAMP")
    private Instant operatedAt;

    /** 记录创建时间（Timestamp 规则：epoch 毫秒） */
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Instant createdAt;

    // ==================== 构造 & 生命周期 ====================

    public QuotaAdjustLog() {}

    public QuotaAdjustLog(User operator, User targetUser, Long oldQuota,
                          Long newQuota, Long currentConsumption) {
        this.operator = operator;
        this.targetUser = targetUser;
        this.targetUserName = targetUser.getName();
        this.oldQuota = oldQuota;
        this.newQuota = newQuota;
        this.currentConsumption = currentConsumption;
        this.operatedAt = Instant.now();
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.operatedAt == null) {
            this.operatedAt = Instant.now();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    // ==================== getters & setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getOperator() { return operator; }
    public void setOperator(User operator) { this.operator = operator; }

    public User getTargetUser() { return targetUser; }
    public void setTargetUser(User targetUser) { this.targetUser = targetUser; }

    public String getTargetUserName() { return targetUserName; }
    public void setTargetUserName(String targetUserName) { this.targetUserName = targetUserName; }

    public Long getOldQuota() { return oldQuota; }
    public void setOldQuota(Long oldQuota) { this.oldQuota = oldQuota; }

    public Long getNewQuota() { return newQuota; }
    public void setNewQuota(Long newQuota) { this.newQuota = newQuota; }

    public Long getCurrentConsumption() { return currentConsumption; }
    public void setCurrentConsumption(Long currentConsumption) { this.currentConsumption = currentConsumption; }

    public Instant getOperatedAt() { return operatedAt; }
    public void setOperatedAt(Instant operatedAt) { this.operatedAt = operatedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
