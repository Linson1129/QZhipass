package org.microsoft.qintelipass.token;

import jakarta.persistence.*;
import org.microsoft.qintelipass.models.User;

import java.time.Instant;

/**
 * 员工个性化 Token 配额覆盖表。
 * 若员工有此记录，则使用该个性化值；否则沿用 global_token_quota 全局默认值。
 *
 * 规则遵循：
 * - FK 使用实体关联 + @JoinColumn + @ManyToOne，不使用基本数据类型
 * - 日期时间字段使用 Timestamp（Instant → epoch 毫秒，前端可直接序列化）
 */
@Entity
@Table(name = "user_token_quota")
public class UserTokenQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目标员工（FK 规则：实体关联） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** 每日 Token 限额 */
    @Column(name = "daily_quota", nullable = false)
    private Long dailyQuota;

    /** 操作人（管理员）（FK 规则：实体关联） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", nullable = false)
    private User updatedBy;

    /** 最后更新时间（Timestamp 规则：epoch 毫秒） */
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Instant updatedAt;

    // ==================== 构造 & 生命周期 ====================

    public UserTokenQuota() {}

    public UserTokenQuota(User user, Long dailyQuota, User updatedBy) {
        this.user = user;
        this.dailyQuota = dailyQuota;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    protected void onSave() {
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }

    // ==================== getters & setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Long getDailyQuota() { return dailyQuota; }
    public void setDailyQuota(Long dailyQuota) { this.dailyQuota = dailyQuota; }

    public User getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(User updatedBy) { this.updatedBy = updatedBy; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
