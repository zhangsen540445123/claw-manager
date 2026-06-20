package com.clawbotforall.wechat;

import java.util.List;

public record WechatPluginVersions(
    String latest,
    List<String> versions
) {}
