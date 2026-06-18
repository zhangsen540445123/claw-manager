package com.clawbotforall.model;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 模型预设持久化操作的 MyBatis Mapper。
 */
@Mapper
public interface ModelPresetMapper {

  List<ModelPresetEntity> listAll();

  int countAll();

  int countDefault();

  ModelPresetEntity findById(@Param("id") String id);

  ModelPresetEntity findDefaultOrLatest();

  ModelPresetEntity findFirstByCreatedAtDesc();

  int insert(ModelPresetEntity preset);

  int update(ModelPresetEntity preset);

  int clearDefault();

  int setDefault(@Param("id") String id);

  int delete(@Param("id") String id);
}
