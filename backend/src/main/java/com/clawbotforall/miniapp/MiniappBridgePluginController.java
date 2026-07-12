package com.clawbotforall.miniapp;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginBatchItem;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginBatchRequest;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginVersionRequest;
import com.clawbotforall.externalapi.PublicApiChannelPluginStatus;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class MiniappBridgePluginController {
  private final InstanceCommandService instances; private final MiniappBridgePluginService plugins;
  public MiniappBridgePluginController(InstanceCommandService instances, MiniappBridgePluginService plugins) { this.instances = instances; this.plugins = plugins; }

  @GetMapping("/api/admin/instances/{id}/miniapp-bridge-plugin")
  public Map<String,Object> status(@PathVariable String id, @RequestParam(defaultValue="false") boolean checkLatest, Authentication auth) { admin(auth); return Map.of("plugin", plugins.status(instances.requireInstance(id), checkLatest)); }
  @GetMapping("/api/admin/miniapp-bridge-plugins/versions")
  public Map<String,Object> versions(Authentication auth) { admin(auth); return Map.of("versions", plugins.versions()); }
  @PostMapping("/api/admin/miniapp-bridge-plugins/check")
  public Map<String,Object> check(@RequestBody(required=false) ApiPluginBatchRequest body, Authentication auth) {
    admin(auth); List<ApiPluginBatchItem> result=new ArrayList<>();
    for(String id: body==null||body.instanceIds()==null?List.<String>of():body.instanceIds()) try {
      result.add(new ApiPluginBatchItem(id, plugins.status(instances.requireInstance(id), false)));
    } catch(RuntimeException e) {
      result.add(new ApiPluginBatchItem(id,new PublicApiChannelPluginStatus(false,"","",false,"failed",e.getMessage(),"",Instant.now().toString())));
    }
    return Map.of("plugins",result);
  }
  @PostMapping("/api/admin/instances/{id}/miniapp-bridge-plugin/{operation}")
  public Map<String,Object> operate(@PathVariable String id, @PathVariable String operation, @RequestBody(required=false) ApiPluginVersionRequest body, Authentication auth) {
    admin(auth); requireOperation(operation); var instance=instances.requireInstance(id);
    return Map.of("plugin", "uninstall".equals(operation) ? plugins.uninstall(instance) : plugins.install(instance, body==null?"":body.version(), operation));
  }
  @PostMapping("/api/admin/miniapp-bridge-plugins/{operation}")
  public Map<String,Object> batch(@PathVariable String operation, @RequestBody(required=false) ApiPluginBatchRequest body, Authentication auth) {
    admin(auth); requireOperation(operation); List<ApiPluginBatchItem> result=new ArrayList<>();
    for(String id: body==null||body.instanceIds()==null?List.<String>of():body.instanceIds()) try { var instance=instances.requireInstance(id); result.add(new ApiPluginBatchItem(id,"uninstall".equals(operation)?plugins.uninstall(instance):plugins.install(instance,body.version(),operation))); }
    catch(RuntimeException e){ result.add(new ApiPluginBatchItem(id,new PublicApiChannelPluginStatus(false,"","",false,"failed",e.getMessage(),"",Instant.now().toString()))); }
    return Map.of("plugins",result);
  }
  private static void admin(Authentication auth){ if(auth==null||!(auth.getPrincipal() instanceof AuthenticatedAdmin)) throw new ApiException(HttpStatus.UNAUTHORIZED,"请先登录。"); }
  private static void requireOperation(String operation) {
    if (!List.of("install", "upgrade", "reinstall", "uninstall").contains(operation)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的插件操作。");
    }
  }
}
