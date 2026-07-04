package com.clawbotforall.web;

import java.util.UUID;

public final class ExternalRequestIds {
  public static final String HEADER = "X-CM-Request-Id";

  private ExternalRequestIds() {}

  public static String create() {
    return "cmreq_" + UUID.randomUUID().toString().replace("-", "");
  }
}
