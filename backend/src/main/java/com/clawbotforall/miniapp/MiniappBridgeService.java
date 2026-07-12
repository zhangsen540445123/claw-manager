package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MiniappBridgeService {
  private static final Map<String, Action> ACTIONS = actions();

  private final MiniappUserBindingMapper bindingMapper;
  private final MiniappUserKeyMapper keyMapper;
  private final RestClient restClient;
  private final String baseUrl;
  private final Clock clock;

  @Autowired
  public MiniappBridgeService(
      MiniappUserBindingMapper bindingMapper,
      MiniappUserKeyMapper keyMapper,
      RestClient.Builder restClientBuilder,
      @Value("${clawbot.miniapp-open-api-base-url:}") String baseUrl
  ) {
    this(bindingMapper, keyMapper, restClientBuilder, baseUrl, Clock.systemUTC());
  }

  MiniappBridgeService(
      MiniappUserBindingMapper bindingMapper,
      MiniappUserKeyMapper keyMapper,
      RestClient.Builder restClientBuilder,
      String baseUrl,
      Clock clock
  ) {
    this.bindingMapper = bindingMapper;
    this.keyMapper = keyMapper;
    this.restClient = restClientBuilder.build();
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.clock = clock;
  }

  public Object execute(String actionKey, MiniappBridgeRequest request) {
    if (baseUrl.isBlank()) {
      throw new ApiException(HttpStatus.CONFLICT, "小程序 Open API 地址尚未配置。");
    }
    Action action = ACTIONS.get(actionKey);
    if (action == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的小程序 actionKey。");
    }
    if (request == null || blank(request.instanceId()) || blank(request.requesterSenderId())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "缺少实例或发送者身份。");
    }
    MiniappUserBindingEntity binding = resolveBinding(request.requesterSenderId());
    if (binding == null || !"connected".equals(binding.getBindStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "当前用户尚未完成小程序微信绑定。");
    }
    if (!request.instanceId().equals(binding.getInstanceId())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "发送者不属于当前 OpenClaw 实例。");
    }
    MiniappUserKeyEntity key = keyMapper.findByOpenidHash(binding.getOpenidHash());
    if (key == null || !key.isEnabled() || blank(key.getUserKey())) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "当前用户没有可用的小程序用户 Key。");
    }

    Map<String, Object> parameters = request.parameters() == null ? Map.of() : request.parameters();
    String path = action.path(parameters);
    URI uri = URI.create(baseUrl + path + action.query(parameters));
    RestClient.RequestBodySpec spec = restClient.method(action.method()).uri(uri)
        .header("X-Open-Api-Openid", key.getOpenid())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.getUserKey())
        .header("X-CM-Bridge-Request-Id", safeRequestId(request.requestId()));
    Object response = action.hasBody()
        ? spec.body(action.body(parameters)).retrieve().body(Object.class)
        : spec.retrieve().body(Object.class);
    keyMapper.updateLastUsed(key.getOpenidHash(), clock.instant().toString());
    return response == null ? Map.of() : response;
  }

  private MiniappUserBindingEntity resolveBinding(String requesterSenderId) {
    String sender = requesterSenderId.trim();
    if (sender.startsWith("miniapp:")) {
      return bindingMapper.findByOpenidHash(sender.substring("miniapp:".length()));
    }
    return bindingMapper.findByWechatUserId(sender);
  }

  private static Map<String, Action> actions() {
    Map<String, Action> map = new LinkedHashMap<>();
    map.put("daily_checklist", Action.get("/open-api/daily-checklist", "date"));
    map.put("daily_task_create", Action.body(HttpMethod.POST, "/open-api/daily-tasks"));
    map.put("daily_task_update", Action.body(HttpMethod.PUT, "/open-api/daily-tasks/{taskId}", "taskId"));
    map.put("daily_task_toggle", Action.noBody(HttpMethod.PUT, "/open-api/daily-tasks/{taskId}/toggle", "taskId"));
    map.put("daily_task_delete", Action.noBody(HttpMethod.DELETE, "/open-api/daily-tasks/{taskId}", "taskId"));
    map.put("daily_task_yesterday_uncompleted_count", Action.get("/open-api/daily-tasks/yesterday-uncompleted-count"));
    map.put("goal_list", Action.get("/open-api/goals", "year", "month", "goalType", "status", "goalCategory", "goalArea", "completed", "keyword"));
    map.put("goal_get", Action.getPath("/open-api/goals/{goalId}", "goalId"));
    map.put("goal_create", Action.body(HttpMethod.POST, "/open-api/goals"));
    map.put("goal_update", Action.body(HttpMethod.PUT, "/open-api/goals/{goalId}", "goalId"));
    map.put("goal_delete", Action.noBody(HttpMethod.DELETE, "/open-api/goals/{goalId}", "goalId"));
    map.put("goal_toggle_completion", Action.query(HttpMethod.PUT, "/open-api/goals/{goalId}/toggle-completion", new String[]{"goalId"}, "completedMonth", "completionSummary", "completionImages"));
    map.put("goal_uncomplete", Action.noBody(HttpMethod.PUT, "/open-api/goals/{goalId}/uncomplete", "goalId"));
    map.put("goal_statistics", Action.get("/open-api/goals/statistics"));
    map.put("goal_year_month_statistics", Action.get("/open-api/goals/statistics/year-month", "year"));
    map.put("goal_categories", Action.get("/open-api/goals/categories", "year", "month", "goalType"));
    map.put("goal_category_list", Action.getPathAndQuery("/open-api/goals/category/{category}", new String[]{"category"}, "year", "month", "goalType"));
    map.put("subtask_list", Action.getPath("/open-api/goals/{goalId}/subtasks", "goalId"));
    map.put("subtask_create", Action.body(HttpMethod.POST, "/open-api/goals/{goalId}/subtasks", "goalId"));
    map.put("subtask_update", Action.body(HttpMethod.PUT, "/open-api/goals/{goalId}/subtasks/{subTaskId}", "goalId", "subTaskId"));
    map.put("subtask_delete", Action.noBody(HttpMethod.DELETE, "/open-api/goals/{goalId}/subtasks/{subTaskId}", "goalId", "subTaskId"));
    map.put("subtask_toggle", Action.query(HttpMethod.PUT, "/open-api/goals/{goalId}/subtasks/{subTaskId}/toggle-status", new String[]{"goalId", "subTaskId"}, "completedMonth", "completionSummary", "completionImages"));
    map.put("habit_checkin", Action.query(HttpMethod.POST, "/open-api/goals/{goalId}/checkin", new String[]{"goalId"}, "date"));
    map.put("habit_cancel", Action.query(HttpMethod.DELETE, "/open-api/goals/{goalId}/checkin", new String[]{"goalId"}, "date"));
    map.put("habit_status", Action.getPathAndQuery("/open-api/goals/{goalId}/checkin/status", new String[]{"goalId"}, "date"));
    map.put("habit_records", Action.getPath("/open-api/goals/{goalId}/checkin/records", "goalId"));
    map.put("habit_count", Action.getPath("/open-api/goals/{goalId}/checkin/count", "goalId"));
    map.put("habit_batch", Action.body(HttpMethod.POST, "/open-api/goals/{goalId}/checkin/batch", "goalId"));
    map.put("html_create", Action.body(HttpMethod.POST, "/open-api/html-content"));
    map.put("html_get", Action.getPath("/open-api/html-content/{contentKey}", "contentKey"));
    map.put("html_list", Action.get("/open-api/html-content"));
    map.put("html_delete", Action.noBody(HttpMethod.DELETE, "/open-api/html-content/{contentKey}", "contentKey"));
    return Map.copyOf(map);
  }

  private static String safeRequestId(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.isBlank() ? "mbreq_unknown" : normalized.substring(0, Math.min(100, normalized.length()));
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static String trimTrailingSlash(String value) { return value == null ? "" : value.trim().replaceFirst("/+$", ""); }

  private record Action(HttpMethod method, String pathTemplate, String[] pathKeys, String[] queryKeys, boolean hasBody) {
    static Action get(String path, String... queryKeys) { return new Action(HttpMethod.GET, path, new String[0], queryKeys, false); }
    static Action getPath(String path, String... pathKeys) { return new Action(HttpMethod.GET, path, pathKeys, new String[0], false); }
    static Action getPathAndQuery(String path, String[] pathKeys, String... queryKeys) { return new Action(HttpMethod.GET, path, pathKeys, queryKeys, false); }
    static Action body(HttpMethod method, String path, String... pathKeys) { return new Action(method, path, pathKeys, new String[0], true); }
    static Action noBody(HttpMethod method, String path, String... pathKeys) { return new Action(method, path, pathKeys, new String[0], false); }
    static Action query(HttpMethod method, String path, String[] pathKeys, String... queryKeys) { return new Action(method, path, pathKeys, queryKeys, false); }
    String path(Map<String, Object> params) {
      String result = pathTemplate;
      for (String key : pathKeys) {
        Object value = params.get(key);
        if (value == null || value.toString().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "缺少参数: " + key);
        result = result.replace("{" + key + "}", org.springframework.web.util.UriUtils.encodePathSegment(value.toString(), java.nio.charset.StandardCharsets.UTF_8));
      }
      return result;
    }
    String query(Map<String, Object> params) {
      org.springframework.web.util.UriComponentsBuilder builder = org.springframework.web.util.UriComponentsBuilder.newInstance();
      for (String key : queryKeys) if (params.get(key) != null) builder.queryParam(key, params.get(key));
      String query = builder.build().encode().toUriString();
      return query.isBlank() ? "" : query;
    }
    Map<String, Object> body(Map<String, Object> params) {
      Map<String, Object> body = new LinkedHashMap<>(params);
      for (String key : pathKeys) body.remove(key);
      return body;
    }
  }
}
