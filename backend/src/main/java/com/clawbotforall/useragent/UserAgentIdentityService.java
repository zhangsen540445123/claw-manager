package com.clawbotforall.useragent;

import com.clawbotforall.miniapp.MiniappInstanceService;
import com.clawbotforall.openviking.OpenVikingIdentityService;
import com.clawbotforall.openviking.OpenVikingSenderIdentity;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.web.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAgentIdentityService {
  private static final Logger log = LoggerFactory.getLogger(UserAgentIdentityService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserAgentIdentityMapper mapper;
  private final MiniappInstanceService instanceService;
  private final OpenVikingSettingsService settingsService;
  private final OpenVikingIdentityService identityService;
  private final Clock clock;

  @Autowired
  public UserAgentIdentityService(
      UserAgentIdentityMapper mapper,
      MiniappInstanceService instanceService,
      OpenVikingSettingsService settingsService,
      OpenVikingIdentityService identityService
  ) {
    this(mapper, instanceService, settingsService, identityService, Clock.systemUTC());
  }

  UserAgentIdentityService(
      UserAgentIdentityMapper mapper,
      MiniappInstanceService instanceService,
      OpenVikingSettingsService settingsService,
      OpenVikingIdentityService identityService,
      Clock clock
  ) {
    this.mapper = mapper;
    this.instanceService = instanceService;
    this.settingsService = settingsService;
    this.identityService = identityService;
    this.clock = clock;
  }

  @Transactional
  public UserAgentIdentityResult resolve(String instanceId, String wechatUserId) {
    String normalizedInstanceId = normalize(instanceId);
    if (normalizedInstanceId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "instanceId 不能为空。");
    }
    instanceService.requireUsableApiInstance(normalizedInstanceId);

    String normalizedWechatUserId = normalize(wechatUserId);
    if (normalizedWechatUserId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "微信用户 ID 不能为空。");
    }

    UserAgentIdentityEntity existing = mapper.findByWechatUserId(normalizedWechatUserId);
    if (existing != null) {
      return result(existing, false);
    }

    String salt = settingsService.effectiveSettings().identityHashSecret();
    OpenVikingSenderIdentity senderIdentity = identityService
        .resolveSenderIdentity(normalizedWechatUserId, salt)
        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "无法派生微信用户 OpenViking 身份。"));
    String now = clock.instant().toString();
    UserAgentIdentityEntity candidate = new UserAgentIdentityEntity();
    candidate.setAgentId(generateAgentId());
    candidate.setWechatUserId(normalizedWechatUserId);
    candidate.setOpenvikingUserId(senderIdentity.openVikingUserId());
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);

    try {
      mapper.insert(candidate);
      log.info(
          "userAgent.identity.created instanceId={} agentId={} wechatUserHash={}",
          normalizedInstanceId,
          candidate.getAgentId(),
          senderIdentity.senderHash()
      );
      return result(candidate, true);
    } catch (DuplicateKeyException ignored) {
      UserAgentIdentityEntity winner = mapper.findByWechatUserIdForUpdate(normalizedWechatUserId);
      if (winner != null) {
        return result(winner, false);
      }
      throw new ApiException(HttpStatus.CONFLICT, "用户 Agent 身份创建冲突，请重试。");
    }
  }

  private static UserAgentIdentityResult result(UserAgentIdentityEntity entity, boolean created) {
    return new UserAgentIdentityResult(entity.getAgentId(), entity.getOpenvikingUserId(), created);
  }

  private static String generateAgentId() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return "user_" + HexFormat.of().formatHex(bytes);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
