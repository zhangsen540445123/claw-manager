package com.clawbotforall.instance;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InstanceDeleteOperationMapper {
  InstanceDeleteOperationEntity findById(@Param("operationId") String operationId);
  InstanceDeleteOperationEntity findByIdForUpdate(@Param("operationId") String operationId);
  InstanceDeleteOperationEntity findActiveByInstanceForUpdate(@Param("instanceId") String instanceId);
  List<InstanceDeleteOperationEntity> listActive();
  int insert(InstanceDeleteOperationEntity operation);
  int update(InstanceDeleteOperationEntity operation);
}
