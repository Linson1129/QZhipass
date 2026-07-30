package org.microsoft.qintelipass.token;

import org.microsoft.qintelipass.models.User;
import org.microsoft.qintelipass.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Token 用量统计与限额管理服务（独立包，与原注销账户后端分离）
 *
 * 规则遵循：
 * - FK 使用实体关联（@ManyToOne/@JoinColumn），不使用基本数据类型
 * - 日期时间使用 Timestamp（Instant → epoch 毫秒）
 */
@Service("tokenUsageService")
public class TokenService {

    private static final String QUOTA_KEY = "global_token_quota";
    private static final long DEFAULT_QUOTA = 100_000L;

    @Autowired
    private TokenUsageRepository tokenUsageRepository;

    @Autowired
    private GlobalConfigRepository globalConfigRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserTokenQuotaRepository userTokenQuotaRepository;

    @Autowired
    private QuotaAdjustLogRepository quotaAdjustLogRepository;

    /**
     * 应用启动时，若尚未配置统一限额，则写入默认值
     */
    @PostConstruct
    public void initDefaultQuota() {
        if (globalConfigRepository.findById(QUOTA_KEY).isEmpty()) {
            globalConfigRepository.save(new GlobalConfig(QUOTA_KEY, String.valueOf(DEFAULT_QUOTA)));
        }
    }

    // ===================== 限额管理 =====================

    /** 获取全局默认限额（即"个人token统计"模块的默认限额字段） */
    public long getGlobalQuota() {
        return globalConfigRepository.findById(QUOTA_KEY)
                .map(c -> {
                    try { return Long.parseLong(c.getValue()); }
                    catch (NumberFormatException e) { return DEFAULT_QUOTA; }
                })
                .orElse(DEFAULT_QUOTA);
    }

    /**
     * 获取某个用户的 Token 限额。
     * 若管理员曾单独调整过该员工，则返回个性化值；否则返回全局默认值。
     */
    public long getUserQuota(Long userId) {
        if (userId == null) return getGlobalQuota();
        return userTokenQuotaRepository.findByUserId(userId)
                .map(UserTokenQuota::getDailyQuota)
                .orElseGet(this::getGlobalQuota);
    }

    /** 管理员统一设置全局默认限额（立即生效） */
    public void setGlobalQuota(long quota) {
        if (quota < 0) {
            throw new IllegalArgumentException("Token quota must not be negative");
        }
        globalConfigRepository.save(new GlobalConfig(QUOTA_KEY, String.valueOf(quota)));
    }

    /**
     * 管理员调整指定员工的 Token 上限，并记录操作日志。
     * 当新限额 > 当前已消耗量时，员工立即恢复对话能力。
     *
     * @param adminUserId  操作管理员ID
     * @param targetUserId 目标员工ID
     * @param newQuota     新上限值
     * @return 调整后的状态信息
     */
    @Transactional
    public Map<String, Object> adjustUserQuota(Long adminUserId, Long targetUserId, long newQuota) {
        if (newQuota < 0) {
            throw new IllegalArgumentException("Token quota must not be negative");
        }

        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + adminUserId));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Target user not found: " + targetUserId));

        // 1. 记录旧限额
        long oldQuota = getUserQuota(targetUserId);

        // 2. 计算当时已消耗量
        long currentConsumption = getTodayConsumption(targetUserId);

        // 3. 更新或创建个性化配额记录
        UserTokenQuota quota = userTokenQuotaRepository.findByUserId(targetUserId)
                .orElse(new UserTokenQuota(target, newQuota, admin));
        quota.setDailyQuota(newQuota);
        quota.setUpdatedBy(admin);
        quota.setUpdatedAt(Instant.now());
        userTokenQuotaRepository.save(quota);

        // 4. 记录操作日志
        QuotaAdjustLog log = new QuotaAdjustLog(admin, target, oldQuota, newQuota, currentConsumption);
        quotaAdjustLogRepository.save(log);

        // 5. 构建返回结果
        long remaining = Math.max(0, newQuota - currentConsumption);
        boolean canChat = newQuota > currentConsumption;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", targetUserId);
        result.put("userName", target.getName());
        result.put("oldQuota", oldQuota);
        result.put("newQuota", newQuota);
        result.put("currentConsumption", currentConsumption);
        result.put("remaining", remaining);
        result.put("canChat", canChat);
        result.put("isPersonalized", true);
        return result;
    }

    /** 重置员工为全局默认限额（删除个性化配额记录） */
    @Transactional
    public Map<String, Object> resetUserQuota(Long adminUserId, Long targetUserId) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Target user not found"));

        long oldQuota = getUserQuota(targetUserId);
        long newQuota = getGlobalQuota();
        long currentConsumption = getTodayConsumption(targetUserId);

        // 删除个性化配额（恢复全局默认）
        userTokenQuotaRepository.deleteByUserId(targetUserId);

        // 记录操作日志
        QuotaAdjustLog log = new QuotaAdjustLog(admin, target, oldQuota, newQuota, currentConsumption);
        quotaAdjustLogRepository.save(log);

        long remaining = Math.max(0, newQuota - currentConsumption);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", targetUserId);
        result.put("userName", target.getName());
        result.put("oldQuota", oldQuota);
        result.put("newQuota", newQuota);
        result.put("currentConsumption", currentConsumption);
        result.put("remaining", remaining);
        result.put("isPersonalized", false);
        return result;
    }

    /** 查询某员工是否有个性化配额 */
    public boolean hasPersonalizedQuota(Long userId) {
        return userTokenQuotaRepository.findByUserId(userId).isPresent();
    }

    /** 获取某员工个性化配额详情 */
    public Optional<UserTokenQuota> getPersonalizedQuota(Long userId) {
        return userTokenQuotaRepository.findByUserId(userId);
    }

    // ===================== 操作日志查询 =====================

    /**
     * 分页查询配额调整操作日志。
     * 支持按操作人、目标员工、时间范围筛选。
     *
     * @param operatorId   操作人ID（可选）
     * @param targetUserId 目标员工ID（可选）
     * @param startTime    起始时间（epoch 毫秒，可选）
     * @param endTime      结束时间（epoch 毫秒，可选）
     * @param page         页码（0-based）
     * @param size         每页条数
     */
    public Map<String, Object> queryQuotaLogs(Long operatorId, Long targetUserId,
                                               Long startTime, Long endTime,
                                               int page, int size) {
        Instant start = startTime != null ? Instant.ofEpochMilli(startTime) : null;
        Instant end = endTime != null ? Instant.ofEpochMilli(endTime) : null;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "operatedAt"));
        Page<QuotaAdjustLog> logPage = quotaAdjustLogRepository.findLogs(
                operatorId, targetUserId, start, end, pageable);

        List<Map<String, Object>> logs = logPage.getContent().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("operatorId", l.getOperator() != null ? l.getOperator().getId() : null);
            m.put("operatorName", l.getOperator() != null ? l.getOperator().getName() : null);
            m.put("targetUserId", l.getTargetUser() != null ? l.getTargetUser().getId() : null);
            m.put("targetUserName", l.getTargetUserName());
            m.put("oldQuota", l.getOldQuota());
            m.put("newQuota", l.getNewQuota());
            m.put("currentConsumption", l.getCurrentConsumption());
            m.put("operatedAt", l.getOperatedAt().toEpochMilli()); // epoch 毫秒，前端直接使用
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", logs);
        result.put("totalElements", logPage.getTotalElements());
        result.put("totalPages", logPage.getTotalPages());
        result.put("number", logPage.getNumber());
        result.put("size", logPage.getSize());
        return result;
    }

    // ===================== 用量记录 =====================

    /**
     * 记录一次对话产生的 token 消耗（按 user + 日期 + 模型 累加）
     */
    @Transactional
    public void recordUsage(Long userId, String model, long promptTokens, long completionTokens) {
        if (userId == null || model == null) return;

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return;

        LocalDate today = LocalDate.now();
        TokenUsage tu = tokenUsageRepository
                .findByUserIdAndUsageDateAndModel(userId, today, model)
                .orElse(null);

        if (tu == null) {
            tu = new TokenUsage();
            tu.setUser(user);
            tu.setUsageDate(today);
            tu.setModel(model);
        }
        tu.setPromptTokens(tu.getPromptTokens() + promptTokens);
        tu.setCompletionTokens(tu.getCompletionTokens() + completionTokens);
        tu.setTotalTokens(tu.getTotalTokens() + promptTokens + completionTokens);
        tokenUsageRepository.save(tu);
    }

    /** 查询某用户当日的总消耗量 */
    public long getTodayConsumption(Long userId) {
        if (userId == null) return 0;
        LocalDate today = LocalDate.now();
        List<TokenUsage> list = tokenUsageRepository.findByUserIdAndUsageDate(userId, today);
        return list.stream().mapToLong(TokenUsage::getTotalTokens).sum();
    }

    // ===================== 状态查询 =====================

    /** 获取某用户当日限额与使用状态 */
    public UserTokenStatus getDailyStatus(Long userId) {
        long quota = getUserQuota(userId);
        LocalDate today = LocalDate.now();
        List<TokenUsage> list = tokenUsageRepository.findByUserIdAndUsageDate(userId, today);
        long used = list.stream().mapToLong(TokenUsage::getTotalTokens).sum();
        long remaining = Math.max(0, quota - used);

        String department = null;
        String userName = null;
        if (userId != null) {
            var userOpt = userRepository.findById(userId);
            if (userOpt.isPresent()) {
                var u = userOpt.get();
                department = u.getDepartment();
                userName = u.getName();
            }
        }
        return new UserTokenStatus(userId, quota, used, remaining, used >= quota, department, userName);
    }

    /**
     * 发起对话前检测是否超额
     * @param estimatedTokens 本次对话预计消耗
     */
    public boolean checkQuota(Long userId, long estimatedTokens) {
        UserTokenStatus status = getDailyStatus(userId);
        return status.used() + estimatedTokens <= status.quota();
    }

    // ===================== 看板数据 =====================

    /** 管理员看板：活跃用户数、超额用户数、分模型一周每日消耗 */
    public DashboardData getDashboard() {
        long quota = getGlobalQuota();
        LocalDate today = LocalDate.now();

        List<TokenUsage> todayList = tokenUsageRepository.findByUsageDate(today);
        Map<Long, Long> sumByUser = new LinkedHashMap<>();
        for (TokenUsage t : todayList) {
            Long uid = t.getUserId();
            sumByUser.merge(uid, t.getTotalTokens(), Long::sum);
        }

        long activeUsers = sumByUser.size();
        long overQuotaUsers = sumByUser.values().stream().filter(v -> v >= quota).count();

        List<String> dates = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            dates.add(today.minusDays(i).toString());
        }
        Map<String, List<Long>> models = new HashMap<>();
        List<Long> totals = new ArrayList<>(Collections.nCopies(7, 0L));

        LocalDate start = today.minusDays(6);
        for (TokenUsageRepository.ModelDailyTotal m : tokenUsageRepository.findDailyTotalsSince(start)) {
            models.computeIfAbsent(m.getModel(), k -> new ArrayList<>(Collections.nCopies(7, 0L)));
            int idx = (int) ChronoUnit.DAYS.between(start, m.getUsageDate());
            if (idx >= 0 && idx < 7) {
                models.get(m.getModel()).set(idx, m.getTotal());
                totals.set(idx, totals.get(idx) + m.getTotal());
            }
        }

        return new DashboardData(activeUsers, overQuotaUsers, quota, dates, models, totals);
    }

    /** 管理员视角：按部门统计当日 token 使用情况 + 员工明细 */
    public DepartmentUsageData getDepartmentUsage() {
        long globalQuota = getGlobalQuota();
        LocalDate today = LocalDate.now();

        List<TokenUsage> todayList = tokenUsageRepository.findByUsageDate(today);
        Map<Long, Long> sumByUser = new LinkedHashMap<>();
        for (TokenUsage t : todayList) {
            Long uid = t.getUserId();
            sumByUser.merge(uid, t.getTotalTokens(), Long::sum);
        }

        List<User> users = userRepository.findAll();

        Map<String, long[]> deptAgg = new LinkedHashMap<>();
        List<DepartmentUsageData.UserUsageRow> userRows = new ArrayList<>();

        for (User u : users) {
            long used = sumByUser.getOrDefault(u.getId(), 0L);
            long quota = getUserQuota(u.getId()); // 优先个人配额
            boolean over = used >= quota;
            String dept = u.getDepartment() == null ? "未分配" : u.getDepartment();

            userRows.add(new DepartmentUsageData.UserUsageRow(
                    u.getId(), u.getName(), dept, used, quota, over));

            long[] agg = deptAgg.computeIfAbsent(dept, k -> new long[3]);
            agg[0] += 1;
            agg[1] += used;
            if (over) agg[2] += 1;
        }

        List<DepartmentUsageData.DepartmentRow> deptRows = deptAgg.entrySet().stream()
                .map(e -> new DepartmentUsageData.DepartmentRow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparing(DepartmentUsageData.DepartmentRow::department))
                .toList();

        userRows.sort(Comparator.comparing(DepartmentUsageData.UserUsageRow::department)
                .thenComparing(DepartmentUsageData.UserUsageRow::totalTokens, Comparator.reverseOrder()));

        return new DepartmentUsageData(today.toString(), deptRows, userRows);
    }

    // ===================== 每周趋势（员工视角） =====================

    public Map<String, Object> getUserWeeklyTrend(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);

        List<TokenUsage> usages = tokenUsageRepository.findByUserIdAndUsageDateBetween(userId, start, today);
        Map<LocalDate, Long> dateMap = new LinkedHashMap<>();
        for (TokenUsage t : usages) {
            dateMap.merge(t.getUsageDate(), t.getTotalTokens(), Long::sum);
        }

        String[] weekdays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        List<String> labels = new ArrayList<>();
        List<Long> data = new ArrayList<>();
        long monthTotal = 0;
        int dayCount = 0;

        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            labels.add(String.format("%02d-%02d %s", d.getMonthValue(), d.getDayOfMonth(), weekdays[d.getDayOfWeek().getValue() % 7]));
            long val = dateMap.getOrDefault(d, 0L);
            data.add(val);
            if (val > 0) { monthTotal += val; dayCount++; }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labels", labels);
        result.put("data", data);
        result.put("monthlyTotal", monthTotal);
        result.put("averageDaily", dayCount > 0 ? monthTotal / dayCount : 0);
        result.put("activeDays", dayCount);
        return result;
    }

    /** 最近对话记录（员工视角） */
    public List<Map<String, Object>> getRecentConversations(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate threeDaysAgo = today.minusDays(3);

        List<TokenUsage> usages = tokenUsageRepository.findByUserIdAndUsageDateBetween(userId, threeDaysAgo, today);
        return usages.stream()
                .sorted(Comparator.comparing(TokenUsage::getUsageDate, Comparator.reverseOrder()))
                .limit(20)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("modelName", t.getModel());
                    m.put("tokensUsed", t.getTotalTokens());
                    m.put("usageDate", t.getUsageDate().toString());
                    m.put("content", "对话记录");
                    m.put("createdAt", t.getUsageDate().toString());
                    return m;
                })
                .toList();
    }

    // ===================== 前端适配：管理员仪表盘 =====================

    public Map<String, Object> getDashboardForFrontend() {
        long quota = getGlobalQuota();
        LocalDate today = LocalDate.now();

        List<TokenUsage> todayList = tokenUsageRepository.findByUsageDate(today);
        Map<Long, Long> sumByUser = new LinkedHashMap<>();
        for (TokenUsage t : todayList) {
            Long uid = t.getUserId();
            sumByUser.merge(uid, t.getTotalTokens(), Long::sum);
        }

        long activeUsers = sumByUser.size();
        long overQuotaUsers = sumByUser.values().stream().filter(v -> v >= quota).count();
        long todayTotal = sumByUser.values().stream().mapToLong(Long::longValue).sum();

        List<String> dates = new ArrayList<>();
        String[] weekdays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            dates.add(String.format("%02d-%02d %s", d.getMonthValue(), d.getDayOfMonth(), weekdays[d.getDayOfWeek().getValue() % 7]));
        }

        LocalDate start = today.minusDays(6);
        List<TokenUsageRepository.ModelDailyTotal> rawTotals = tokenUsageRepository.findDailyTotalsSince(start);
        String[] modelNames = {"千问", "DeepSeek", "Llama-3.1"};
        Map<String, Map<LocalDate, Long>> modelDateMap = new LinkedHashMap<>();
        for (String m : modelNames) modelDateMap.put(m, new LinkedHashMap<>());

        for (TokenUsageRepository.ModelDailyTotal t : rawTotals) {
            modelDateMap.computeIfAbsent(t.getModel(), k -> new LinkedHashMap<>())
                    .put(t.getUsageDate(), t.getTotal());
        }

        List<Map<String, Object>> datasets = new ArrayList<>();
        for (String m : modelNames) {
            Map<String, Object> ds = new LinkedHashMap<>();
            ds.put("label", m);
            Map<LocalDate, Long> dm = modelDateMap.getOrDefault(m, Map.of());
            List<Long> vals = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                vals.add(dm.getOrDefault(today.minusDays(i), 0L));
            }
            ds.put("data", vals);
            datasets.add(ds);
        }

        Map<String, Object> chartData = new LinkedHashMap<>();
        chartData.put("labels", dates);
        chartData.put("datasets", datasets);

        List<Map<String, Object>> employees = buildEmployeeListForFrontend(today, sumByUser, quota);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeUsers", activeUsers);
        result.put("overQuotaUsers", overQuotaUsers);
        result.put("todayTotalConsumption", todayTotal);
        result.put("chartData", chartData);
        result.put("employees", employees);
        result.put("globalLimit", quota);
        return result;
    }

    private List<Map<String, Object>> buildEmployeeListForFrontend(
            LocalDate today, Map<Long, Long> sumByUser, long globalQuota) {
        List<User> allUsers = userRepository.findAll().stream()
                .filter(u -> u.getStatus() == null || !"CANCELLED".equals(u.getStatus().name()))
                .sorted(Comparator.comparing(User::getId).reversed())
                .toList();

        return allUsers.stream().map(u -> {
            Map<String, Object> emp = new LinkedHashMap<>();
            long used = sumByUser.getOrDefault(u.getId(), 0L);
            long limit = getUserQuota(u.getId());
            String status;
            if (used >= limit) status = "over";
            else if (limit > 0 && (double) used / limit > 0.85) status = "warning";
            else status = "normal";

            emp.put("id", String.valueOf(u.getId()));
            emp.put("name", u.getName());
            emp.put("department", u.getDepartment() != null ? u.getDepartment() : "未分配");
            emp.put("totalTokens", used);
            emp.put("quota", limit);
            emp.put("overQuota", used >= limit);
            emp.put("isPersonalized", hasPersonalizedQuota(u.getId()));
            return emp;
        }).toList();
    }

    // ===================== 用户配额 =====================

    @Deprecated
    public void setUserQuota(Long userId, long quota) {
        if (quota < 0) throw new IllegalArgumentException("Token quota must not be negative");
        globalConfigRepository.save(new GlobalConfig("user_quota_" + userId, String.valueOf(quota)));
    }

    public long countActiveUsers() {
        return userRepository.findAll().stream()
                .filter(u -> u.getStatus() == null || !"CANCELLED".equals(u.getStatus().name()))
                .count();
    }

    // ===================== 查询用户跨日记录 =====================

    public List<TokenUsage> findByUserIdAndUsageDateBetween(Long userId, LocalDate start, LocalDate end) {
        return tokenUsageRepository.findByUserIdAndUsageDateBetween(userId, start, end);
    }

    // ===================== 种子数据 =====================

    @PostConstruct
    public void seedDemoData() {
        try {
            if (tokenUsageRepository.count() > 0) return;
        } catch (Exception e) { return; }

        LocalDate today = LocalDate.now();
        List<User> users = userRepository.findAll();
        if (users.isEmpty()) return;
        Random random = new Random(42);

        String[] models = {"千问", "DeepSeek", "Llama-3.1"};
        Set<String> seen = new HashSet<>();

        for (User user : users) {
            if (user.getStatus() != null && "CANCELLED".equals(user.getStatus().name())) continue;
            for (int dayOffset = 6; dayOffset >= 0; dayOffset--) {
                LocalDate date = today.minusDays(dayOffset);
                int recordCount = random.nextInt(3) + 2;
                int created = 0;
                for (int attempt = 0; attempt < recordCount + 2 && created < recordCount; attempt++) {
                    String model = models[random.nextInt(models.length)];
                    String key = user.getId() + "-" + date + "-" + model;
                    if (seen.contains(key)) continue;
                    seen.add(key);

                    long tokens = random.nextInt(50000) + 5000;
                    TokenUsage tu = new TokenUsage();
                    tu.setUser(user);
                    tu.setUsageDate(date);
                    tu.setModel(model);
                    tu.setPromptTokens(tokens / 2);
                    tu.setCompletionTokens(tokens - tokens / 2);
                    tu.setTotalTokens(tokens);
                    try {
                        tokenUsageRepository.save(tu);
                        created++;
                    } catch (Exception e) { /* skip duplicate */ }
                }
            }
        }
    }

    // ===================== 定时清理 =====================

    /**
     * 每日 0 点执行：
     * - 清理 30 天前的旧用量记录
     * - 清理 90 天前的旧操作日志
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void dailyReset() {
        LocalDate cutoffUsage = LocalDate.now().minusDays(30);
        tokenUsageRepository.deleteByUsageDateBefore(cutoffUsage);

        Instant cutoffLog = Instant.now().minus(90, ChronoUnit.DAYS);
        quotaAdjustLogRepository.deleteByCreatedAtBefore(cutoffLog);
    }
}
