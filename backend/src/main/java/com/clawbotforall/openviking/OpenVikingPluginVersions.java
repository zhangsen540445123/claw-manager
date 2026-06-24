package com.clawbotforall.openviking;

import java.util.List;

public record OpenVikingPluginVersions(
    String latest,
    List<String> versions
) {}
