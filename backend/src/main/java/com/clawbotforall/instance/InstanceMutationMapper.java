package com.clawbotforall.instance;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 实例聚合写入操作的 MyBatis Mapper。
 */
@Mapper
public interface InstanceMutationMapper {

  int countAll();

  List<Integer> listUsedPorts();

  int insertInstance(InstanceEntity instance);

  int insertModel(InstanceModelEntity model);

  int deleteModelsForInstance(@Param("instanceId") String instanceId);

  int insertProvisioning(InstanceProvisioningEntity provisioning);

  int insertModelAuth(InstanceModelAuthEntity modelAuth);

  int updateModelAuth(InstanceModelAuthEntity modelAuth);

  int deleteWechatAccountsForInstance(@Param("instanceId") String instanceId);

  int deleteInstance(@Param("instanceId") String instanceId);

  int insertWechatAccount(WechatPairedAccountEntity account);

  int ensureWechatAccountChannel(WechatAccountChannelEntity channel);

  int upsertWechatAccountChannel(WechatAccountChannelEntity channel);

  int updateWechatAccountRemark(
      @Param("instanceId") String instanceId,
      @Param("accountId") String accountId,
      @Param("remark") String remark,
      @Param("updatedAt") String updatedAt
  );

  int updateWechatAccountProfile(
      @Param("instanceId") String instanceId,
      @Param("accountId") String accountId,
      @Param("phone") String phone,
      @Param("remark") String remark,
      @Param("updatedAt") String updatedAt
  );

  int updateWechatAccountMetadata(WechatPairedAccountEntity account);

  int deleteWechatAccount(
      @Param("instanceId") String instanceId,
      @Param("accountId") String accountId
  );

  int updateProvisioning(InstanceProvisioningEntity provisioning);

  int updateInstanceStatus(
      @Param("id") String id,
      @Param("status") String status,
      @Param("updatedAt") String updatedAt
  );

  int updateInstancePlugins(
      @Param("id") String id,
      @Param("pluginsAllow") String pluginsAllow,
      @Param("pluginsEntries") String pluginsEntries,
      @Param("updatedAt") String updatedAt
  );
}
