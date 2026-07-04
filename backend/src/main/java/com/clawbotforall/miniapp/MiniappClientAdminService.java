package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MiniappClientAdminService {
  private static final Pattern APP_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,80}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final MiniappClientMapper mapper;
  private final Clock clock;
  private final MiniappSecretGenerator secretGenerator;

  @Autowired
  public MiniappClientAdminService(MiniappClientMapper mapper) {
    this(mapper, Clock.systemUTC(), MiniappClientAdminService::generateSecret);
  }

  MiniappClientAdminService(
      MiniappClientMapper mapper,
      Clock clock,
      MiniappSecretGenerator secretGenerator
  ) {
    this.mapper = mapper;
    this.clock = clock;
    this.secretGenerator = secretGenerator;
  }

  public List<PublicMiniappClient> listClients() {
    return mapper.list().stream()
        .map(client -> toPublic(client, null, false))
        .toList();
  }

  @Transactional
  public PublicMiniappClient createClient(String appId, boolean enabled) {
    String normalizedAppId = normalizeAppId(appId);
    String now = clock.instant().toString();
    String secret = secretGenerator.generate();
    MiniappClientEntity entity = new MiniappClientEntity();
    entity.setAppId(normalizedAppId);
    entity.setAppSecret(secret);
    entity.setEnabled(enabled);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    try {
      mapper.insert(entity);
    } catch (DuplicateKeyException error) {
      throw new ApiException(HttpStatus.CONFLICT, "小程序 AK 已存在。");
    }
    return toPublic(entity, secret, true);
  }

  @Transactional
  public PublicMiniappClient updateEnabled(String appId, boolean enabled) {
    MiniappClientEntity existing = requireClient(appId);
    String now = clock.instant().toString();
    mapper.updateEnabled(existing.getAppId(), enabled, now);
    existing.setEnabled(enabled);
    existing.setUpdatedAt(now);
    return toPublic(existing, null, false);
  }

  @Transactional
  public PublicMiniappClient resetSecret(String appId) {
    MiniappClientEntity existing = requireClient(appId);
    String secret = secretGenerator.generate();
    String now = clock.instant().toString();
    mapper.updateSecret(existing.getAppId(), secret, now);
    existing.setAppSecret(secret);
    existing.setUpdatedAt(now);
    return toPublic(existing, secret, false);
  }

  @Transactional
  public void deleteClient(String appId) {
    String normalizedAppId = normalizeAppId(appId);
    if (mapper.deleteByAppId(normalizedAppId) == 0) {
      throw new ApiException(HttpStatus.NOT_FOUND, "小程序 AK 不存在。");
    }
  }

  private MiniappClientEntity requireClient(String appId) {
    String normalizedAppId = normalizeAppId(appId);
    MiniappClientEntity existing = mapper.findByAppId(normalizedAppId);
    if (existing == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "小程序 AK 不存在。");
    }
    return existing;
  }

  private PublicMiniappClient toPublic(MiniappClientEntity client, String plainSecret, boolean created) {
    String secret = defaultString(plainSecret);
    return new PublicMiniappClient(
        defaultString(client.getAppId()),
        secret.isBlank() ? null : secret,
        preview(secret.isBlank() ? client.getAppSecret() : secret),
        client.isEnabled(),
        defaultString(client.getCreatedAt()),
        defaultString(client.getUpdatedAt()),
        created
    );
  }

  private static String normalizeAppId(String appId) {
    String normalized = defaultString(appId).trim();
    if (!APP_ID_PATTERN.matcher(normalized).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "小程序 AK 只能包含 3-80 位字母、数字、下划线或短横线。");
    }
    return normalized;
  }

  private static String generateSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "cm_sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String preview(String value) {
    String normalized = defaultString(value);
    if (normalized.isBlank() || normalized.length() <= 16) {
      return normalized;
    }
    return normalized.substring(0, 12) + "..." + normalized.substring(normalized.length() - 4);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
