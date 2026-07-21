package com.clawbotforall.instance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OpenClawSkillLoadConfig {

  public static final String SHARED_SKILLS_EXTRA_DIR = "/workspace/skills";

  private OpenClawSkillLoadConfig() {}

  public static Map<String, Object> managedSkillsConfig() {
    Map<String, Object> load = new LinkedHashMap<>();
    load.put("extraDirs", List.of(SHARED_SKILLS_EXTRA_DIR));
    load.put("watch", true);
    return Map.of("load", load);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> mergeSkillsConfig(Object existingValue) {
    Map<String, Object> result = existingValue instanceof Map<?, ?> existingMap
        ? new LinkedHashMap<>((Map<String, Object>) stringKeyMap(existingMap))
        : new LinkedHashMap<>();

    Map<String, Object> load = result.get("load") instanceof Map<?, ?> existingLoad
        ? new LinkedHashMap<>((Map<String, Object>) stringKeyMap(existingLoad))
        : new LinkedHashMap<>();
    load.put("extraDirs", appendSharedExtraDir(load.get("extraDirs")));
    load.putIfAbsent("watch", true);
    result.put("load", load);
    return result;
  }

  public static void ensureConfigFile(Path configPath, ObjectMapper objectMapper) throws IOException {
    Files.createDirectories(configPath.getParent());
    Map<String, Object> config = readConfig(configPath, objectMapper);
    Map<String, Object> skills = mergeSkillsConfig(config.get("skills"));
    if (Objects.equals(config.get("skills"), skills)) {
      return;
    }
    config.put("skills", skills);
    objectMapper.writeValue(configPath.toFile(), config);
  }

  private static Map<String, Object> readConfig(Path configPath, ObjectMapper objectMapper) throws IOException {
    if (!Files.exists(configPath)) {
      return new LinkedHashMap<>();
    }
    return objectMapper.readValue(configPath.toFile(), new TypeReference<>() {});
  }

  private static List<String> appendSharedExtraDir(Object value) {
    LinkedHashSet<String> dirs = new LinkedHashSet<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof String dir && !dir.trim().isBlank()) {
          dirs.add(dir.trim());
        }
      }
    }
    dirs.add(SHARED_SKILLS_EXTRA_DIR);
    return new ArrayList<>(dirs);
  }

  private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
  }
}
