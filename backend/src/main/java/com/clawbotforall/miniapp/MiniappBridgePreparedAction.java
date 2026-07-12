package com.clawbotforall.miniapp;

import java.util.Map;
import org.springframework.http.HttpMethod;

record MiniappBridgePreparedAction(
    String domain,
    String operation,
    String actionKey,
    HttpMethod method,
    String path,
    Map<String, Object> query,
    Map<String, Object> body
) {}
