package com.clawbotforall.miniapp;

import com.clawbotforall.externalapi.ExternalApiIdentity;
import com.clawbotforall.externalapi.ExternalApiIdentityService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingIdentityService;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.openviking.OpenVikingSenderIdentity;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.wechat.WechatBindLinkEntity;
import com.clawbotforall.wechat.WechatBindLinkMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MiniappUserAccessService {
  private static final Logger log = LoggerFactory.getLogger(MiniappUserAccessService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final MiniappUserBindingMapper bindingMapper;
  private final MiniappUserKeyMapper keyMapper;
  private final OpenVikingSettingsService openVikingSettingsService;
  private final ExternalApiIdentityService identityService;
  private final MiniappInstanceService instanceService;
  private final WechatBindLinkMapper bindLinkMapper;
  private final OpenVikingIdentityService openVikingIdentityService;
  private final Clock clock;

  @Autowired
  public MiniappUserAccessService(
      MiniappUserBindingMapper bindingMapper,
      MiniappUserKeyMapper keyMapper,
      OpenVikingSettingsService openVikingSettingsService,
      ExternalApiIdentityService identityService,
      MiniappInstanceService instanceService,
      WechatBindLinkMapper bindLinkMapper,
      OpenVikingIdentityService openVikingIdentityService
  ) {
    this(
        bindingMapper,
        keyMapper,
        openVikingSettingsService,
        identityService,
        instanceService,
        bindLinkMapper,
        openVikingIdentityService,
        Clock.systemUTC()
    );
  }

  MiniappUserAccessService(
      MiniappUserBindingMapper bindingMapper,
      MiniappUserKeyMapper keyMapper,
      OpenVikingSettingsService openVikingSettingsService,
      ExternalApiIdentityService identityService,
      MiniappInstanceService instanceService,
      Clock clock
  ) {
    this(bindingMapper, keyMapper, openVikingSettingsService, identityService, instanceService, null, null, clock);
  }

  MiniappUserAccessService(
      MiniappUserBindingMapper bindingMapper,
      MiniappUserKeyMapper keyMapper,
      OpenVikingSettingsService openVikingSettingsService,
      ExternalApiIdentityService identityService,
      MiniappInstanceService instanceService,
      WechatBindLinkMapper bindLinkMapper,
      OpenVikingIdentityService openVikingIdentityService,
      Clock clock
  ) {
    this.bindingMapper = bindingMapper;
    this.keyMapper = keyMapper;
    this.openVikingSettingsService = openVikingSettingsService;
    this.identityService = identityService;
    this.instanceService = instanceService;
    this.bindLinkMapper = bindLinkMapper;
    this.openVikingIdentityService = openVikingIdentityService;
    this.clock = clock;
  }

  @Transactional
  public MiniappUserKeyResult createOrGetUserKey(String openid, boolean reset) {
    ExternalApiIdentity identity = resolveOpenid(openid);
    MiniappUserBindingEntity binding = reconcileBinding(identity.openidHash());
    if (binding == null || !"connected".equals(binding.getBindStatus()) || blank(binding.getOpenvikingUserId())) {
      throw new ApiException(HttpStatus.CONFLICT, "小程序用户尚未完成微信扫码绑定。");
    }
    MiniappUserKeyEntity existing = keyMapper.findByOpenidHash(identity.openidHash());
    String now = clock.instant().toString();
    if (existing != null && existing.isEnabled() && !reset) {
      keyMapper.updateLastUsed(identity.openidHash(), now);
      return new MiniappUserKeyResult(
          identity.openid(),
          null,
          existing.getKeyPreview(),
          binding.getOpenvikingUserId(),
          binding.getInstanceId(),
          false
      );
    }
    MiniappUserKeyEntity key = new MiniappUserKeyEntity();
    key.setOpenidHash(identity.openidHash());
    key.setOpenid(identity.openid());
    key.setUserKey(generateUserKey());
    key.setKeyPreview(preview(key.getUserKey()));
    key.setEnabled(true);
    key.setCreatedAt(existing == null ? now : existing.getCreatedAt());
    key.setUpdatedAt(now);
    key.setLastUsedAt(now);
    if (existing == null) {
      keyMapper.insert(key);
    } else {
      keyMapper.replaceKey(key);
    }
    return new MiniappUserKeyResult(
        identity.openid(),
        key.getUserKey(),
        key.getKeyPreview(),
        binding.getOpenvikingUserId(),
        binding.getInstanceId(),
        true
    );
  }

  @Transactional
  public MiniappChatRoute resolveChatRoute(String authorization, String requestOpenid) {
    String token = bearerToken(authorization);
    MiniappUserKeyEntity key = keyMapper.findByUserKey(token);
    if (key == null || !key.isEnabled()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "用户 key 无效或已禁用。");
    }
    String bodyOpenid = trim(requestOpenid);
    if (!bodyOpenid.isBlank() && !bodyOpenid.equals(key.getOpenid())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "请求 openid 与用户 key 不匹配。");
    }
    MiniappUserBindingEntity binding = reconcileBinding(key.getOpenidHash());
    if (binding == null || !"connected".equals(binding.getBindStatus()) || blank(binding.getOpenvikingUserId())) {
      throw new ApiException(HttpStatus.CONFLICT, "小程序用户尚未完成微信扫码绑定。");
    }
    InstanceEntity instance = instanceService.requireUsableApiInstance(binding.getInstanceId());
    keyMapper.updateLastUsed(key.getOpenidHash(), clock.instant().toString());
    return new MiniappChatRoute(
        instance,
        key.getOpenid(),
        key.getOpenidHash(),
        binding.getOpenvikingUserId(),
        "miniapp:" + key.getOpenidHash()
    );
  }

  public String conversationHash(String conversationId) {
    return identityService.conversationHash(
        conversationId,
        openVikingSettingsService.effectiveSettings().identityHashSecret()
    );
  }

  ExternalApiIdentity resolveOpenid(String openid) {
    OpenVikingEffectiveSettings settings = openVikingSettingsService.effectiveSettings();
    return identityService.resolve(openid, settings.identityHashSecret());
  }

  MiniappUserBindingEntity reconcileBinding(String openidHash) {
    MiniappUserBindingEntity binding = bindingMapper.findByOpenidHash(openidHash);
    if (binding == null || bindLinkMapper == null || openVikingIdentityService == null) {
      return binding;
    }
    String token = trim(binding.getCurrentBindToken());
    if (token.isBlank()) {
      return binding;
    }
    WechatBindLinkEntity link = bindLinkMapper.findByToken(token);
    if (link == null) {
      return binding;
    }
    if ("rejected".equals(link.getStatus())) {
      bindingMapper.updateStatus(openidHash, "rejected", clock.instant().toString());
      binding.setBindStatus("rejected");
      return binding;
    }
    if (!"connected".equals(link.getStatus()) || blank(link.getScannedWechatUserId())) {
      return binding;
    }
    OpenVikingSenderIdentity senderIdentity = openVikingIdentityService
        .resolveSenderIdentity(
            link.getScannedWechatUserId(),
            openVikingSettingsService.effectiveSettings().identityHashSecret()
        )
        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "无法派生微信用户 OpenViking 身份。"));
    String now = clock.instant().toString();
    bindingMapper.markConnected(openidHash, link.getScannedWechatUserId(), senderIdentity.openVikingUserId(), now, now);
    binding.setWechatUserId(link.getScannedWechatUserId());
    binding.setOpenvikingUserId(senderIdentity.openVikingUserId());
    binding.setBindStatus("connected");
    binding.setBoundAt(now);
    binding.setUpdatedAt(now);
    log.info(
        "miniapp.binding.identityReady openidHash={} bindToken={} linkStatus={} instanceId={} wechatUserId={} openVikingUserId={}",
        openidHash,
        token,
        link.getStatus(),
        binding.getInstanceId(),
        link.getScannedWechatUserId(),
        senderIdentity.openVikingUserId()
    );
    return binding;
  }

  private static String bearerToken(String authorization) {
    String value = trim(authorization);
    if (!value.startsWith("Bearer ")) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少用户 Bearer key。");
    }
    String token = value.substring("Bearer ".length()).trim();
    if (token.isBlank() || !token.startsWith("cm_user_")) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "用户 key 格式无效。");
    }
    return token;
  }

  private static String generateUserKey() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "cm_user_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String preview(String key) {
    if (key == null || key.length() <= 16) {
      return key;
    }
    return key.substring(0, 12) + "..." + key.substring(key.length() - 4);
  }

  private static boolean blank(String value) {
    return trim(value).isBlank();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
