package com.clawbotforall.externalapi;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExternalApiSettingsMapper {
  ExternalApiSettingsEntity find();

  int upsert(ExternalApiSettingsEntity settings);
}
