package com.clawbotforall.openviking;

public interface OpenVikingAdminClient {

  String registerUser(String baseUrl, String rootApiKey, String accountId, String openvikingUserId);

  String regenerateUserKey(String baseUrl, String rootApiKey, String accountId, String openvikingUserId);
}
