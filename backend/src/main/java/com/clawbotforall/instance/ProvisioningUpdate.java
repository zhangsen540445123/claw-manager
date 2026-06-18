package com.clawbotforall.instance;

/**
 * 实例创建进度变更返回的实例和进度状态组合。
 */
public record ProvisioningUpdate(
    InstanceEntity instance,
    InstanceProvisioningEntity provisioning
) {}
