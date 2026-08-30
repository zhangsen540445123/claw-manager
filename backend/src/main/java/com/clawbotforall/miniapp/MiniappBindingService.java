package com.clawbotforall.miniapp;

import com.clawbotforall.externalapi.ExternalApiIdentity;
import com.clawbotforall.externalapi.ExternalApiIdentityService;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.wechat.PublicWechatBindLink;
import com.clawbotforall.wechat.WechatBindLinkEntity;
import com.clawbotforall.wechat.WechatBindLinkMapper;
import com.clawbotforall.wechat.WechatBindLinkService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MiniappBindingService {
  private final MiniappUserBindingMapper bindingMapper;
  private final MiniappInstanceService instanceService;
  private final WechatBindLinkService wechatBindLinkService;
  private final WechatBindLinkMapper wechatBindLinkMapper;
  private final InstanceAggregateMapper instanceMapper;
  private final OpenVikingSettingsService openVikingSettingsService;
  private final ExternalApiIdentityService identityService;
  private final MiniappUserAccessService userAccessService;
  private final Clock clock;

  @Autowired
  public MiniappBindingService(
      MiniappUserBindingMapper bindingMapper,
      MiniappInstanceService instanceService,
      WechatBindLinkService wechatBindLinkService,
      WechatBindLinkMapper wechatBindLinkMapper,
      InstanceAggregateMapper instanceMapper,
      OpenVikingSettingsService openVikingSettingsService,
      ExternalApiIdentityService identityService,
      MiniappUserAccessService userAccessService
  ) {
    this(bindingMapper, instanceService, wechatBindLinkService, wechatBindLinkMapper, instanceMapper,
        openVikingSettingsService, identityService, userAccessService, Clock.systemUTC());
  }

  MiniappBindingService(
      MiniappUserBindingMapper bindingMapper,
      MiniappInstanceService instanceService,
      WechatBindLinkService wechatBindLinkService,
      WechatBindLinkMapper wechatBindLinkMapper,
      InstanceAggregateMapper instanceMapper,
      OpenVikingSettingsService openVikingSettingsService,
      ExternalApiIdentityService identityService,
      MiniappUserAccessService userAccessService,
      Clock clock
  ) {
    this.bindingMapper = bindingMapper;
    this.instanceService = instanceService;
    this.wechatBindLinkService = wechatBindLinkService;
    this.wechatBindLinkMapper = wechatBindLinkMapper;
    this.instanceMapper = instanceMapper;
    this.openVikingSettingsService = openVikingSettingsService;
    this.identityService = identityService;
    this.userAccessService = userAccessService;
    this.clock = clock;
  }

  @Transactional
  public MiniappBindLinkResult createWechatBindLink(String openid, String origin) {
    ExternalApiIdentity identity = resolveOpenid(openid);
    MiniappUserBindingEntity binding = bindingMapper.findByOpenidHashForUpdate(identity.openidHash());
    if (binding == null) {
      InstanceEntity selected = instanceService.selectLeastLoadedInstance();
      String now = clock.instant().toString();
      binding = new MiniappUserBindingEntity();
      binding.setOpenidHash(identity.openidHash());
      binding.setOpenid(identity.openid());
      binding.setInstanceId(selected.getId());
      binding.setBindStatus("pending");
      binding.setCreatedAt(now);
      binding.setUpdatedAt(now);
      bindingMapper.insert(binding);
    }
    String targetAccountId = "";
    if (!blank(binding.getWechatUserId())) {
      WechatPairedAccountEntity account = instanceMapper.findWechatAccountByWechatUserId(binding.getWechatUserId());
      if (account == null) {
        throw new ApiException(HttpStatus.CONFLICT, "该小程序用户的微信绑定账号不存在，请先处理绑定数据。");
      }
      targetAccountId = account.getAccountId();
    }
    PublicWechatBindLink link = wechatBindLinkService.createMiniappLink(
        identity.openidHash(),
        binding.getInstanceId(),
        targetAccountId,
        origin
    );
    String updatedAt = clock.instant().toString();
    if (hasCompletePersistedIdentity(binding)) {
      bindingMapper.updateBindTokenPreservingStatus(identity.openidHash(), link.token(), updatedAt);
    } else {
      bindingMapper.updateBindToken(identity.openidHash(), link.token(), updatedAt);
      binding.setBindStatus("waiting_scan");
    }
    binding.setCurrentBindToken(link.token());
    binding.setUpdatedAt(updatedAt);
    return result(identity.openid(), binding, link);
  }

  @Transactional
  public MiniappBindLinkResult getBindLink(String token, String origin) {
    WechatBindLinkEntity link = wechatBindLinkMapper.findByToken(trim(token));
    if (link == null || blank(link.getMiniappOpenidHash())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "小程序微信绑定链接不存在。");
    }
    MiniappUserBindingEntity binding = userAccessService.reconcileBinding(link.getMiniappOpenidHash());
    PublicWechatBindLink publicLink = wechatBindLinkService.getPublicStatus(token, origin);
    binding = userAccessService.reconcileBinding(link.getMiniappOpenidHash());
    if (binding == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "小程序微信绑定关系不存在。");
    }
    return result(binding.getOpenid(), binding, publicLink);
  }

  private MiniappBindLinkResult result(String openid, MiniappUserBindingEntity binding, PublicWechatBindLink link) {
    String status = link == null ? binding.getBindStatus() : link.status();
    boolean connected = "connected".equals(binding.getBindStatus())
        && !blank(binding.getOpenvikingUserId())
        && !blank(binding.getAgentId());
    return new MiniappBindLinkResult(
        openid,
        link == null ? binding.getCurrentBindToken() : link.token(),
        status,
        binding.getInstanceId(),
        binding.getOpenvikingUserId(),
        connected,
        link
    );
  }

  private ExternalApiIdentity resolveOpenid(String openid) {
    return identityService.resolve(openid, openVikingSettingsService.effectiveSettings().identityHashSecret());
  }

  private static boolean hasCompletePersistedIdentity(MiniappUserBindingEntity binding) {
    return binding != null
        && !blank(binding.getWechatUserId())
        && !blank(binding.getAgentId())
        && !blank(binding.getOpenvikingUserId());
  }

  private static boolean blank(String value) {
    return trim(value).isBlank();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
