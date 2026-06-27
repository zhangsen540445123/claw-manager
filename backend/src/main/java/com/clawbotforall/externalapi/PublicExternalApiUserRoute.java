package com.clawbotforall.externalapi;

public record PublicExternalApiUserRoute(
    String openid,
    String openidHash,
    String openvikingUserId,
    String instanceId,
    String createdAt,
    String updatedAt,
    String lastUsedAt
) {
  public static PublicExternalApiUserRoute from(ExternalApiUserRouteEntity route) {
    return new PublicExternalApiUserRoute(
        route.getOpenid(),
        route.getOpenidHash(),
        route.getOpenvikingUserId(),
        route.getInstanceId(),
        route.getCreatedAt(),
        route.getUpdatedAt(),
        route.getLastUsedAt()
    );
  }
}
