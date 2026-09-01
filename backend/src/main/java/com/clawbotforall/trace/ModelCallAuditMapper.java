package com.clawbotforall.trace;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ModelCallAuditMapper {
  int insert(ModelCallAudit audit);
  List<ModelCallAudit> findForTrace(@Param("instanceId") String instanceId,
      @Param("sessionKeyHash") String sessionKeyHash, @Param("from") String from, @Param("to") String to);
  int deleteBefore(@Param("cutoff") String cutoff);
  int deleteByInstanceId(@Param("instanceId") String instanceId);
}
