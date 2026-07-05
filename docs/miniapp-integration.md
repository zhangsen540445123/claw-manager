# 小程序接入说明

本文面向微信小程序后端服务。小程序前端只需要拿到二维码和用户 key，所有与 Claw Manager 的服务端鉴权、出码、生成 key 和聊天请求都应由小程序后端完成。

## 接入目标

小程序用户以 `openid` 作为唯一身份。用户先通过 Claw Manager 生成的微信二维码完成扫码绑定，绑定后得到一个 `cm_user_...` 用户 key。之后小程序后端使用这个 key 调用聊天接口，API 通道和微信通道共享同一个 OpenViking `wx_<hash>` 用户记忆。

核心链路：

```text
小程序 openid
  -> 微信扫码绑定
  -> miniapp_user_bindings.openviking_user_id = wx_<hash>
  -> cm_user_... 用户 key
  -> /api/external/openclaw/chat/stream
  -> API Channel handoff
  -> OpenViking wx_<hash> 用户记忆
```

## 前置条件

- 至少一个 OpenClaw 实例处于 `running`，且 `instance_provisioning.status=ready`。
- 目标实例已安装并加载 API Channel、微信插件和 OpenViking 插件。
- 后台“OpenViking预设”已配置 base URL、accountId、身份盐值和 Root API Key。
- 后台“小程序接入”中已创建小程序后端调用方，包含 AK(`app_id`) 和 SK(`app_secret`)。
- 小程序后端能够保存 `cm_user_...` key，并在后续请求中使用它作为用户聊天凭据。

## 鉴权模型

小程序管理类接口使用 HMAC 鉴权。聊天接口使用用户 key 鉴权，不使用旧外部聊天共享凭据。

### HMAC 请求头

| Header | 说明 |
| --- | --- |
| `X-CM-App-Id` | `miniapp_clients.app_id` |
| `X-CM-Timestamp` | 当前毫秒时间戳，允许 5 分钟时钟偏移 |
| `X-CM-Nonce` | 每次请求唯一随机值，5 分钟内不可重复 |
| `X-CM-Signature` | HMAC-SHA256 十六进制签名 |

所有外部接口响应都会带：

| Header | 说明 |
| --- | --- |
| `X-CM-Request-Id` | Claw Manager 为本次外部调用生成的联调 ID。排查问题时把它提供给 Claw Manager 运维，可在 API 日志中定位同一次请求。 |

### 签名串

```text
METHOD
PATH_WITH_QUERY
X-CM-TIMESTAMP
X-CM-NONCE
SHA256(rawBody)
```

- `METHOD` 使用大写，例如 `POST`。
- `PATH_WITH_QUERY` 只包含路径和 query，例如 `/api/external/miniapp/wechat-bind-links`。
- `rawBody` 必须是实际发送的原始 body 字符串，GET 请求使用空字符串。
- HMAC secret 使用 `miniapp_clients.app_secret`。

Java 示例：

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

static String sha256Hex(String text) throws Exception {
  MessageDigest digest = MessageDigest.getInstance("SHA-256");
  return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
}

static String sign(String secret, String method, String pathWithQuery, String timestamp, String nonce, String rawBody) throws Exception {
  String canonical = String.join("\n",
      method.toUpperCase(),
      pathWithQuery,
      timestamp,
      nonce,
      sha256Hex(rawBody));
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
}
```

## 完整接入 Demo

下面示例使用 Java 21 + Spring Boot 3 + fastjson2 编写“小程序后端”接入层。小程序前端只负责触发按钮、展示二维码和发起业务请求；AK/SK、HMAC 签名、`cm_user_...` key 的保存和聊天转发都应放在小程序后端。

### 第 1 步：后台创建小程序 AK/SK

1. 管理员登录 Claw Manager 后台。
2. 进入“小程序接入”。
3. 点击“新增 AK”，填写一个稳定的 AK，例如 `miniapp_main`。
4. 创建后后台只会展示一次完整 SK，请保存到小程序后端密钥配置中。
5. 后续列表只展示 SK preview；如果 SK 泄露，使用“重置 SK”，并同步更新小程序后端配置。

### 第 2 步：准备 Spring Boot 配置

`pom.xml` 使用 Java 21，JSON 使用 fastjson2，SSE 客户端使用 `WebClient`：

```xml
<properties>
  <java.version>21</java.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
  </dependency>
  <dependency>
    <groupId>com.alibaba.fastjson2</groupId>
    <artifactId>fastjson2</artifactId>
    <version>2.0.53</version>
  </dependency>
</dependencies>
```

`application.yml`：

```yaml
cm:
  openclaw:
    base-url: http://127.0.0.1:8080
    app-id: miniapp_main
    app-secret: ${CM_APP_SECRET}
```

启动类启用配置绑定：

```java
package com.example.miniapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniappBackendApplication {
  public static void main(String[] args) {
    SpringApplication.run(MiniappBackendApplication.class, args);
  }
}
```

配置对象：

```java
package com.example.miniapp;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cm.openclaw")
public record ClawManagerProperties(
    URI baseUrl,
    String appId,
    String appSecret
) {}
```

### 第 3 步：实现 HMAC 签名器

```java
package com.example.miniapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ClawManagerHmacSigner {

  public String sign(String secret, String method, String pathWithQuery, String timestamp, String nonce, String rawBody) {
    try {
      String canonical = String.join("\n",
          method.toUpperCase(),
          pathWithQuery,
          timestamp,
          nonce,
          sha256Hex(rawBody));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("Failed to sign Claw Manager request.", error);
    }
  }

  private String sha256Hex(String text) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
  }
}
```

### 第 4 步：封装 Claw Manager Client

```java
package com.example.miniapp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ClawManagerMiniappClient {
  private static final String REQUEST_ID_HEADER = "X-CM-Request-Id";
  private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE =
      new ParameterizedTypeReference<>() {};

  private final ClawManagerProperties properties;
  private final ClawManagerHmacSigner signer;
  private final WebClient webClient;

  public ClawManagerMiniappClient(
      ClawManagerProperties properties,
      ClawManagerHmacSigner signer,
      WebClient.Builder webClientBuilder
  ) {
    this.properties = properties;
    this.signer = signer;
    this.webClient = webClientBuilder.baseUrl(properties.baseUrl().toString()).build();
  }

  private CmResponse<JSONObject> cmFetch(String method, String pathWithQuery, Object bodyObject) {
    String rawBody = bodyObject == null ? "" : JSON.toJSONString(bodyObject);
    String timestamp = String.valueOf(System.currentTimeMillis());
    String nonce = UUID.randomUUID().toString();
    String signature = signer.sign(properties.appSecret(), method, pathWithQuery, timestamp, nonce, rawBody);

    WebClient.RequestBodySpec spec = webClient.method(HttpMethod.valueOf(method))
        .uri(pathWithQuery)
        .accept(MediaType.APPLICATION_JSON)
        .header("X-CM-App-Id", properties.appId())
        .header("X-CM-Timestamp", timestamp)
        .header("X-CM-Nonce", nonce)
        .header("X-CM-Signature", signature);

    WebClient.RequestHeadersSpec<?> request = "GET".equalsIgnoreCase(method)
        ? spec
        : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(rawBody);

    return request.exchangeToMono(response -> {
      String cmRequestId = response.headers().asHttpHeaders().getFirst(REQUEST_ID_HEADER);
      if (response.statusCode().isError()) {
        return response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .flatMap(body -> Mono.<CmResponse<JSONObject>>error(new IllegalStateException(
                "CM request failed status=" + response.statusCode().value()
                    + " cmRequestId=" + cmRequestId
                    + " body=" + body)));
      }
      return response.bodyToMono(String.class)
          .defaultIfEmpty("{}")
          .map(body -> new CmResponse<>(JSON.parseObject(body), cmRequestId == null ? "" : cmRequestId));
    }).block(Duration.ofMinutes(2));
  }
  public Binding createBindLink(String openid) {
    CmResponse<JSONObject> response = cmFetch("POST", "/api/external/miniapp/wechat-bind-links", Map.of("openid", openid));
    return response.body().getObject("binding", Binding.class);
  }

  public Binding getBindLink(String bindToken) {
    CmResponse<JSONObject> response = cmFetch("GET", "/api/external/miniapp/wechat-bind-links/" + bindToken, null);
    return response.body().getObject("binding", Binding.class);
  }

  public UserKey createOrGetUserKey(String openid, boolean reset) {
    CmResponse<JSONObject> response = cmFetch("POST", "/api/external/miniapp/user-keys", Map.of("openid", openid, "reset", reset));
    return response.body().getObject("userKey", UserKey.class);
  }

  public ChatResult chatStream(String userKey, String conversationId, String message) {
    Map<String, Object> body = Map.of(
        "conversationId", conversationId,
        "message", message,
        "metadata", Map.of("source", "miniapp")
    );
    AtomicReference<String> cmRequestIdRef = new AtomicReference<>("");
    AtomicReference<String> sseRequestIdRef = new AtomicReference<>("");
    StringBuilder answer = new StringBuilder();

    webClient.post()
        .uri("/api/external/openclaw/chat/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .headers(headers -> headers.setBearerAuth(userKey.replaceFirst("^Bearer\\s+", "")))
        .bodyValue(JSON.toJSONString(body))
        .exchangeToFlux(response -> {
          String cmRequestId = response.headers().asHttpHeaders().getFirst(REQUEST_ID_HEADER);
          cmRequestIdRef.set(cmRequestId == null ? "" : cmRequestId);
          if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMapMany(errorBody -> Flux.error(new IllegalStateException(
                    "chat failed status=" + response.statusCode().value()
                        + " cmRequestId=" + cmRequestId
                        + " body=" + errorBody)));
          }
          return response.bodyToFlux(STRING_SSE);
        })
        .doOnNext(event -> handleChatEvent(event, sseRequestIdRef, answer))
        .blockLast(Duration.ofMinutes(16));

    return new ChatResult(answer.toString(), cmRequestIdRef.get(), sseRequestIdRef.get());
  }

  private void handleChatEvent(
      ServerSentEvent<String> event,
      AtomicReference<String> sseRequestIdRef,
      StringBuilder answer
  ) {
    String dataText = event.data();
    if (dataText == null || dataText.isBlank()) {
      return;
    }
    JSONObject data = JSON.parseObject(dataText);
    if ("start".equals(event.event())) {
      sseRequestIdRef.set(data.getString("requestId"));
      return;
    }
    if ("delta".equals(event.event())) {
      answer.append(data.getString("text") == null ? "" : data.getString("text"));
      return;
    }
    if ("error".equals(event.event())) {
      throw new IllegalStateException("chat SSE error requestId=" + sseRequestIdRef.get()
          + " message=" + data.getString("message"));
    }
  }

  public record CmResponse<T>(T body, String cmRequestId) {}

  public record Binding(
      String openid,
      String bindToken,
      String status,
      String instanceId,
      String openVikingUserId,
      boolean canCreateUserKey,
      String qrLink,
      String qrPayload,
      String expiresAt
  ) {}

  public record UserKey(
      String openid,
      String key,
      String keyPreview,
      String openVikingUserId,
      String instanceId,
      boolean created
  ) {}

  public record ChatResult(String answer, String cmRequestId, String sseRequestId) {}
}
```

### 第 5 步：给小程序前端提供业务接口

```java
package com.example.miniapp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/miniapp/claw-manager")
public class MiniappClawManagerController {
  private final ClawManagerMiniappClient clawManagerClient;
  private final MiniappUserKeyStore userKeyStore;

  public MiniappClawManagerController(
      ClawManagerMiniappClient clawManagerClient,
      MiniappUserKeyStore userKeyStore
  ) {
    this.clawManagerClient = clawManagerClient;
    this.userKeyStore = userKeyStore;
  }

  @PostMapping("/wechat-bind-links")
  public ClawManagerMiniappClient.Binding createBindLink(@RequestBody OpenidRequest request) {
    return clawManagerClient.createBindLink(request.openid());
  }

  @GetMapping("/wechat-bind-links/{bindToken}")
  public ClawManagerMiniappClient.Binding getBindLink(@PathVariable String bindToken) {
    return clawManagerClient.getBindLink(bindToken);
  }

  @PostMapping("/user-keys")
  public UserKeyStatus createUserKey(@RequestBody OpenidRequest request) {
    ClawManagerMiniappClient.UserKey userKey = clawManagerClient.createOrGetUserKey(request.openid(), false);
    if (userKey.key() != null && !userKey.key().isBlank()) {
      userKeyStore.save(request.openid(), userKey.key(), userKey.keyPreview());
    }
    return new UserKeyStatus(
        userKey.keyPreview(),
        userKey.openVikingUserId(),
        userKey.instanceId(),
        userKey.created()
    );
  }

  @PostMapping("/chat")
  public ClawManagerMiniappClient.ChatResult chat(@RequestBody ChatRequest request) {
    String userKey = userKeyStore.requireUserKey(request.openid());
    return clawManagerClient.chatStream(userKey, request.conversationId(), request.message());
  }

  public record OpenidRequest(String openid) {}

  public record ChatRequest(String openid, String conversationId, String message) {}

  public record UserKeyStatus(
      String keyPreview,
      String openVikingUserId,
      String instanceId,
      boolean created
  ) {}
}
```

`MiniappUserKeyStore` 是你们小程序后端自己的持久化层，可以落 MySQL、Redis 或现有用户表。至少需要按 `openid` 保存完整 `cm_user_...`：

```java
package com.example.miniapp;

public interface MiniappUserKeyStore {
  void save(String openid, String userKey, String keyPreview);

  String requireUserKey(String openid);
}
```

### 第 6 步：前端/后端串联流程

1. 小程序前端点击“接入微信”按钮。
2. 小程序后端调用 `POST /miniapp/claw-manager/wechat-bind-links`，body 为 `{"openid":"..."}`。
3. 小程序前端展示返回的 `qrLink` 或 `qrPayload`。
4. 小程序前端或后端轮询 `GET /miniapp/claw-manager/wechat-bind-links/{bindToken}`。
5. 状态变为 `connected` 后，小程序后端调用 `POST /miniapp/claw-manager/user-keys`。
6. 小程序后端保存首次返回的完整 `key`，后续只需要展示 `keyPreview`，小程序前端不要持有完整 `cm_user_...`。
7. 用户在小程序中聊天时，小程序后端调用 `POST /miniapp/claw-manager/chat`。
8. 排查时记录 `X-CM-Request-Id` 和聊天结果里的 `sseRequestId`。

### 第 7 步：验证微信/API 共享 OpenViking 记忆

双向验收建议用两个互不混淆的事实：

1. 微信侧发送：`请记住我的微信侧代号是海棠九号。`
2. 等待 OpenViking 异步抽取完成。
3. API 侧调用：`我的微信侧代号是什么？`
4. API 答案应包含：`海棠九号`。
5. API 侧调用：`请记住我的 API 侧口令是松针回声。`
6. 等待 OpenViking 异步抽取完成。
7. 微信侧发送：`我的 API 侧口令是什么？`
8. 微信答案应包含：`松针回声`。

排查时把以下 ID 一起给 Claw Manager 运维：

- 小程序接口响应头 `X-CM-Request-Id`
- 聊天 SSE `event:start` 里的 `requestId`
- `bindToken`
- `keyPreview`
- `openVikingUserId`

API 日志中 `cmRequestId` 用于定位 Claw Manager 收到的 HTTP 请求；SSE `requestId` 用于继续追到 API Channel、runner 和 OpenViking 插件日志。

## 接口定义

### 创建微信绑定二维码

```http
POST /api/external/miniapp/wechat-bind-links
Content-Type: application/json
X-CM-App-Id: miniapp_main
X-CM-Timestamp: 1783160000000
X-CM-Nonce: 8f1d1f32-6c7d-4b7e-b9e4-001
X-CM-Signature: <hex>
```

```json
{
  "openid": "miniapp-openid-001"
}
```

响应：

```json
{
  "binding": {
    "openid": "miniapp-openid-001",
    "bindToken": "wbl_xxx",
    "status": "waiting_scan",
    "instanceId": "mr67mzy8-30acf3",
    "openVikingUserId": "",
    "canCreateUserKey": false,
    "qrLink": "https://liteapp.weixin.qq.com/q/...",
    "qrPayload": "",
    "expiresAt": "2026-07-05T10:30:10Z"
  }
}
```

行为说明：

- 首次请求会根据实例负载选择一个 OpenClaw 实例，并写入 `miniapp_user_bindings`。
- 当前“负载最小”按 `微信绑定用户数 + 小程序绑定用户数` 计算。
- 同一 `openid` 二次出码会复用原 `instance_id`，不会改变已绑定的 `openviking_user_id`。

### 查询绑定状态

```http
GET /api/external/miniapp/wechat-bind-links/{bindToken}
X-CM-App-Id: miniapp_main
X-CM-Timestamp: 1783160000000
X-CM-Nonce: 8f1d1f32-6c7d-4b7e-b9e4-002
X-CM-Signature: <hex>
```

扫码后初始化中响应：

```json
{
  "binding": {
    "openid": "miniapp-openid-001",
    "bindToken": "wbl_xxx",
    "status": "initializing",
    "instanceId": "mr67mzy8-30acf3",
    "openVikingUserId": "",
    "canCreateUserKey": false,
    "qrLink": "",
    "qrPayload": "",
    "expiresAt": "2026-07-05T10:30:10Z"
  }
}
```

状态说明：

- `waiting_scan`：二维码已生成，等待用户扫码。
- `scanned`：Claw Manager 已收到微信登录确认，正在校验绑定关系。
- `initializing`：微信身份已确认，正在初始化微信通道；小程序可以轮询到该进度，但还不能生成用户 key。
- `connected`：微信通道已激活；此时 `canCreateUserKey=true`，小程序后端可以生成用户 key。

### 生成或查看用户 key

```http
POST /api/external/miniapp/user-keys
Content-Type: application/json
X-CM-App-Id: miniapp_main
X-CM-Timestamp: 1783160000000
X-CM-Nonce: 8f1d1f32-6c7d-4b7e-b9e4-003
X-CM-Signature: <hex>
```

```json
{
  "openid": "miniapp-openid-001",
  "reset": false
}
```

首次生成响应会返回完整 key：

```json
{
  "userKey": {
    "openid": "miniapp-openid-001",
    "key": "cm_user_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "keyPreview": "cm_user_xxxx...xxxx",
    "openVikingUserId": "wx_8db1ee8f655145d6cfa4e286cda3fda3",
    "instanceId": "mr67mzy8-30acf3",
    "created": true
  }
}
```

非首次且 `reset=false` 时只返回 `keyPreview`，不再回显完整 key。`reset=true` 会生成新 key，并替换旧 key。

### API 聊天 SSE

```http
POST /api/external/openclaw/chat/stream
Authorization: Bearer cm_user_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Content-Type: application/json
```

```json
{
  "conversationId": "miniapp-conv-001",
  "message": "请记住我的小程序口令是银杏罗盘。",
  "metadata": {
    "source": "miniapp"
  }
}
```

请求字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | 用户消息，不能为空 |
| `conversationId` | 否 | 小程序侧会话 ID；为空时使用 `default` |
| `metadata` | 否 | 透传给 API Channel 的附加信息，建议包含 `source=miniapp` |
| `openid` | 否 | 一般不传；如果传入，必须与 key 绑定的 openid 一致 |

SSE 事件：

```text
event:start
data:{"requestId":"...","instanceId":"...","conversationId":"...","openVikingUserId":"wx_..."}

event:delta
data:{"text":"银"}

event:done
data:{"requestId":"...","messageId":"...","openVikingUserId":"wx_...","finishedAt":"..."}
```

错误时返回 `event:error`，并结束 SSE。常见错误包括用户 key 无效、用户未完成扫码绑定、绑定实例不可用、API Channel 未安装或未启动。

## 时序图

### 首次绑定

```mermaid
sequenceDiagram
  participant M as 小程序后端
  participant C as Claw Manager API
  participant D as MySQL
  participant G as OpenClaw Gateway
  participant W as 用户微信

  M->>C: POST /miniapp/wechat-bind-links(openid,HMAC)
  C->>D: 创建 miniapp_user_bindings(pending)
  C->>C: 选择负载最小 ready 实例
  C->>G: 创建微信绑定二维码
  C->>D: 写 wechat_bind_links.miniapp_openid_hash
  C-->>M: 返回 bindToken 和 qrLink
  W->>G: 扫码
  G->>C: 上报扫码结果
  C->>D: 写 scanned/initializing
  C->>D: 微信通道激活后写 connected 和 wx_<hash>
  M->>C: GET /miniapp/wechat-bind-links/{token}
  C-->>M: status=connected, canCreateUserKey=true
```

### API 聊天共享微信记忆

```mermaid
sequenceDiagram
  participant M as 小程序后端
  participant C as Claw Manager API
  participant D as MySQL
  participant A as API Channel
  participant O as OpenViking 插件
  participant V as OpenViking Server

  M->>C: POST /openclaw/chat/stream Bearer cm_user_...
  C->>D: 查 miniapp_user_keys 和 miniapp_user_bindings
  C->>A: openVikingUserId=wx_<hash>, senderHash, conversationHash
  A->>A: 写 sender-handoff.json
  A-->>C: SSE delta/done
  O->>A: afterTurn 获取 sessionKey
  O->>O: 从 handoff 解析 wx_<hash>
  O->>V: 用 wx_<hash> 的 user key 写入/召回
```

## 数据库字典

### `miniapp_clients`

小程序后端调用方表，用于 HMAC 鉴权。

| 字段 | 作用 |
| --- | --- |
| `app_id` | 调用方 ID，对应请求头 `X-CM-App-Id` |
| `app_secret` | HMAC secret，当前按明文保存 |
| `enabled` | 是否启用，禁用后请求返回 401 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### `miniapp_request_nonces`

HMAC 防重放表。

| 字段 | 作用 |
| --- | --- |
| `app_id` | 调用方 ID |
| `nonce` | 请求 nonce，和 `app_id` 组成主键 |
| `created_at` | nonce 写入时间 |
| `expires_at` | nonce 过期时间，当前 5 分钟 |

### `miniapp_user_bindings`

小程序用户绑定事实源，保存 openid、OpenClaw 实例、微信用户和 OpenViking 用户的关系。

| 字段 | 作用 |
| --- | --- |
| `openid_hash` | `HMAC(identity_salt, openid).slice(0, 32)`，主键 |
| `openid` | 小程序传入的原始 openid |
| `instance_id` | 首次出码选择的 OpenClaw 实例，二次出码继续复用 |
| `wechat_user_id` | 扫码完成后的微信用户 ID |
| `openviking_user_id` | 微信用户对应的 `wx_<hash>`，API 和微信共享记忆的关键字段 |
| `bind_status` | 小程序绑定状态，例如 `pending`、`waiting_scan`、`connected`、`rejected`；`connected` 表示已具备 `wx_<hash>` 身份，可生成用户 key |
| `current_bind_token` | 当前二维码 token，对应 `wechat_bind_links.token` |
| `bound_at` | 成功绑定时间 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### `miniapp_user_keys`

小程序用户聊天 key 表。

| 字段 | 作用 |
| --- | --- |
| `openid_hash` | 关联 `miniapp_user_bindings.openid_hash` |
| `openid` | 原始 openid，便于排查和校验 |
| `user_key` | `cm_user_...` 完整用户 key，当前按明文保存 |
| `key_preview` | 脱敏展示值 |
| `enabled` | key 是否启用 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `last_used_at` | 最近生成、查看或聊天使用时间 |

### `wechat_bind_links`

微信二维码出码和扫码状态表。小程序出码复用这张表。

| 字段 | 作用 |
| --- | --- |
| `token` | 绑定链接 token |
| `mode` | 出码模式 |
| `phone` | 传统管理员扫码绑定流程使用的手机号字段；小程序出码为空 |
| `instance_id` | 出码所在 OpenClaw 实例 |
| `target_account_id` | 二次扫码时指定原微信账号，确保回到同一实例和账号链路 |
| `scanned_wechat_user_id` | 实际扫码得到的微信用户 ID |
| `status` | 二维码状态，例如 `created`、`waiting_scan`、`scanned`、`initializing`、`connected`、`rejected` |
| `qr_mode` | 二维码模式 |
| `qr_payload` | 二维码 payload |
| `qr_link` | 可直接打开的二维码链接 |
| `qr_expires_at` | 二维码过期时间 |
| `error_message` | 出码或扫码错误信息 |
| `created_by_admin_id` | 管理员出码来源；小程序出码可为空 |
| `miniapp_openid_hash` | 小程序 openid hash，用于把扫码结果回写到 `miniapp_user_bindings` |
| `created_at` | 创建时间 |
| `started_at` | 出码开始时间 |
| `expires_at` | 绑定链接过期时间 |
| `completed_at` | 完成时间 |
| `updated_at` | 更新时间 |

### `openviking_user_keys`

OpenViking user key broker 缓存表。

| 字段 | 作用 |
| --- | --- |
| `account_id` | OpenViking account，默认 `claw-manager` |
| `openviking_user_id` | 微信用户对应的 `wx_<hash>`；小程序 API 和微信共享这一身份 |
| `user_key` | OpenViking Server 返回的用户级 API key |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### 相关表

| 表 | 作用 |
| --- | --- |
| `instances` | OpenClaw 实例元数据，`id/status/container_name/port` 用于路由和可用性判断 |
| `instance_provisioning` | 实例 ready 状态，小程序首绑和聊天都要求目标实例 ready |
| `wechat_paired_accounts` | 已绑定微信账号，二次出码会根据 `wechat_user_id` 查回原账号；小程序扫码绑定允许 `phone` 为空 |

## 二次扫码

同一 openid 再次请求二维码时：

- 读取已有 `miniapp_user_bindings`。
- 使用原 `instance_id` 出码。
- 如果已有 `wechat_user_id`，通过 `wechat_paired_accounts` 找到原微信账号并设置 `target_account_id`。
- 同一微信用户重新扫码后，`openviking_user_id` 保持原 `wx_<hash>`。
- 如果扫码结果被服务端判定为不允许的账号，绑定状态会进入 `rejected`，调用方应提示用户重新处理绑定。

## 验收与排障

双向验收：

1. 小程序出码，用户微信扫码。
2. 查询状态直到 `status=connected` 且 `openVikingUserId=wx_...`。
3. 生成 `cm_user_...`。
4. 微信发送“请记住我的微信代号是白桦灯塔”。
5. API 使用 `cm_user_...` 问“我的微信代号是什么”，应答包含“白桦灯塔”。
6. API 发送“请记住我的小程序口令是银杏罗盘”。
7. 微信问“我的小程序口令是什么”，应答包含“银杏罗盘”。
8. 检查 runner 日志中 API 和微信 afterTurn 均显示同一个 `wx_<hash>`。

常见问题：

| 现象 | 排查点 |
| --- | --- |
| HMAC 接口返回 401 | 检查 `app_id`、`app_secret`、timestamp、nonce、raw body 和签名串 |
| 生成 user key 返回 409 | 用户尚未扫码到 `connected`，或 `openviking_user_id` 为空 |
| 聊天接口返回 401 | `Authorization` 不是有效的 `Bearer cm_user_...` |
| 聊天接口返回 409 | 绑定实例不可用、未 ready 或 API Channel 未安装 |
| 记忆写到错误用户 | 检查 API Channel 日志里的 `openVikingUserId` 和 `sender-handoff.json` |
| OpenViking 没有抽取 task | 检查 OpenViking 插件版本、afterTurn memory intent commit 日志和 `task_id` |
| SSE chunk 重复 | 当前属于 API Channel 流式输出问题，需结合 requestId 查看 runner 日志 |
