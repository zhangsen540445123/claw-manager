package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.miniapp.MiniappUserBindingEntity;
import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Cross-checks database and OpenClaw state, then cleans only strongly attributable historical residues. */
@Service
public class WechatUserResidueScanner {
  private static final Logger log = LoggerFactory.getLogger(WechatUserResidueScanner.class);
  private static final Pattern AGENT_ID = Pattern.compile("user_[0-9a-f]{32}");
  private static final Set<String> ACTIVE_REBIND_STATUSES = Set.of(
      "pending", "cleaning", "provisioning", "cleanup_failed");
  private static final Set<String> ACTIVE_CLEANUP_STATUSES = Set.of(
      "pending", "cleaning", "cleanup_failed");

  private final InstanceAggregateMapper aggregateMapper;
  private final UserAgentIdentityMapper identityMapper;
  private final MiniappUserBindingMapper miniappBindingMapper;
  private final WechatUserCleanupOperationMapper cleanupOperationMapper;
  private final WechatRebindOperationMapper rebindOperationMapper;
  private final WechatBindLinkMapper bindLinkMapper;
  private final WechatAccountSyncService accountSyncService;
  private final WechatUserCleanupService cleanupService;
  private final OpenClawUserDataCleaner dataCleaner;
  private final InstanceFileService fileService;
  private final ObjectMapper objectMapper;
  private final Map<String, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();

  public WechatUserResidueScanner(
      InstanceAggregateMapper aggregateMapper,
      UserAgentIdentityMapper identityMapper,
      MiniappUserBindingMapper miniappBindingMapper,
      WechatUserCleanupOperationMapper cleanupOperationMapper,
      WechatRebindOperationMapper rebindOperationMapper,
      WechatBindLinkMapper bindLinkMapper,
      WechatAccountSyncService accountSyncService,
      WechatUserCleanupService cleanupService,
      OpenClawUserDataCleaner dataCleaner,
      InstanceFileService fileService,
      ObjectMapper objectMapper
  ) {
    this.aggregateMapper = aggregateMapper;
    this.identityMapper = identityMapper;
    this.miniappBindingMapper = miniappBindingMapper;
    this.cleanupOperationMapper = cleanupOperationMapper;
    this.rebindOperationMapper = rebindOperationMapper;
    this.bindLinkMapper = bindLinkMapper;
    this.accountSyncService = accountSyncService;
    this.cleanupService = cleanupService;
    this.dataCleaner = dataCleaner;
    this.fileService = fileService;
    this.objectMapper = objectMapper;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void scanAfterStartup() {
    scanAllInstances();
  }

  @Scheduled(fixedDelayString = "${clawbot.wechat.residue-scan-delay-ms:300000}")
  public void scanPeriodically() {
    scanAllInstances();
  }

  public void scanAllInstances() {
    for (InstanceEntity instance : safe(aggregateMapper.listAll())) {
      try {
        scanInstance(instance);
      } catch (RuntimeException error) {
        log.warn("用户残留扫描失败：instanceId={}, errorType={}", instance.getId(), error.getClass().getSimpleName());
        log.debug("用户残留扫描异常详情：instanceId={}", instance.getId(), error);
      }
    }
  }

  public ScanResult scanInstance(InstanceEntity instance) {
    if (instance == null || text(instance.getId()).isBlank()) {
      return new ScanResult(List.of(), List.of("instance_missing"));
    }
    ReentrantLock lock = instanceLocks.computeIfAbsent(instance.getId(), ignored -> new ReentrantLock());
    if (!lock.tryLock()) {
      return new ScanResult(List.of(), List.of("scan_already_running"));
    }
    try {
      resumeInterruptedOperations(instance.getId());
      accountSyncService.syncInstanceAccounts(instance);
      return scanLocked(instance);
    } finally {
      lock.unlock();
    }
  }

  private void resumeInterruptedOperations(String instanceId) {
    for (WechatUserCleanupOperationEntity operation : safe(cleanupOperationMapper.listByInstance(instanceId))) {
      if (!Set.of("pending", "cleaning").contains(text(operation.getStatus()))
          || text(operation.getOperationId()).isBlank()) {
        continue;
      }
      try {
        cleanupService.resume(operation.getOperationId());
      } catch (RuntimeException error) {
        log.warn("恢复中断用户清理失败：instanceId={}, operationHash={}, errorType={}",
            instanceId, hashPreview(operation.getOperationId()), error.getClass().getSimpleName());
        log.debug("恢复中断用户清理异常详情：instanceId={}, operationHash={}",
            instanceId, hashPreview(operation.getOperationId()), error);
      }
    }
  }

  private ScanResult scanLocked(InstanceEntity instance) {
    String instanceId = instance.getId();
    InstancePaths paths = fileService.paths(instanceId);
    OpenClawSnapshot snapshot = readSnapshot(paths.homeDir().resolve("openclaw.json"));
    List<WechatPairedAccountEntity> accounts = safe(
        aggregateMapper.listWechatAccountsByInstanceIds(List.of(instanceId)));
    List<WechatPairedAccountEntity> allAccounts = safe(aggregateMapper.listAllWechatAccounts());
    List<UserAgentIdentityEntity> identities = safe(identityMapper.listAll());
    List<MiniappUserBindingEntity> miniapps = safe(miniappBindingMapper.listByInstanceId(instanceId));
    List<WechatUserCleanupOperationEntity> cleanups = safe(cleanupOperationMapper.listByInstance(instanceId));
    List<WechatRebindOperationEntity> rebinds = safe(rebindOperationMapper.listByInstance(instanceId));
    List<String> protectedAccountIds = safe(bindLinkMapper.listProtectedAccountIds(instanceId));

    Map<String, WechatPairedAccountEntity> accountsById = new HashMap<>();
    Map<String, WechatPairedAccountEntity> accountsByPeer = new HashMap<>();
    for (WechatPairedAccountEntity account : accounts) {
      if (!text(account.getAccountId()).isBlank()) accountsById.put(account.getAccountId(), account);
      if (!text(account.getWechatUserId()).isBlank()) accountsByPeer.put(account.getWechatUserId(), account);
    }
    Set<String> globallyPairedPeers = new HashSet<>();
    for (WechatPairedAccountEntity account : allAccounts) {
      if (!text(account.getWechatUserId()).isBlank()) globallyPairedPeers.add(account.getWechatUserId());
    }
    Map<String, UserAgentIdentityEntity> identitiesByAgent = new HashMap<>();
    Map<String, UserAgentIdentityEntity> identitiesByPeer = new HashMap<>();
    for (UserAgentIdentityEntity identity : identities) {
      if (validAgent(identity.getAgentId())) identitiesByAgent.put(identity.getAgentId(), identity);
      if (!text(identity.getWechatUserId()).isBlank()) identitiesByPeer.put(identity.getWechatUserId(), identity);
    }
    Map<String, List<MiniappUserBindingEntity>> miniappsByAgent = new HashMap<>();
    for (MiniappUserBindingEntity miniapp : miniapps) {
      if (validAgent(miniapp.getAgentId())) {
        miniappsByAgent.computeIfAbsent(miniapp.getAgentId(), ignored -> new ArrayList<>()).add(miniapp);
      }
    }

    Set<String> protectedAgents = protectedAgents(cleanups, rebinds);
    for (UserAgentIdentityEntity identity : identities) {
      if (validAgent(identity.getAgentId()) && globallyPairedPeers.contains(text(identity.getWechatUserId()))) {
        protectedAgents.add(identity.getAgentId());
      }
    }
    Set<String> protectedAccounts = protectedAccounts(rebinds);
    for (String accountId : protectedAccountIds) {
      if (!text(accountId).isBlank()) protectedAccounts.add(text(accountId));
    }
    for (Binding binding : snapshot.bindings()) {
      if ("openclaw-weixin".equals(binding.channel())
          && protectedAccounts.contains(text(binding.accountId()))
          && validAgent(binding.agentId())) {
        protectedAgents.add(binding.agentId());
      }
    }
    Map<String, Candidate> candidates = new LinkedHashMap<>();

    for (UserAgentIdentityEntity identity : identities) {
      if (!validAgent(identity.getAgentId()) || protectedAgents.contains(identity.getAgentId())) continue;
      boolean paired = globallyPairedPeers.contains(text(identity.getWechatUserId()));
      List<Binding> matching = snapshot.bindings().stream()
          .filter(binding -> binding.agentId().equals(identity.getAgentId()))
          .filter(binding -> "openclaw-weixin".equals(binding.channel()))
          .filter(binding -> binding.peerId().equals(text(identity.getWechatUserId())))
          .toList();
      if (!paired && !matching.isEmpty()) {
        Candidate candidate = candidates.computeIfAbsent(identity.getAgentId(), Candidate::new);
        candidate.wechatUserId = text(identity.getWechatUserId());
        candidate.openvikingUserId = text(identity.getOpenvikingUserId());
        candidate.evidenceTypes.add("identity_wechat_binding");
        matching.forEach(binding -> addBindingEvidence(candidate, binding, accountsById, protectedAccounts));
      }
      if (!paired && snapshot.agents().contains(identity.getAgentId())) {
        Candidate candidate = candidates.computeIfAbsent(identity.getAgentId(), Candidate::new);
        candidate.wechatUserId = text(identity.getWechatUserId());
        candidate.openvikingUserId = text(identity.getOpenvikingUserId());
        candidate.evidenceTypes.add("identity_agent_config");
      }
      List<MiniappUserBindingEntity> agentMiniapps = miniappsByAgent.getOrDefault(identity.getAgentId(), List.of());
      if (!paired && agentMiniapps.stream().anyMatch(WechatUserResidueScanner::connectedMiniapp)) {
        Candidate candidate = candidates.computeIfAbsent(identity.getAgentId(), Candidate::new);
        candidate.wechatUserId = text(identity.getWechatUserId());
        candidate.openvikingUserId = text(identity.getOpenvikingUserId());
        candidate.evidenceTypes.add("miniapp_agent_instance");
      }
    }

    for (Binding binding : snapshot.bindings()) {
      if (!"openclaw-weixin".equals(binding.channel()) || !validAgent(binding.agentId())
          || protectedAgents.contains(binding.agentId())) continue;
      WechatPairedAccountEntity pairedById = accountsById.get(binding.accountId());
      WechatPairedAccountEntity pairedByPeer = accountsByPeer.get(binding.peerId());
      WechatPairedAccountEntity paired = pairedById != null ? pairedById : pairedByPeer;
      UserAgentIdentityEntity expected = paired == null ? null : identitiesByPeer.get(text(paired.getWechatUserId()));
      boolean currentValidBinding = paired != null && expected != null
          && binding.agentId().equals(expected.getAgentId());
      if (currentValidBinding) continue;
      Candidate candidate = candidates.computeIfAbsent(binding.agentId(), Candidate::new);
      if (paired != null && expected != null && validAgent(expected.getAgentId())
          && !expected.getAgentId().equals(binding.agentId())) {
        candidate.protectedAgentIds.add(expected.getAgentId());
      }
      if (paired != null && expected == null) {
        candidate.accountId = firstNonBlank(candidate.accountId, paired.getAccountId());
        candidate.wechatUserId = firstNonBlank(candidate.wechatUserId, paired.getWechatUserId());
      } else {
        candidate.wechatUserId = firstNonBlank(candidate.wechatUserId, binding.peerId());
      }
      UserAgentIdentityEntity identity = identitiesByAgent.get(binding.agentId());
      if (identity != null) candidate.openvikingUserId = text(identity.getOpenvikingUserId());
      candidate.evidenceTypes.add("binding_agent_peer");
      addBindingEvidence(candidate, binding, accountsById, protectedAccounts);
    }

    for (MiniappUserBindingEntity miniapp : miniapps) {
      if (!connectedMiniapp(miniapp) || !validAgent(miniapp.getAgentId())
          || protectedAgents.contains(miniapp.getAgentId())) continue;
      if (globallyPairedPeers.contains(text(miniapp.getWechatUserId()))) continue;
      Candidate candidate = candidates.computeIfAbsent(miniapp.getAgentId(), Candidate::new);
      candidate.wechatUserId = firstNonBlank(candidate.wechatUserId, miniapp.getWechatUserId());
      candidate.openvikingUserId = firstNonBlank(candidate.openvikingUserId, miniapp.getOpenvikingUserId());
      candidate.evidenceTypes.add("miniapp_agent_instance");
    }

    for (Candidate candidate : candidates.values()) {
      for (Binding binding : snapshot.bindings()) {
        if (binding.agentId().equals(candidate.agentId) && "claw-manager-api".equals(binding.channel())
            && !text(binding.peerId()).isBlank()) {
          candidate.apiPeerIds.add(binding.peerId());
        }
      }
      for (MiniappUserBindingEntity miniapp : miniappsByAgent.getOrDefault(candidate.agentId, List.of())) {
        if (!text(miniapp.getOpenidHash()).isBlank()) candidate.apiPeerIds.add("api:" + miniapp.getOpenidHash().trim());
      }
      candidate.sessionIds.addAll(dataCleaner.readOldSessionIds(instanceId, candidate.agentId));
    }

    List<String> operationIds = new ArrayList<>();
    Set<String> attributedAgents = new HashSet<>();
    for (Candidate candidate : candidates.values()) {
      attributedAgents.add(candidate.agentId);
      WechatUserCleanupOperationEntity operation = cleanupService.startResidue(instance, candidate.toEvidence(), "residue_scanner");
      if (operation != null && !text(operation.getOperationId()).isBlank()) operationIds.add(operation.getOperationId());
    }

    List<String> warnings = bareDirectoryWarnings(paths, attributedAgents, protectedAgents, accounts, identitiesByPeer);
    return new ScanResult(List.copyOf(operationIds), List.copyOf(warnings));
  }

  private void addBindingEvidence(Candidate candidate, Binding binding,
      Map<String, WechatPairedAccountEntity> accountsById, Set<String> protectedAccounts) {
    if (!text(binding.accountId()).isBlank() && !accountsById.containsKey(binding.accountId())
        && !protectedAccounts.contains(binding.accountId())) {
      candidate.accountId = firstNonBlank(candidate.accountId, binding.accountId());
    }
    candidate.wechatUserId = firstNonBlank(candidate.wechatUserId, binding.peerId());
  }

  private Set<String> protectedAgents(List<WechatUserCleanupOperationEntity> cleanups,
      List<WechatRebindOperationEntity> rebinds) {
    Set<String> result = new HashSet<>();
    for (WechatUserCleanupOperationEntity cleanup : cleanups) {
      if (ACTIVE_CLEANUP_STATUSES.contains(text(cleanup.getStatus())) && validAgent(cleanup.getAgentId())) {
        result.add(cleanup.getAgentId());
      }
    }
    for (WechatRebindOperationEntity rebind : rebinds) {
      if (!ACTIVE_REBIND_STATUSES.contains(text(rebind.getStatus()))) continue;
      if (validAgent(rebind.getOldAgentId())) result.add(rebind.getOldAgentId());
      if (validAgent(rebind.getNewAgentId())) result.add(rebind.getNewAgentId());
    }
    return result;
  }

  private Set<String> protectedAccounts(List<WechatRebindOperationEntity> rebinds) {
    Set<String> result = new HashSet<>();
    for (WechatRebindOperationEntity rebind : rebinds) {
      if (!ACTIVE_REBIND_STATUSES.contains(text(rebind.getStatus()))) continue;
      if (!text(rebind.getOldAccountId()).isBlank()) result.add(rebind.getOldAccountId());
      if (!text(rebind.getNewAccountId()).isBlank()) result.add(rebind.getNewAccountId());
    }
    return result;
  }

  private List<String> bareDirectoryWarnings(InstancePaths paths, Set<String> attributedAgents,
      Set<String> protectedAgents, List<WechatPairedAccountEntity> accounts,
      Map<String, UserAgentIdentityEntity> identitiesByPeer) {
    Set<String> safeAgents = new HashSet<>(attributedAgents);
    safeAgents.addAll(protectedAgents);
    for (WechatPairedAccountEntity account : accounts) {
      UserAgentIdentityEntity identity = identitiesByPeer.get(text(account.getWechatUserId()));
      if (identity != null && validAgent(identity.getAgentId())) safeAgents.add(identity.getAgentId());
    }
    Set<String> directories = new LinkedHashSet<>();
    Path stateRoot = paths.homeDir().resolve(".openclaw");
    collectAgentDirectories(stateRoot.resolve("agents"), "", directories);
    collectAgentDirectories(stateRoot, "workspace-", directories);
    List<String> warnings = new ArrayList<>();
    for (String agentId : directories) {
      if (!safeAgents.contains(agentId)) {
        String agentHash = hashPreview(agentId);
        warnings.add("unattributed_agent_directory:" + agentHash);
        log.warn("发现无法归属的 Agent 目录，仅告警不删除：instanceId={}, agentHash={}",
            paths.baseDir().getFileName(), agentHash);
      }
    }
    return warnings;
  }

  private void collectAgentDirectories(Path parent, String prefix, Set<String> target) {
    if (!Files.isDirectory(parent)) return;
    try (var children = Files.list(parent)) {
      children.filter(Files::isDirectory).forEach(path -> {
        String name = path.getFileName().toString();
        String agentId = prefix.isEmpty() ? name : name.startsWith(prefix) ? name.substring(prefix.length()) : "";
        if (validAgent(agentId)) target.add(agentId);
      });
    } catch (IOException error) {
      throw new IllegalStateException("读取 OpenClaw Agent 目录失败。", error);
    }
  }

  private OpenClawSnapshot readSnapshot(Path configPath) {
    if (!Files.exists(configPath)) return new OpenClawSnapshot(List.of(), Set.of());
    try {
      JsonNode root = objectMapper.readTree(configPath.toFile());
      List<Binding> bindings = new ArrayList<>();
      for (JsonNode node : root.path("bindings")) {
        JsonNode match = node.path("match");
        bindings.add(new Binding(text(node.path("agentId").asText()), text(match.path("channel").asText()),
            text(match.path("accountId").asText()), text(match.path("peer").path("id").asText())));
      }
      Set<String> agents = new LinkedHashSet<>();
      for (JsonNode node : root.path("agents").path("list")) {
        if (validAgent(node.path("id").asText())) agents.add(node.path("id").asText());
      }
      return new OpenClawSnapshot(List.copyOf(bindings), Set.copyOf(agents));
    } catch (IOException error) {
      throw new IllegalStateException("读取 OpenClaw 配置失败。", error);
    }
  }

  private static boolean connectedMiniapp(MiniappUserBindingEntity binding) {
    return "connected".equals(text(binding.getBindStatus()));
  }

  private static boolean validAgent(String value) {
    return AGENT_ID.matcher(text(value)).matches();
  }

  private static String firstNonBlank(String first, String second) {
    return text(first).isBlank() ? text(second) : text(first);
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static String hashPreview(String value) {
    return WechatLogSanitizer.identityHashPreview(text(value));
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  public record ScanResult(List<String> operationIds, List<String> warnings) {}
  private record Binding(String agentId, String channel, String accountId, String peerId) {}
  private record OpenClawSnapshot(List<Binding> bindings, Set<String> agents) {}

  private static final class Candidate {
    private final String agentId;
    private String accountId = "";
    private String wechatUserId = "";
    private String openvikingUserId = "";
    private final Set<String> apiPeerIds = new LinkedHashSet<>();
    private final Set<String> sessionIds = new LinkedHashSet<>();
    private final Set<String> protectedAgentIds = new LinkedHashSet<>();
    private final Set<String> evidenceTypes = new LinkedHashSet<>();

    private Candidate(String agentId) { this.agentId = agentId; }

    private WechatUserResidueEvidence toEvidence() {
      return new WechatUserResidueEvidence(
          emptyToNull(accountId), emptyToNull(wechatUserId), agentId, emptyToNull(openvikingUserId),
          List.copyOf(apiPeerIds), List.copyOf(sessionIds), List.copyOf(protectedAgentIds),
          List.copyOf(evidenceTypes));
    }

    private static String emptyToNull(String value) {
      String normalized = text(value);
      return normalized.isBlank() ? null : normalized;
    }
  }
}
