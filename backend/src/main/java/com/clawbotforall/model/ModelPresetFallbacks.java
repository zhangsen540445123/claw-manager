package com.clawbotforall.model;

import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 模型预设 fallback 列表的 JSON 存取与请求载荷规范化。
 */
public final class ModelPresetFallbacks {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private ModelPresetFallbacks() {}

  /**
   * 解析实体中持久化的 fallback 预设 ID JSON 数组；空值/非法内容返回空列表。
   */
  public static List<String> parse(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<String> parsed = JSON.readValue(json, STRING_LIST);
      if (parsed == null) {
        return List.of();
      }
      List<String> result = new ArrayList<>();
      for (String id : parsed) {
        if (id != null && !id.isBlank()) {
          result.add(id);
        }
      }
      return List.copyOf(result);
    } catch (JsonProcessingException error) {
      return List.of();
    }
  }

  /**
   * 将 fallback 预设 ID 列表写成 JSON；空列表返回 null（落库为 NULL）。
   */
  public static String toJsonOrNull(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return null;
    }
    try {
      return JSON.writeValueAsString(ids);
    } catch (JsonProcessingException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Fallback 模型预设配置无效。");
    }
  }

  /**
   * 将请求载荷中的 fallbackPresetIds 规范化为字符串列表；缺省返回空列表。
   */
  public static List<String> normalizeRequest(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object item : list) {
        if (item == null) {
          continue;
        }
        String value = String.valueOf(item).trim();
        if (!value.isBlank()) {
          result.add(value);
        }
      }
      return List.copyOf(result);
    }
    if (raw instanceof CharSequence text) {
      String value = text.toString().trim();
      if (value.isBlank()) {
        return List.of();
      }
      try {
        List<String> parsed = JSON.readValue(value, STRING_LIST);
        if (parsed == null) {
          return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String id : parsed) {
          if (id != null && !id.isBlank()) {
            result.add(id.trim());
          }
        }
        return List.copyOf(result);
      } catch (JsonProcessingException error) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "fallbackPresetIds 必须是预设 ID 数组。");
      }
    }
    throw new ApiException(HttpStatus.BAD_REQUEST, "fallbackPresetIds 必须是预设 ID 数组。");
  }
}
