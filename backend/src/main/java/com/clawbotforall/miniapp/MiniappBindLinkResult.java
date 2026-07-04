package com.clawbotforall.miniapp;

import com.clawbotforall.wechat.PublicWechatBindLink;

public record MiniappBindLinkResult(
    String openid,
    String bindToken,
    String status,
    String instanceId,
    String openVikingUserId,
    boolean canCreateUserKey,
    PublicWechatBindLink link
) {}
