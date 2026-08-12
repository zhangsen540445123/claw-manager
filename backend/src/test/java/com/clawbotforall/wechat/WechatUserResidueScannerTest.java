package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatUserResidueScannerTest {
  @Mock InstanceAggregateMapper aggregateMapper;
  @Mock UserAgentIdentityMapper identityMapper;
  @Mock MiniappUserBindingMapper miniappBindingMapper;
  @Mock WechatUserCleanupOperationMapper cleanupOperationMapper;
  @Mock WechatRebindOperationMapper rebindOperationMapper;
  @Mock WechatBindLinkMapper bindLinkMapper;
  @Mock WechatAccountSyncService accountSyncService;
  @Mock WechatUserCleanupService cleanupService;
  @Mock OpenClawUserDataCleaner dataCleaner;
  @Mock InstanceFileService fileService;
  @TempDir Path temp;

  @Test
  void resumesInterruptedOperationsBeforeScanningResiduesWithoutRetryingFailedOnes() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), "{}");
    WechatUserCleanupOperationEntity interrupted = new WechatUserCleanupOperationEntity();
    interrupted.setOperationId("cleanup-interrupted");
    interrupted.setStatus("cleaning");
    WechatUserCleanupOperationEntity failed = new WechatUserCleanupOperationEntity();
    failed.setOperationId("cleanup-failed");
    failed.setStatus("cleanup_failed");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(identityMapper.listAll()).thenReturn(List.of());
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of(interrupted, failed));
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(cleanupService.resume("cleanup-interrupted")).thenReturn(interrupted);

    scanner().scanInstance(instance);

    verify(cleanupService).resume("cleanup-interrupted");
    verify(cleanupService, never()).retry("cleanup-failed");
    verify(cleanupService, never()).resume("cleanup-failed");
  }

  @Test
  void startsAttributedCleanupWhenPairedAccountExistsButIdentityIsMissing() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]},
          "bindings": [{
            "agentId": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "match": {"channel": "openclaw-weixin", "accountId": "account-1",
              "peer": {"kind": "direct", "id": "wechat-user"}}
          }]
        }
        """);
    com.clawbotforall.instance.WechatPairedAccountEntity account =
        new com.clawbotforall.instance.WechatPairedAccountEntity();
    account.setInstanceId("inst-1");
    account.setAccountId("account-1");
    account.setWechatUserId("wechat-user");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of(account));
    when(identityMapper.listAll()).thenReturn(List.of());
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        .thenReturn(List.of());
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId("cleanup-identity-missing");
    when(cleanupService.startResidue(eq(instance), any(), eq("residue_scanner"))).thenReturn(operation);

    scanner().scanInstance(instance);

    ArgumentCaptor<WechatUserResidueEvidence> evidence = ArgumentCaptor.forClass(WechatUserResidueEvidence.class);
    verify(cleanupService).startResidue(eq(instance), evidence.capture(), eq("residue_scanner"));
    assertThat(evidence.getValue().accountId()).isEqualTo("account-1");
    assertThat(evidence.getValue().wechatUserId()).isEqualTo("wechat-user");
    assertThat(evidence.getValue().agentId()).isEqualTo("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThat(evidence.getValue().evidenceTypes()).contains("binding_agent_peer");
  }

  @Test
  void startsCleanupForIdentityAndMatchingWechatBindingWhenPairedAccountIsGone() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]},
          "bindings": [{
            "agentId": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "match": {"channel": "openclaw-weixin", "accountId": "ghost-account",
              "peer": {"kind": "direct", "id": "wechat-user"}}
          }]
        }
        """);
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    identity.setWechatUserId("wechat-user");
    identity.setOpenvikingUserId("wx-memory");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(identityMapper.listAll()).thenReturn(List.of(identity));
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of("session-1"));
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId("cleanup-1");
    when(cleanupService.startResidue(eq(instance), any(), eq("residue_scanner"))).thenReturn(operation);

    WechatUserResidueScanner.ScanResult result = scanner().scanInstance(instance);

    ArgumentCaptor<WechatUserResidueEvidence> evidence = ArgumentCaptor.forClass(WechatUserResidueEvidence.class);
    verify(cleanupService).startResidue(eq(instance), evidence.capture(), eq("residue_scanner"));
    assertThat(evidence.getValue().accountId()).isEqualTo("ghost-account");
    assertThat(evidence.getValue().wechatUserId()).isEqualTo("wechat-user");
    assertThat(evidence.getValue().evidenceTypes()).contains("identity_wechat_binding");
    assertThat(result.operationIds()).containsExactly("cleanup-1");
    verify(accountSyncService).syncInstanceAccounts(instance);
  }

  @Test
  void protectsCurrentAgentWhenCleaningOldDuplicateBindingForSameWechatPeer() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    String currentAgent = "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String oldAgent = "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "%s"}, {"id": "%s"}]},
          "bindings": [
            {
              "agentId": "%s",
              "match": {"channel": "openclaw-weixin", "accountId": "current-account",
                "peer": {"kind": "direct", "id": "wechat-peer"}}
            },
            {
              "agentId": "%s",
              "match": {"channel": "openclaw-weixin", "accountId": "old-account",
                "peer": {"kind": "direct", "id": "wechat-peer"}}
            }
          ]
        }
        """.formatted(currentAgent, oldAgent, currentAgent, oldAgent));
    com.clawbotforall.instance.WechatPairedAccountEntity account =
        new com.clawbotforall.instance.WechatPairedAccountEntity();
    account.setInstanceId("inst-1");
    account.setAccountId("current-account");
    account.setWechatUserId("wechat-peer");
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId(currentAgent);
    identity.setWechatUserId("wechat-peer");
    identity.setOpenvikingUserId("wx-memory");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of(account));
    when(aggregateMapper.listAllWechatAccounts()).thenReturn(List.of(account));
    when(identityMapper.listAll()).thenReturn(List.of(identity));
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", oldAgent)).thenReturn(List.of());
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId("cleanup-old-duplicate");
    when(cleanupService.startResidue(eq(instance), any(), eq("residue_scanner"))).thenReturn(operation);

    scanner().scanInstance(instance);

    ArgumentCaptor<WechatUserResidueEvidence> evidence = ArgumentCaptor.forClass(WechatUserResidueEvidence.class);
    verify(cleanupService).startResidue(eq(instance), evidence.capture(), eq("residue_scanner"));
    assertThat(evidence.getValue().agentId()).isEqualTo(oldAgent);
    assertThat(evidence.getValue().protectedAgentIds()).containsExactly(currentAgent);
  }

  @Test
  void startsCleanupForOrphanIdentityWhoseAgentStillExistsInInstanceConfig() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]},
          "bindings": []
        }
        """);
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    identity.setWechatUserId("wechat-user");
    identity.setOpenvikingUserId("wx-memory");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(identityMapper.listAll()).thenReturn(List.of(identity));
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of("session-1"));
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId("cleanup-orphan-identity");
    when(cleanupService.startResidue(eq(instance), any(), eq("residue_scanner"))).thenReturn(operation);

    WechatUserResidueScanner.ScanResult result = scanner().scanInstance(instance);

    ArgumentCaptor<WechatUserResidueEvidence> evidence = ArgumentCaptor.forClass(WechatUserResidueEvidence.class);
    verify(cleanupService).startResidue(eq(instance), evidence.capture(), eq("residue_scanner"));
    assertThat(evidence.getValue().agentId()).isEqualTo(identity.getAgentId());
    assertThat(evidence.getValue().wechatUserId()).isEqualTo("wechat-user");
    assertThat(evidence.getValue().openvikingUserId()).isEqualTo("wx-memory");
    assertThat(evidence.getValue().sessionIds()).containsExactly("session-1");
    assertThat(evidence.getValue().evidenceTypes()).contains("identity_agent_config");
    assertThat(result.operationIds()).containsExactly("cleanup-orphan-identity");
  }

  @Test
  void doesNotCleanIdentityThatStillHasPairedAccountInAnotherInstance() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]},
          "bindings": []
        }
        """);
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    identity.setWechatUserId("wechat-user");
    identity.setOpenvikingUserId("wx-memory");
    com.clawbotforall.instance.WechatPairedAccountEntity otherInstanceAccount =
        new com.clawbotforall.instance.WechatPairedAccountEntity();
    otherInstanceAccount.setInstanceId("inst-2");
    otherInstanceAccount.setAccountId("account-2");
    otherInstanceAccount.setWechatUserId("wechat-user");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(aggregateMapper.listAllWechatAccounts()).thenReturn(List.of(otherInstanceAccount));
    when(identityMapper.listAll()).thenReturn(List.of(identity));
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());

    WechatUserResidueScanner.ScanResult result = scanner().scanInstance(instance);

    verify(cleanupService, never()).startResidue(any(), any(), any());
    assertThat(result.operationIds()).isEmpty();
  }

  @Test
  void doesNotCleanCrossInstanceActiveAgentFromStaleLocalBinding() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]},
          "bindings": [{
            "agentId": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "match": {"channel": "openclaw-weixin", "accountId": "stale-account",
              "peer": {"kind": "direct", "id": "wechat-user"}}
          }]
        }
        """);
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    identity.setWechatUserId("wechat-user");
    identity.setOpenvikingUserId("wx-memory");
    com.clawbotforall.instance.WechatPairedAccountEntity otherInstanceAccount =
        new com.clawbotforall.instance.WechatPairedAccountEntity();
    otherInstanceAccount.setInstanceId("inst-2");
    otherInstanceAccount.setAccountId("account-2");
    otherInstanceAccount.setWechatUserId("wechat-user");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(aggregateMapper.listAllWechatAccounts()).thenReturn(List.of(otherInstanceAccount));
    when(identityMapper.listAll()).thenReturn(List.of(identity));
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());

    WechatUserResidueScanner.ScanResult result = scanner().scanInstance(instance);

    verify(cleanupService, never()).startResidue(any(), any(), any());
    assertThat(result.operationIds()).isEmpty();
  }

  @Test
  void doesNotCreateResidueForBindingWhoseAccountIsProtectedByActiveWorkflow() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]},
          "bindings": [{
            "agentId": "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "match": {"channel": "openclaw-weixin", "accountId": "protected-account",
              "peer": {"kind": "direct", "id": "wechat-user"}}
          }]
        }
        """);
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    identity.setWechatUserId("wechat-user");
    identity.setOpenvikingUserId("wx-memory");
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(aggregateMapper.listAllWechatAccounts()).thenReturn(List.of());
    when(identityMapper.listAll()).thenReturn(List.of(identity));
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(bindLinkMapper.listProtectedAccountIds("inst-1")).thenReturn(List.of("protected-account"));

    WechatUserResidueScanner.ScanResult result = scanner().scanInstance(instance);

    verify(cleanupService, never()).startResidue(any(), any(), any());
    assertThat(result.operationIds()).isEmpty();
  }

  @Test
  void leavesUnattributedBareAgentDirectoryUntouchedAndReportsWarning() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), "{}");
    Path bare = paths.homeDir().resolve(".openclaw/agents/user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    Files.createDirectories(bare);
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(identityMapper.listAll()).thenReturn(List.of());
    when(miniappBindingMapper.listByInstanceId("inst-1")).thenReturn(List.of());
    when(cleanupOperationMapper.listByInstance("inst-1")).thenReturn(List.of());
    when(rebindOperationMapper.listByInstance("inst-1")).thenReturn(List.of());

    WechatUserResidueScanner.ScanResult result = scanner().scanInstance(instance);

    verify(cleanupService, never()).startResidue(any(), any(), any());
    assertThat(result.warnings()).allMatch(value -> !value.contains("user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
    assertThat(result.warnings()).anyMatch(value -> value.startsWith("unattributed_agent_directory:"));
    assertThat(Files.exists(bare)).isTrue();
  }

  private WechatUserResidueScanner scanner() {
    return new WechatUserResidueScanner(aggregateMapper, identityMapper, miniappBindingMapper,
        cleanupOperationMapper, rebindOperationMapper, bindLinkMapper, accountSyncService, cleanupService,
        dataCleaner, fileService, new ObjectMapper());
  }

  private InstancePaths paths() throws Exception {
    Path home = temp.resolve("home");
    Files.createDirectories(home);
    return new InstancePaths(temp, home, temp.resolve("workspace"), temp.resolve("logs"));
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst-1");
    instance.setStatus("running");
    return instance;
  }
}

