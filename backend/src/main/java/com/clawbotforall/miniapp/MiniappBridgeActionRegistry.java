package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
class MiniappBridgeActionRegistry {
  private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final Map<String, String> CATEGORY_NAMES = Map.of(
      "study", "学习·成长", "experience", "体验·突破", "relax", "休闲·放松",
      "family", "家庭·生活", "core", "核心词", "work", "工作·事业",
      "social", "人际·社群", "finance", "财务·理财", "health", "健康·身体");
  private static final Set<String> GOAL_TYPES = Set.of("YEAR", "MONTH");
  private static final Set<String> GOAL_CATEGORIES = Set.of("PROJECT", "HABIT");
  private static final Set<String> FREQUENCIES = Set.of("DAILY", "WEEKLY", "PERIOD");
  private static final Map<String, Spec> ACTIONS = actions();

  Set<String> actionKeys() {
    return ACTIONS.keySet();
  }

  MiniappBridgePreparedAction prepare(String actionKey, Map<String, Object> input) {
    Spec spec = ACTIONS.get(actionKey);
    if (spec == null) throw badRequest("不支持的小程序 actionKey。");
    LinkedHashMap<String, Object> parameters = new LinkedHashMap<>(input == null ? Map.of() : input);
    parameters.entrySet().removeIf(entry -> entry.getValue() == null);
    rejectUnknown(spec, parameters);
    normalizeCommon(parameters);
    validateRequired(spec, parameters);
    if ("goal_create".equals(actionKey)) normalizeGoalCreate(parameters);
    if ("goal_list".equals(actionKey) && parameters.containsKey("category")) {
      String category = category(parameters.remove("category"));
      parameters.put("goalArea", CATEGORY_NAMES.get(category));
    }
    validateAction(actionKey, parameters);

    String path = spec.pathTemplate();
    for (String key : spec.pathKeys()) {
      Object value = parameters.remove(key);
      path = path.replace("{" + key + "}", UriUtils.encodePathSegment(String.valueOf(value), StandardCharsets.UTF_8));
    }
    LinkedHashMap<String, Object> query = new LinkedHashMap<>();
    for (String key : spec.queryKeys()) if (parameters.containsKey(key)) query.put(key, parameters.remove(key));
    LinkedHashMap<String, Object> body = spec.hasBody() ? parameters : new LinkedHashMap<>();
    return new MiniappBridgePreparedAction(spec.domain(), spec.operation(), actionKey, spec.method(), path,
        Map.copyOf(query), Map.copyOf(body));
  }

  private void normalizeGoalCreate(Map<String, Object> parameters) {
    for (String key : List.of("title", "goalType", "goalYear", "goalCategory", "category")) require(parameters, key);
    String title = text(parameters, "title");
    String goalType = upper(parameters, "goalType", GOAL_TYPES);
    String goalCategory = upper(parameters, "goalCategory", GOAL_CATEGORIES);
    int goalYear = integer(parameters, "goalYear", 2000, 9999);
    parameters.put("goalType", goalType);
    parameters.put("goalCategory", goalCategory);
    parameters.put("goalYear", goalYear);
    if ("MONTH".equals(goalType)) {
      require(parameters, "goalMonth");
      parameters.put("goalMonth", integer(parameters, "goalMonth", 1, 12));
    } else if (parameters.containsKey("goalMonth")) {
      throw badRequest("年度目标不能携带 goalMonth。");
    }
    String category = category(parameters.remove("category"));
    parameters.put("userTags", category);
    parameters.put("goalArea", CATEGORY_NAMES.get(category));
    parameters.putIfAbsent("goalContent", title);
    parameters.putIfAbsent("status", "ACTIVE");
    parameters.putIfAbsent("priority", "MEDIUM");
    parameters.putIfAbsent("isFrogGoal", 0);
    if ("HABIT".equals(goalCategory)) normalizeHabit(parameters);
  }

  private void normalizeHabit(Map<String, Object> parameters) {
    for (String key : List.of("habitStartDate", "habitTargetDays", "habitTargetCount", "habitSuffix", "habitFrequencyType")) require(parameters, key);
    LocalDate start = date(parameters, "habitStartDate");
    int targetDays = integer(parameters, "habitTargetDays", -1, Integer.MAX_VALUE);
    if (targetDays == 0 || targetDays < -1) throw badRequest("habitTargetDays 必须为正整数或 -1。");
    parameters.put("habitTargetDays", targetDays);
    parameters.put("habitTargetCount", integer(parameters, "habitTargetCount", 1, Integer.MAX_VALUE));
    String frequency = upper(parameters, "habitFrequencyType", FREQUENCIES);
    parameters.put("habitFrequencyType", frequency);
    LocalDate end;
    if (targetDays == -1) {
      end = start.plusDays(36_500);
    } else if ("DAILY".equals(frequency)) {
      require(parameters, "habitDailyWeekDays");
      List<Integer> weekdays = integerList(parameters.get("habitDailyWeekDays"), "habitDailyWeekDays", 0, 6);
      parameters.put("habitDailyWeekDays", weekdays);
      end = dailyEnd(start, targetDays, weekdays);
    } else if ("WEEKLY".equals(frequency)) {
      require(parameters, "habitWeeklyDays");
      int weeklyDays = integer(parameters, "habitWeeklyDays", 1, 7);
      parameters.put("habitWeeklyDays", weeklyDays);
      int calendarDays = weeklyDays >= 7 ? targetDays : ((targetDays + weeklyDays - 1) / weeklyDays) * 7;
      end = start.plusDays(calendarDays - 1L);
    } else {
      require(parameters, "habitIntervalDays");
      int interval = integer(parameters, "habitIntervalDays", 1, Integer.MAX_VALUE);
      parameters.put("habitIntervalDays", interval);
      end = start.plusDays((long) (targetDays - 1) * interval);
    }
    parameters.put("habitStartDate", start.toString());
    parameters.putIfAbsent("startTime", start + " 00:00:00");
    parameters.putIfAbsent("endTime", end + " 23:59:59");
  }

  private LocalDate dailyEnd(LocalDate start, int targetDays, List<Integer> weekdays) {
    int count = 0;
    LocalDate current = start;
    while (count < targetDays) {
      int jsDay = current.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : current.getDayOfWeek().getValue();
      if (weekdays.contains(jsDay)) count++;
      if (count < targetDays) current = current.plusDays(1);
    }
    return current;
  }

  private void normalizeCommon(Map<String, Object> parameters) {
    normalizeIntegers(parameters, 1, Integer.MAX_VALUE, "goalId", "taskId", "subTaskId", "count", "habitTargetCount", "habitIntervalDays");
    normalizeIntegers(parameters, 2000, 9999, "year", "goalYear");
    normalizeIntegers(parameters, 1, 12, "month", "goalMonth", "completedMonth");
    normalizeIntegers(parameters, 0, 1, "completed", "isFrogGoal");
    normalizeIntegers(parameters, 0, 100, "progress");
    normalizeIntegers(parameters, 0, Integer.MAX_VALUE, "sortOrder", "habitLivesRemaining");
    normalizeIntegers(parameters, 1, 7, "habitWeeklyDays");
    if (parameters.containsKey("habitTargetDays")) {
      int targetDays = integer(parameters, "habitTargetDays", -1, Integer.MAX_VALUE);
      if (targetDays == 0) throw badRequest("habitTargetDays 必须为正整数或 -1。");
      parameters.put("habitTargetDays", targetDays);
    }
    for (String key : List.of("date", "habitStartDate")) if (parameters.containsKey(key)) parameters.put(key, date(parameters, key).toString());
    for (String key : List.of("deadline", "startTime", "endTime")) if (parameters.containsKey(key)) parameters.put(key, dateTime(parameters, key));
    if (parameters.containsKey("clearHabitConfig")) parameters.put("clearHabitConfig", bool(parameters, "clearHabitConfig"));
    if (parameters.containsKey("habitDailyWeekDays")) parameters.put("habitDailyWeekDays", integerList(parameters.get("habitDailyWeekDays"), "habitDailyWeekDays", 0, 6));
  }

  private void normalizeIntegers(Map<String, Object> parameters, int min, int max, String... keys) {
    for (String key : keys) if (parameters.containsKey(key)) parameters.put(key, integer(parameters, key, min, max));
  }

  private void validateAction(String actionKey, Map<String, Object> parameters) {
    if (parameters.containsKey("goalType")) parameters.put("goalType", upper(parameters, "goalType", GOAL_TYPES));
    if (parameters.containsKey("goalCategory")) parameters.put("goalCategory", upper(parameters, "goalCategory", GOAL_CATEGORIES));
    if (parameters.containsKey("category")) category(parameters.get("category"));
    if (actionKey.startsWith("html_") && parameters.containsKey("htmlContent") && text(parameters, "htmlContent").isBlank()) throw badRequest("htmlContent 不能为空。");
  }

  private void rejectUnknown(Spec spec, Map<String, Object> parameters) {
    LinkedHashSet<String> allowed = new LinkedHashSet<>();
    allowed.addAll(spec.pathKeys()); allowed.addAll(spec.queryKeys()); allowed.addAll(spec.bodyKeys());
    List<String> unknown = parameters.keySet().stream().filter(key -> !allowed.contains(key)).toList();
    if (!unknown.isEmpty()) throw badRequest("未知参数: " + String.join(", ", unknown));
  }

  private void validateRequired(Spec spec, Map<String, Object> parameters) {
    for (String key : spec.requiredKeys()) require(parameters, key);
  }

  private static Map<String, Spec> actions() {
    LinkedHashMap<String, Spec> map = new LinkedHashMap<>();
    add(map, "daily_checklist", "daily_task", "get_checklist", HttpMethod.GET, "/open-api/daily-checklist", p(), q("date"), b(), r(), false);
    add(map, "daily_task_create", "daily_task", "create", HttpMethod.POST, "/open-api/daily-tasks", p(), q(), b("title", "date", "goalId"), r("title"), true);
    add(map, "daily_task_update", "daily_task", "update", HttpMethod.PUT, "/open-api/daily-tasks/{taskId}", p("taskId"), q(), b("title", "goalId"), r("taskId", "title"), true);
    add(map, "daily_task_toggle", "daily_task", "toggle", HttpMethod.PUT, "/open-api/daily-tasks/{taskId}/toggle", p("taskId"), q(), b(), r("taskId"), false);
    add(map, "daily_task_delete", "daily_task", "delete", HttpMethod.DELETE, "/open-api/daily-tasks/{taskId}", p("taskId"), q(), b(), r("taskId"), false);
    add(map, "daily_task_yesterday_uncompleted_count", "daily_task", "yesterday_uncompleted_count", HttpMethod.GET, "/open-api/daily-tasks/yesterday-uncompleted-count", p(), q(), b(), r(), false);
    add(map, "goal_list", "goal", "list", HttpMethod.GET, "/open-api/goals", p(), q("year", "month", "goalType", "status", "goalCategory", "goalArea", "completed", "keyword", "category"), b(), r(), false);
    add(map, "goal_get", "goal", "get", HttpMethod.GET, "/open-api/goals/{goalId}", p("goalId"), q(), b(), r("goalId"), false);
    add(map, "goal_create", "goal", "create", HttpMethod.POST, "/open-api/goals", p(), q(), goalCreateFields(), r("title", "goalType", "goalYear", "goalCategory", "category"), true);
    add(map, "goal_update", "goal", "update", HttpMethod.PUT, "/open-api/goals/{goalId}", p("goalId"), q(), b("title", "goalContent", "description", "status", "priority", "deadline", "progress", "isFrogGoal", "startTime", "endTime", "icon", "goalCategory", "habitPrefix", "habitTargetCount", "habitSuffix", "completionSummary", "clearHabitConfig", "habitFrequencyType", "habitDailyWeekDays", "habitWeeklyDays", "habitEncourageText", "habitLivesRemaining", "habitTargetDays", "habitStartDate", "habitIntervalDays"), r("goalId"), true);
    add(map, "goal_delete", "goal", "delete", HttpMethod.DELETE, "/open-api/goals/{goalId}", p("goalId"), q(), b(), r("goalId"), false);
    add(map, "goal_toggle_completion", "goal", "toggle_completion", HttpMethod.PUT, "/open-api/goals/{goalId}/toggle-completion", p("goalId"), q("completedMonth", "completionSummary", "completionImages"), b(), r("goalId"), false);
    add(map, "goal_uncomplete", "goal", "uncomplete", HttpMethod.PUT, "/open-api/goals/{goalId}/uncomplete", p("goalId"), q(), b(), r("goalId"), false);
    add(map, "goal_statistics", "goal", "statistics", HttpMethod.GET, "/open-api/goals/statistics", p(), q(), b(), r(), false);
    add(map, "goal_year_month_statistics", "goal", "year_month_statistics", HttpMethod.GET, "/open-api/goals/statistics/year-month", p(), q("year"), b(), r(), false);
    add(map, "goal_categories", "goal", "categories", HttpMethod.GET, "/open-api/goals/categories", p(), q("year", "month", "goalType"), b(), r(), false);
    add(map, "goal_category_list", "goal", "category_list", HttpMethod.GET, "/open-api/goals/category/{category}", p("category"), q("year", "month"), b(), r("category"), false);
    add(map, "subtask_list", "subtask", "list", HttpMethod.GET, "/open-api/goals/{goalId}/subtasks", p("goalId"), q(), b(), r("goalId"), false);
    add(map, "subtask_create", "subtask", "create", HttpMethod.POST, "/open-api/goals/{goalId}/subtasks", p("goalId"), q(), b("taskName", "startTime", "endTime", "sortOrder"), r("goalId", "taskName"), true);
    add(map, "subtask_update", "subtask", "update", HttpMethod.PUT, "/open-api/goals/{goalId}/subtasks/{subTaskId}", p("goalId", "subTaskId"), q(), b("taskName", "startTime", "endTime", "sortOrder"), r("goalId", "subTaskId", "taskName"), true);
    add(map, "subtask_delete", "subtask", "delete", HttpMethod.DELETE, "/open-api/goals/{goalId}/subtasks/{subTaskId}", p("goalId", "subTaskId"), q(), b(), r("goalId", "subTaskId"), false);
    add(map, "subtask_toggle", "subtask", "toggle", HttpMethod.PUT, "/open-api/goals/{goalId}/subtasks/{subTaskId}/toggle-status", p("goalId", "subTaskId"), q("completedMonth", "completionSummary", "completionImages"), b(), r("goalId", "subTaskId"), false);
    add(map, "habit_checkin", "habit_checkin", "checkin", HttpMethod.POST, "/open-api/goals/{goalId}/checkin", p("goalId"), q("date"), b(), r("goalId"), false);
    add(map, "habit_cancel", "habit_checkin", "cancel", HttpMethod.DELETE, "/open-api/goals/{goalId}/checkin", p("goalId"), q("date"), b(), r("goalId"), false);
    add(map, "habit_status", "habit_checkin", "status", HttpMethod.GET, "/open-api/goals/{goalId}/checkin/status", p("goalId"), q("date"), b(), r("goalId"), false);
    add(map, "habit_records", "habit_checkin", "records", HttpMethod.GET, "/open-api/goals/{goalId}/checkin/records", p("goalId"), q(), b(), r("goalId"), false);
    add(map, "habit_count", "habit_checkin", "count", HttpMethod.GET, "/open-api/goals/{goalId}/checkin/count", p("goalId"), q(), b(), r("goalId"), false);
    add(map, "habit_batch", "habit_checkin", "batch", HttpMethod.POST, "/open-api/goals/{goalId}/checkin/batch", p("goalId"), q(), b("count"), r("goalId", "count"), true);
    add(map, "html_create", "html_content", "create", HttpMethod.POST, "/open-api/html-content", p(), q(), b("title", "htmlContent", "contentKey"), r("htmlContent"), true);
    add(map, "html_get", "html_content", "get", HttpMethod.GET, "/open-api/html-content/{contentKey}", p("contentKey"), q(), b(), r("contentKey"), false);
    add(map, "html_list", "html_content", "list", HttpMethod.GET, "/open-api/html-content", p(), q(), b(), r(), false);
    add(map, "html_delete", "html_content", "delete", HttpMethod.DELETE, "/open-api/html-content/{contentKey}", p("contentKey"), q(), b(), r("contentKey"), false);
    return Map.copyOf(map);
  }

  private static String[] goalCreateFields() {
    return b("title", "description", "priority", "deadline", "goalType", "goalYear", "goalMonth", "goalArea", "goalContent", "userTags", "status", "isFrogGoal", "startTime", "endTime", "icon", "goalCategory", "category", "habitPrefix", "habitTargetCount", "habitSuffix", "habitFrequencyType", "habitDailyWeekDays", "habitWeeklyDays", "habitEncourageText", "habitLivesRemaining", "habitTargetDays", "habitStartDate", "habitIntervalDays");
  }

  private static void add(Map<String, Spec> map, String key, String domain, String operation, HttpMethod method,
      String path, String[] pathKeys, String[] queryKeys, String[] bodyKeys, String[] required, boolean hasBody) {
    map.put(key, new Spec(domain, operation, method, path, List.of(pathKeys), List.of(queryKeys), List.of(bodyKeys), List.of(required), hasBody));
  }
  private static String[] p(String... values) { return values; }
  private static String[] q(String... values) { return values; }
  private static String[] b(String... values) { return values; }
  private static String[] r(String... values) { return values; }

  private static void require(Map<String, Object> parameters, String key) {
    Object value = parameters.get(key);
    if (value == null || value instanceof String text && text.isBlank() || value instanceof List<?> list && list.isEmpty()) throw badRequest("缺少参数: " + key);
  }
  private static String text(Map<String, Object> parameters, String key) { require(parameters, key); return String.valueOf(parameters.get(key)).trim(); }
  private static int integer(Map<String, Object> parameters, String key, int min, int max) {
    require(parameters, key);
    try {
      int value = parameters.get(key) instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(parameters.get(key)));
      if (value < min || value > max) throw new NumberFormatException();
      return value;
    } catch (NumberFormatException error) { throw badRequest(key + " 必须是有效整数。"); }
  }
  private static LocalDate date(Map<String, Object> parameters, String key) {
    try { return LocalDate.parse(text(parameters, key), DATE); }
    catch (DateTimeParseException error) { throw badRequest(key + " 必须使用 yyyy-MM-dd 格式。"); }
  }
  private static String dateTime(Map<String, Object> parameters, String key) {
    try { return LocalDateTime.parse(text(parameters, key), DATE_TIME).format(DATE_TIME); }
    catch (DateTimeParseException error) { throw badRequest(key + " 必须使用 yyyy-MM-dd HH:mm:ss 格式。"); }
  }
  private static boolean bool(Map<String, Object> parameters, String key) {
    Object value = parameters.get(key);
    if (value instanceof Boolean result) return result;
    String text = String.valueOf(value).trim();
    if ("true".equalsIgnoreCase(text)) return true;
    if ("false".equalsIgnoreCase(text)) return false;
    throw badRequest(key + " 必须是布尔值。");
  }
  private static String upper(Map<String, Object> parameters, String key, Set<String> allowed) {
    String value = text(parameters, key).toUpperCase();
    if (!allowed.contains(value)) throw badRequest(key + " 的值无效。");
    return value;
  }
  private static String category(Object value) {
    String category = String.valueOf(value).trim();
    if (!CATEGORY_NAMES.containsKey(category)) throw badRequest("category 的值无效。");
    return category;
  }
  private static List<Integer> integerList(Object raw, String key, int min, int max) {
    if (!(raw instanceof List<?> values) || values.isEmpty()) throw badRequest(key + " 必须是非空数组。");
    List<Integer> result = new ArrayList<>();
    for (Object value : values) {
      try {
        int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        if (parsed < min || parsed > max) throw new NumberFormatException();
        if (!result.contains(parsed)) result.add(parsed);
      } catch (NumberFormatException error) { throw badRequest(key + " 包含无效整数。"); }
    }
    return List.copyOf(result);
  }
  private static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }

  private record Spec(String domain, String operation, HttpMethod method, String pathTemplate,
      List<String> pathKeys, List<String> queryKeys, List<String> bodyKeys, List<String> requiredKeys, boolean hasBody) {}
}
