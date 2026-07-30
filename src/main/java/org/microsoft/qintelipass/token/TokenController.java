package org.microsoft.qintelipass.token;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
public class TokenController {

    @Autowired
    private TokenService tokenService;

    // ============ 用户视角 ============

    /**
     * 获取当前用户当日 token 限额与使用情况（前端左上角用户信息板块调用）
     */
    @GetMapping("/api/user/token")
    public ResponseEntity<?> getUserToken(
            @RequestHeader(value = "X-User-Id", required = false) String userIdStr) {
        Long userId = parseUserId(userIdStr);
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Missing or invalid X-User-Id header"));
        }
        UserTokenStatus status = tokenService.getDailyStatus(userId);
        return ResponseEntity.ok(Map.of("success", true, "data", status));
    }

    // ============ 前端适配：用户 Token 用量（employee-token-stats 页面） ============

    @GetMapping("/api/v1/user/token/usage")
    public ResponseEntity<?> getUserTokenUsage(
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Missing X-User-Id header"));
        }
        UserTokenStatus status = tokenService.getDailyStatus(userId);

        String st = status.overQuota() ? "over" :
                    (status.quota() > 0 && (double) status.used() / status.quota() > 0.85) ? "warning" : "normal";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("daily_limit", status.quota());
        data.put("used_today", status.used());
        data.put("remaining", status.remaining());
        data.put("status", st);
        data.put("department", status.department());
        data.put("name", status.userName());
        return ResponseEntity.ok(Map.of("success", true, "rawData", data));
    }

    @GetMapping("/api/v1/user/token/weekly")
    public ResponseEntity<?> getUserWeeklyTrend(
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Missing X-User-Id header"));
        }
        Map<String, Object> data = tokenService.getUserWeeklyTrend(userId);
        return ResponseEntity.ok(Map.of("success", true, "rawData", data));
    }

    @GetMapping("/api/v1/user/token/conversations")
    public ResponseEntity<?> getRecentConversations(
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Missing X-User-Id header"));
        }
        List<Map<String, Object>> list = tokenService.getRecentConversations(userId);
        return ResponseEntity.ok(Map.of("success", true, "rawData", list));
    }

    // ============ 管理员视角 ============

    @GetMapping("/api/admin/token/dashboard")
    public ResponseEntity<?> getDashboard() {
        DashboardData data = tokenService.getDashboard();
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @GetMapping("/api/admin/token/usage")
    public ResponseEntity<?> getDepartmentUsage() {
        DepartmentUsageData data = tokenService.getDepartmentUsage();
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @PostMapping("/api/admin/token/quota")
    public ResponseEntity<?> setQuota(@RequestBody Map<String, Object> body) {
        Object quotaObj = body.get("quota");
        if (quotaObj == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "quota is required"));
        }
        long quota;
        try {
            quota = Long.parseLong(String.valueOf(quotaObj));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "quota must be a number"));
        }
        try {
            tokenService.setGlobalQuota(quota);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "Quota updated", "quota", quota));
    }

    // ============ 前端适配：管理员仪表盘 + 配额 ============

    @GetMapping({"/api/v1/admin/token/dashboard", "/api/admin/token/dashboard"})
    public ResponseEntity<?> getDashboardForFrontend() {
        try {
            Map<String, Object> rawData = tokenService.getDashboardForFrontend();
            return ResponseEntity.ok(Map.of("success", true, "rawData", rawData));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/api/v1/admin/token/usage")
    public ResponseEntity<?> getUsageForFrontend() {
        try {
            DepartmentUsageData data = tokenService.getDepartmentUsage();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("userUsageRows", data.users());
            result.put("departmentRows", data.departments());
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * PUT /api/v1/admin/token/quota
     * 设置全局默认限额
     */
    @PutMapping("/api/v1/admin/token/quota")
    public ResponseEntity<?> setQuotaForFrontend(@RequestBody Map<String, Object> body) {
        Object limitObj = body.get("daily_token_limit");
        if (limitObj == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "daily_token_limit is required"));
        }
        long newLimit = Long.parseLong(String.valueOf(limitObj));
        if (newLimit < 1000) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Token limit must be >= 1000"));
        }
        tokenService.setGlobalQuota(newLimit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("daily_limit", newLimit);
        result.put("affectedUsers", countTotalUsers());
        return ResponseEntity.ok(Map.of("success", true, "rawData", result));
    }

    /**
     * PUT /api/v1/admin/token/quota/{userId}
     * 管理员单独调整某员工的 Token 上限（自动记录操作日志）。
     *
     * 请求体：
     * { "daily_token_limit": 150000, "adminUserId": 1 }
     *
     * 当新限额 > 已消耗量时，员工立即恢复对话能力。
     * 前端轮询 /api/v1/user/token/usage 即可看到实时的剩余额度变化。
     */
    @PutMapping("/api/v1/admin/token/quota/{userId}")
    public ResponseEntity<?> setUserQuotaForFrontend(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body) {
        Object limitObj = body.get("daily_token_limit");
        if (limitObj == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "daily_token_limit is required"));
        }
        long newLimit = Long.parseLong(String.valueOf(limitObj));
        if (newLimit < 1000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Token limit must be >= 1000"));
        }

        // 操作人（管理员）ID：优先从请求体获取，其次从 Header
        Long adminUserId = parseLong(body.get("adminUserId"));
        if (adminUserId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "adminUserId is required"));
        }

        try {
            Map<String, Object> result = tokenService.adjustUserQuota(adminUserId, userId, newLimit);
            return ResponseEntity.ok(Map.of("success", true, "rawData", result,
                    "message", "User quota updated, log recorded"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    /**
     * DELETE /api/v1/admin/token/quota/{userId}
     * 重置某员工为全局默认限额（删除个性化配额记录）
     */
    @DeleteMapping("/api/v1/admin/token/quota/{userId}")
    public ResponseEntity<?> resetUserQuota(
            @PathVariable Long userId,
            @RequestParam Long adminUserId) {
        try {
            Map<String, Object> result = tokenService.resetUserQuota(adminUserId, userId);
            return ResponseEntity.ok(Map.of("success", true, "rawData", result,
                    "message", "User quota reset to system default"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    /**
     * GET /api/v1/admin/token/quota/{userId}
     * 查询某员工的配额详情（是否个性化、当前值等）
     */
    @GetMapping("/api/v1/admin/token/quota/{userId}")
    public ResponseEntity<?> getUserQuotaDetail(@PathVariable Long userId) {
        long effectiveQuota = tokenService.getUserQuota(userId);
        long globalQuota = tokenService.getGlobalQuota();
        boolean isPersonalized = tokenService.hasPersonalizedQuota(userId);
        long consumed = tokenService.getTodayConsumption(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("effectiveQuota", effectiveQuota);
        data.put("globalDefaultQuota", globalQuota);
        data.put("isPersonalized", isPersonalized);
        data.put("todayConsumed", consumed);
        data.put("todayRemaining", Math.max(0, effectiveQuota - consumed));

        if (isPersonalized) {
            tokenService.getPersonalizedQuota(userId).ifPresent(q -> {
                data.put("personalizedQuota", q.getDailyQuota());
                data.put("updatedBy", q.getUpdatedBy() != null ? q.getUpdatedBy().getId() : null);
                data.put("updatedByName", q.getUpdatedBy() != null ? q.getUpdatedBy().getName() : null);
                data.put("updatedAt", q.getUpdatedAt() != null ? q.getUpdatedAt().toEpochMilli() : null);
            });
        }

        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /**
     * GET /api/v1/admin/token/quota/logs
     * 查询配额调整操作日志（分页、支持筛选）。
     *
     * Query 参数（全部可选）：
     * - operatorId：操作人（管理员）ID
     * - targetUserId：目标员工ID
     * - startTime：起始时间（epoch 毫秒）
     * - endTime：结束时间（epoch 毫秒）
     * - page：页码（0-based，默认 0）
     * - size：每页条数（默认 20）
     */
    @GetMapping("/api/v1/admin/token/quota/logs")
    public ResponseEntity<?> getQuotaLogs(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Map<String, Object> logs = tokenService.queryQuotaLogs(
                    operatorId, targetUserId, startTime, endTime, page, size);
            return ResponseEntity.ok(Map.of("success", true, "rawData", logs));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ============ 对话（聊天）场景 ============

    @PostMapping({"/v1/chat/check", "/api/v1/chat/check"})
    public ResponseEntity<?> checkChat(@RequestBody Map<String, Object> body) {
        Long userId = parseUserId(body.get("userId"));
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "userId is required"));
        }
        String model = body.get("model") == null ? "default" : String.valueOf(body.get("model"));
        long estimated = toLong(body.get("estimatedTokens"), 2000L);

        boolean allowed = tokenService.checkQuota(userId, estimated);
        UserTokenStatus status = tokenService.getDailyStatus(userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "allowed", allowed,
                "model", model,
                "quota", status.quota(),
                "used", status.used(),
                "remaining", status.remaining(),
                "overQuota", status.overQuota()
        ));
    }

    @PostMapping({"/v1/chat/usage", "/api/v1/chat/usage"})
    public ResponseEntity<?> recordChat(@RequestBody Map<String, Object> body) {
        Long userId = parseUserId(body.get("userId"));
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "userId is required"));
        }
        String model = body.get("model") == null ? "default" : String.valueOf(body.get("model"));
        long prompt = toLong(body.get("promptTokens"), 0L);
        long completion = toLong(body.get("completionTokens"), 0L);

        tokenService.recordUsage(userId, model, prompt, completion);
        UserTokenStatus status = tokenService.getDailyStatus(userId);
        return ResponseEntity.ok(Map.of("success", true, "data", status));
    }

    // ============ 工具方法 ============

    private Long parseUserId(String userIdStr) {
        if (userIdStr == null || userIdStr.trim().isEmpty()) return null;
        try { return Long.parseLong(userIdStr.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Long parseUserId(Object obj) {
        if (obj == null) return null;
        try { return Long.parseLong(String.valueOf(obj).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private long toLong(Object obj, long def) {
        if (obj == null) return def;
        try { return Long.parseLong(String.valueOf(obj)); }
        catch (NumberFormatException e) { return def; }
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        try { return Long.parseLong(String.valueOf(obj).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private long countTotalUsers() {
        try { return tokenService.countActiveUsers(); }
        catch (Exception e) { return 0; }
    }
}
