package com.clawbotforall.openviking;

import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpenVikingUserKeyService {

  private static final Pattern OPENVIKING_USER_ID_PATTERN = Pattern.compile("(?:wx|api)_[0-9a-f]{32}");

  private final OpenVikingUserKeyMapper userKeyMapper;
  private final OpenVikingSettingsService settingsService;
  private final OpenVikingIdentityService identityService;
  private final OpenVikingAdminClient adminClient;
  private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

  public OpenVikingUserKeyService(
      OpenVikingUserKeyMapper userKeyMapper,
      OpenVikingSettingsService settingsService,
      OpenVikingIdentityService identityService,
      OpenVikingAdminClient adminClient
  ) {
    this.userKeyMapper = userKeyMapper;
    this.settingsService = settingsService;
    this.identityService = identityService;
    this.adminClient = adminClient;
  }

  @Transactional
  public OpenVikingResolvedUserKey resolve(OpenVikingUserResolveRequest request) {
    OpenVikingEffectiveSettings settings = settingsService.effectiveSettings();
    requireText(settings.baseUrl(), "OpenViking Base URL 不能为空。");
    requireText(settings.accountId(), "OpenViking Account ID 不能为空。");
    requireText(settings.rootApiKey(), "OpenViking Root API Key 未配置。");

    String openvikingUserId = resolveOpenVikingUserId(request, settings.identityHashSecret());
    String lockKey = settings.accountId() + ":" + openvikingUserId;
    Object lock = locks.computeIfAbsent(lockKey, ignored -> new Object());
    synchronized (lock) {
      OpenVikingUserKeyEntity cached = userKeyMapper.find(settings.accountId(), openvikingUserId);
      if (cached != null && hasText(cached.getUserKey())) {
        return new OpenVikingResolvedUserKey(settings.accountId(), openvikingUserId, cached.getUserKey(), false);
      }
      String userKey = registerOrRegenerate(settings, openvikingUserId);
      OpenVikingUserKeyEntity entity = new OpenVikingUserKeyEntity();
      String now = Instant.now().toString();
      entity.setAccountId(settings.accountId());
      entity.setOpenvikingUserId(openvikingUserId);
      entity.setUserKey(userKey);
      entity.setCreatedAt(now);
      entity.setUpdatedAt(now);
      userKeyMapper.upsert(entity);
      return new OpenVikingResolvedUserKey(settings.accountId(), openvikingUserId, userKey, true);
    }
  }

  private String registerOrRegenerate(OpenVikingEffectiveSettings settings, String openvikingUserId) {
    try {
      return adminClient.registerUser(settings.baseUrl(), settings.rootApiKey(), settings.accountId(), openvikingUserId);
    } catch (ApiException error) {
      String message = error.getMessage() == null ? "" : error.getMessage();
      if (!message.contains("ALREADY_EXISTS") && !message.contains("409")) {
        throw error;
      }
      return adminClient.regenerateUserKey(settings.baseUrl(), settings.rootApiKey(), settings.accountId(), openvikingUserId);
    }
  }

  private String resolveOpenVikingUserId(OpenVikingUserResolveRequest request, String identitySalt) {
    String supplied = request == null ? "" : trim(request.openvikingUserId());
    if (!supplied.isBlank()) {
      if (!OPENVIKING_USER_ID_PATTERN.matcher(supplied).matches()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking user identity 格式无效。");
      }
      return supplied;
    }
    return identityService.resolveSenderIdentity(request == null ? null : request.senderId(), identitySalt)
        .map(OpenVikingSenderIdentity::openVikingUserId)
        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "OpenViking user identity is unavailable for this turn."));
  }

  private static void requireText(String value, String message) {
    if (!hasText(value)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, message);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
