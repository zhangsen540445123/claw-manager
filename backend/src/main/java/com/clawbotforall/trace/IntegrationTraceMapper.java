package com.clawbotforall.trace;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IntegrationTraceMapper {
  int insert(IntegrationTraceEvent event);
  List<IntegrationTraceEvent> listEvents(@Param("instanceId") String instanceId, @Param("channel") String channel,
      @Param("status") String status, @Param("stage") String stage, @Param("from") String from,
      @Param("to") String to, @Param("limit") int limit, @Param("offset") int offset);
  List<IntegrationTraceEvent> findByTraceId(String traceId);
  int deleteBefore(String cutoff);
  int deleteByInstanceId(@Param("instanceId") String instanceId);
  int deleteByIdentityEvidence(
      @Param("instanceId") String instanceId,
      @Param("senderHashes") List<String> senderHashes,
      @Param("sessionKeyHashes") List<String> sessionKeyHashes
  );
}
