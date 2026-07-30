package org.microsoft.qintelipass.token;

import jakarta.persistence.*;
import org.microsoft.qintelipass.models.User;

import java.time.LocalDate;

/**
 * 员工每日 Token 使用量记录
 * 以 (user_id, usage_date, model) 为唯一维度，
 * 因此每日 0 点自然"清零"——新的一天会写入新的日期记录，互不干扰。
 *
 * FK 规则：user 字段使用实体关联 + @ManyToOne + @JoinColumn，不使用基本数据类型
 */
@Entity
@Table(
    name = "token_usage",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "usage_date", "model"})
)
public class TokenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 用户（FK 规则：实体关联） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    /** 大模型名称，例如 gpt-4o、claude-3、deepseek 等 */
    @Column(name = "model", nullable = false, length = 50)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens = 0;

    public TokenUsage() {}

    // -- getters / setters --

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    /** 便捷方法：返回用户ID（兼容旧代码） */
    public Long getUserId() { return user != null ? user.getId() : null; }

    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }

    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
}
