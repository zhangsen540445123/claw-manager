package com.clawbotforall.image;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ImageGenerationSettingsMapper {
  ImageGenerationSettings find();
  int upsert(ImageGenerationSettings settings);
}
