package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clawbotforall.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class MiniappBridgeActionRegistryTest {

  private final MiniappBridgeActionRegistry registry = new MiniappBridgeActionRegistry();

  @Test
  void preparesAllThirtyTwoActions() {
    assertThat(registry.actionKeys()).hasSize(32)
        .contains("daily_checklist", "goal_create", "subtask_update", "habit_batch", "html_delete");
  }

  @Test
  void rejectsUnknownParameters() {
    assertThatThrownBy(() -> registry.prepare("daily_checklist", Map.of("date", "2026-07-12", "dat", "typo")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("未知参数")
        .hasMessageContaining("dat");
  }

  @Test
  void normalizesYearProjectGoal() {
    MiniappBridgePreparedAction action = registry.prepare("goal_create", Map.of(
        "title", "学习英语",
        "goalType", "YEAR",
        "goalYear", 2026,
        "goalCategory", "PROJECT",
        "category", "study"));

    assertThat(action.method()).isEqualTo(HttpMethod.POST);
    assertThat(action.path()).isEqualTo("/open-api/goals");
    assertThat(action.body()).containsEntry("goalContent", "学习英语")
        .containsEntry("userTags", "study")
        .containsEntry("goalArea", "学习·成长")
        .containsEntry("status", "ACTIVE")
        .containsEntry("priority", "MEDIUM")
        .containsEntry("isFrogGoal", 0)
        .doesNotContainKey("category")
        .doesNotContainKey("goalMonth");
  }

  @Test
  void requiresMonthForMonthlyGoal() {
    assertThatThrownBy(() -> registry.prepare("goal_create", Map.of(
        "title", "七月阅读", "goalType", "MONTH", "goalYear", 2026,
        "goalCategory", "PROJECT", "category", "study")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("goalMonth");
  }

  @Test
  void rejectsGoalMonthForYearGoal() {
    assertThatThrownBy(() -> registry.prepare("goal_create", Map.of(
        "title", "年度阅读", "goalType", "YEAR", "goalYear", 2026, "goalMonth", 7,
        "goalCategory", "PROJECT", "category", "study")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("年度目标")
        .hasMessageContaining("goalMonth");
  }

  @Test
  void computesDailyHabitEndDate() {
    MiniappBridgePreparedAction action = registry.prepare("goal_create", habit(Map.of(
        "habitFrequencyType", "DAILY",
        "habitDailyWeekDays", List.of(1, 3, 5),
        "habitTargetDays", 4)));
    assertThat(action.body()).containsEntry("startTime", "2026-07-13 00:00:00")
        .containsEntry("endTime", "2026-07-20 23:59:59");
  }

  @Test
  void computesWeeklyHabitEndDate() {
    MiniappBridgePreparedAction action = registry.prepare("goal_create", habit(Map.of(
        "habitFrequencyType", "WEEKLY", "habitWeeklyDays", 5, "habitTargetDays", 8)));
    assertThat(action.body()).containsEntry("endTime", "2026-07-26 23:59:59");
  }

  @Test
  void computesPeriodHabitEndDate() {
    MiniappBridgePreparedAction action = registry.prepare("goal_create", habit(Map.of(
        "habitFrequencyType", "PERIOD", "habitIntervalDays", 3, "habitTargetDays", 4)));
    assertThat(action.body()).containsEntry("endTime", "2026-07-22 23:59:59");
  }

  @Test
  void preservesExplicitSemanticTimeWindowForHabitGoal() {
    MiniappBridgePreparedAction action = registry.prepare("goal_create", habit(Map.of(
        "goalType", "MONTH", "goalMonth", 7,
        "habitFrequencyType", "WEEKLY", "habitWeeklyDays", 3, "habitTargetDays", 19,
        "startTime", "2026-07-13 00:00:00", "endTime", "2026-07-31 23:59:59")));
    assertThat(action.body()).containsEntry("startTime", "2026-07-13 00:00:00")
        .containsEntry("endTime", "2026-07-31 23:59:59");
  }

  @Test
  void separatesPathQueryAndBody() {
    MiniappBridgePreparedAction action = registry.prepare("goal_toggle_completion", Map.of(
        "goalId", 12, "completedMonth", 7, "completionSummary", "完成"));
    assertThat(action.path()).isEqualTo("/open-api/goals/12/toggle-completion");
    assertThat(action.query()).containsEntry("completedMonth", 7).containsEntry("completionSummary", "完成");
    assertThat(action.body()).isEmpty();
  }

  @Test
  void rejectsInvalidDateTimeAndMonthRanges() {
    assertThatThrownBy(() -> registry.prepare("subtask_create", Map.of(
        "goalId", 1, "taskName", "准备材料", "startTime", "2026/07/12")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("yyyy-MM-dd HH:mm:ss");
    assertThatThrownBy(() -> registry.prepare("goal_list", Map.of("month", 13)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("month");
  }

  @Test
  void coercesSupportedScalarTypes() {
    MiniappBridgePreparedAction action = registry.prepare("goal_update", Map.of(
        "goalId", "12", "progress", "80", "clearHabitConfig", "true"));
    assertThat(action.path()).isEqualTo("/open-api/goals/12");
    assertThat(action.body()).containsEntry("progress", 80).containsEntry("clearHabitConfig", true);
  }

  private Map<String, Object> habit(Map<String, Object> overrides) {
    java.util.LinkedHashMap<String, Object> parameters = new java.util.LinkedHashMap<>();
    parameters.put("title", "坚持跑步");
    parameters.put("goalType", "YEAR");
    parameters.put("goalYear", 2026);
    parameters.put("goalCategory", "HABIT");
    parameters.put("category", "health");
    parameters.put("habitStartDate", "2026-07-13");
    parameters.put("habitTargetDays", 30);
    parameters.put("habitTargetCount", 1);
    parameters.put("habitSuffix", "次");
    parameters.putAll(overrides);
    return parameters;
  }
}
