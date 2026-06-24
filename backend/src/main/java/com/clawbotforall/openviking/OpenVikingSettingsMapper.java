package com.clawbotforall.openviking;

import org.apache.ibatis.annotations.Mapper;

/**
 * OpenViking 全局配置持久化。
 */
@Mapper
public interface OpenVikingSettingsMapper {

  OpenVikingSettingsEntity findGlobal();

  int upsert(OpenVikingSettingsEntity settings);
}
