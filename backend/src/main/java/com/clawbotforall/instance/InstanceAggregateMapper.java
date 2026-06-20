package com.clawbotforall.instance;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 实例聚合及其子记录读取操作的 MyBatis Mapper。
 */
@Mapper
public interface InstanceAggregateMapper {

  List<InstanceEntity> listAll();

  List<InstanceEntity> listRuntimeActive();

  InstanceEntity findById(@Param("id") String id);

  List<InstanceModelEntity> listModelsByInstanceIds(@Param("instanceIds") List<String> instanceIds);

  List<InstanceProvisioningEntity> listProvisioningByInstanceIds(@Param("instanceIds") List<String> instanceIds);

  List<InstanceModelAuthEntity> listModelAuthByInstanceIds(@Param("instanceIds") List<String> instanceIds);

  List<WechatPairedAccountEntity> listWechatAccountsByInstanceIds(@Param("instanceIds") List<String> instanceIds);

  List<WechatAccountChannelEntity> listWechatAccountChannelsByInstanceIds(@Param("instanceIds") List<String> instanceIds);

  List<WechatPairedAccountEntity> listAllWechatAccounts();

  WechatPairedAccountEntity findWechatAccountByPhone(@Param("phone") String phone);

  List<WechatPairedAccountEntity> searchWechatAccountsByPhoneKeyword(@Param("keyword") String keyword);

  WechatPairedAccountEntity findWechatAccountByAccountId(@Param("accountId") String accountId);

  WechatPairedAccountEntity findWechatAccountByWechatUserId(@Param("wechatUserId") String wechatUserId);

  int countWechatAccountsByInstanceId(@Param("instanceId") String instanceId);
}
