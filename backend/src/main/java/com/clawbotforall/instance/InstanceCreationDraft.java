package com.clawbotforall.instance;

/**
 * 新实例持久化前创建的聚合草稿。
 */
public record InstanceCreationDraft(
    InstanceEntity instance,
    InstanceModelEntity model,
    InstanceProvisioningEntity provisioning,
    InstanceModelAuthEntity modelAuth
) {}
