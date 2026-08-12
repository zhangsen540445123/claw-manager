package com.clawbotforall.wechat;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 微信扫码绑定链接持久化操作的 MyBatis Mapper。
 */
@Mapper
public interface WechatBindLinkMapper {

  WechatBindLinkEntity findByToken(@Param("token") String token);

  WechatBindLinkEntity findByTokenForUpdate(@Param("token") String token);

  List<WechatBindLinkEntity> listAdminLinks(
      @Param("mode") String mode,
      @Param("status") String status,
      @Param("phone") String phone,
      @Param("now") String now,
      @Param("offset") int offset,
      @Param("pageSize") int pageSize
  );

  int countAdminLinks(
      @Param("mode") String mode,
      @Param("status") String status,
      @Param("phone") String phone,
      @Param("now") String now
  );

  List<String> listProtectedAccountIds(@Param("instanceId") String instanceId);

  WechatBindLinkEntity findActiveForUserForUpdate(
      @Param("instanceId") String instanceId,
      @Param("phone") String phone,
      @Param("accountId") String accountId,
      @Param("wechatUserId") String wechatUserId
  );

  int insert(WechatBindLinkEntity link);

  int update(WechatBindLinkEntity link);

  int redactByPhoneOrAccountId(
      @Param("phone") String phone,
      @Param("accountId") String accountId,
      @Param("updatedAt") String updatedAt
  );

  int redactByInstanceId(
      @Param("instanceId") String instanceId,
      @Param("updatedAt") String updatedAt
  );
}
