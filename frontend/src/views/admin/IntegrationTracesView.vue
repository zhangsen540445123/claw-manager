<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Copy, RefreshCw, Search } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import MetricCard from "../../components/MetricCard.vue";
import PageHeader from "../../components/PageHeader.vue";
import { api } from "../../api/http";

type Summary = { traceId:string; channel:string; instanceId:string; senderHashPreview:string; startedAt:string; finishedAt:string; lastEventAt:string; diagnosedAt:string; elapsedMs:number; status:string; lastStage:string; diagnosisCode:string; diagnosisMessage:string };
type Event = { component:string; stage:string; status:string; requestId:string; toolName:string; httpStatus:number; businessCode:number; elapsedMs:number; errorCode:string; errorMessage:string; details:Record<string,unknown>; createdAt:string };
type ModelCallAudit = { id:string; eventType:string; instanceId:string; agentId:string; sessionId:string; sessionKeyHash:string; runId:string; callId:string; provider:string; model:string; apiTransport:string; pluginVersion:string; systemPrompt:string; prompt:string; historyMessages:unknown; imagesCount:number; output:unknown; usage:unknown; stopReason:string; durationMs:number|null; outcome:string; errorCategory:string; createdAt:string; expiresAt:string };
type Detail = { summary:Summary; timeline:Event[]; relatedRequestIds:string[]; artifact:Record<string,unknown>; diagnosis:{code:string;message:string}; modelCallAudits?:ModelCallAudit[] };

const diagnosisOptions = [
  "NO_OPENCLAW_DISPATCH", "NO_IMAGE_TOOL_CALL", "IMAGE_TOOL_FAILED", "IMAGE_PROVIDER_NOT_CALLED",
  "IMAGE_PROVIDER_FAILED", "IMAGE_DECODE_FAILED", "IMAGE_FILE_WRITE_FAILED", "ARTIFACT_NOT_CALLED",
  "ARTIFACT_TOOL_FAILED", "ARTIFACT_UPLOAD_FAILED", "ARTIFACT_HTML_FAILED", "WECHAT_MEDIA_FAILED",
  "DISPATCH_TIMEOUT", "RECOVERED_AFTER_RETRY", "COMPLETE"
];
const loading=ref(false), detailLoading=ref(false), drawer=ref(false), hasMore=ref(false);
const items=ref<Summary[]>([]), timeline=ref<Event[]>([]), selected=ref<Summary|null>(null), related=ref<string[]>([]), audits=ref<ModelCallAudit[]>([]);
const artifact=ref<Record<string,unknown>>({}), page=ref(1), size=ref(20);
const filters=reactive<{traceId:string;channel:string;status:string;instanceId:string;component:string;diagnosisCode:string;range:[Date,Date]|null}>({ traceId:"", channel:"", status:"", instanceId:"", component:"", diagnosisCode:"", range:null });
const paginationTotal=computed(()=>(page.value-1)*size.value+items.value.length+(hasMore.value?1:0));
const completedCount=computed(()=>items.value.filter((item)=>item.status==="completed").length);
const failedCount=computed(()=>items.value.filter((item)=>item.status==="failed").length);
const imageIssueCount=computed(()=>items.value.filter((item)=>item.diagnosisCode&&item.diagnosisCode!=="COMPLETE").length);

async function load(reset=false){
  if(reset) page.value=1;
  loading.value=true;
  try {
    const traceId=filters.traceId.trim();
    if(traceId){
      const result=await api<Detail>(`/api/admin/integration-traces/${encodeURIComponent(traceId)}`);
      items.value=[result.summary];hasMore.value=false;return;
    }
    const q=new URLSearchParams({page:String(page.value),size:String(size.value)});
    for(const key of ["channel","status","instanceId","component","diagnosisCode"] as const) if(filters[key])q.set(key,filters[key]);
    if(filters.range){q.set("from",filters.range[0].toISOString());q.set("to",filters.range[1].toISOString());}
    const result=await api<{items:Summary[];hasMore:boolean}>(`/api/admin/integration-traces?${q}`);
    items.value=result.items;hasMore.value=result.hasMore;
  } catch(e){ElMessage.error(e instanceof Error?e.message:"链路读取失败");}
  finally{loading.value=false;}
}
async function open(row:Summary){
  drawer.value=true;detailLoading.value=true;selected.value=row;timeline.value=[];related.value=[];audits.value=[];artifact.value={};
  try {const result=await api<Detail>(`/api/admin/integration-traces/${encodeURIComponent(row.traceId)}`);timeline.value=result.timeline;related.value=result.relatedRequestIds;artifact.value=result.artifact??{};audits.value=result.modelCallAudits??[];}
  catch(e){ElMessage.error(e instanceof Error?e.message:"链路详情读取失败");}
  finally{detailLoading.value=false;}
}
async function copy(value:string){await navigator.clipboard.writeText(value);ElMessage.success("已复制");}
function tag(status:string){return status==="failed"?"danger":status==="completed"?"success":"warning";}
function statusText(status:string){return status==="failed"?"失败":status==="completed"?"完成":"进行中";}
function channelText(channel:string){return channel==="wechat"?"微信":channel==="api"?"API":"内部";}
function formatTime(value:string){return value?new Date(value).toLocaleString("zh-CN",{hour12:false}):"-";}
function formatElapsed(row:Summary){return `${row.finishedAt?"":"未结束，已耗时 "}${row.elapsedMs} ms`;}
function diagnosisTag(code:string){return code==="COMPLETE"?"success":code==="RECOVERED_AFTER_RETRY"?"warning":"danger";}
function hasDetails(event:Event){return Object.keys(event.details??{}).length>0;}
function pretty(value:unknown):string{
  if(value===null||value===undefined||value==="")return "-";
  return typeof value==="string"?value:JSON.stringify(value,null,2);
}
function auditTitle(audit:ModelCallAudit){return `${audit.eventType} · ${audit.provider||"-"}/${audit.model||"-"}`;}
function hasOpenVikingInjection(audit:ModelCallAudit){return /<relevant-memories>[\s\S]*<\/relevant-memories>/i.test([audit.systemPrompt,audit.prompt,pretty(audit.historyMessages)].join("\n"));}
function injectedSnippet(audit:ModelCallAudit){const text=[audit.systemPrompt,audit.prompt,pretty(audit.historyMessages)].join("\n");return text.match(/<relevant-memories>[\s\S]*?<\/relevant-memories>/i)?.[0]??"未检测到 OpenViking 记忆注入片段";}
onMounted(()=>load());
</script>

<template>
  <section class="workspace traces-page">
    <PageHeader title="链路追踪" description="按统一 Trace ID 查看消息、工具、生图、Artifact 和渠道发送时间线。">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load()">刷新</el-button></template>
    </PageHeader>
    <section class="metric-grid compact-metric-grid">
      <MetricCard label="当前页链路" :value="items.length" />
      <MetricCard label="本页已完成" :value="completedCount" tone="success" />
      <MetricCard label="本页失败" :value="failedCount" tone="danger" />
      <MetricCard label="本页需诊断" :value="imageIssueCount" tone="warning" />
    </section>
    <div class="toolbar-row">
      <el-input v-model="filters.traceId" placeholder="Trace ID（精确查询）" clearable />
      <el-date-picker v-model="filters.range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" />
      <el-input v-model="filters.instanceId" placeholder="实例 ID" clearable />
      <el-select v-model="filters.channel" placeholder="渠道" clearable><el-option label="微信" value="wechat"/><el-option label="API" value="api"/></el-select>
      <el-select v-model="filters.status" placeholder="状态" clearable><el-option label="进行中" value="in_progress"/><el-option label="完成" value="completed"/><el-option label="失败" value="failed"/></el-select>
      <el-select v-model="filters.component" placeholder="组件" clearable><el-option label="微信插件" value="wechat-plugin"/><el-option label="API Channel" value="api-channel"/><el-option label="Miniapp Bridge" value="miniapp-bridge"/><el-option label="Claw Manager" value="claw-manager"/><el-option label="Time Manager" value="time-manager"/></el-select>
      <el-select v-model="filters.diagnosisCode" placeholder="诊断码" clearable filterable><el-option v-for="code in diagnosisOptions" :key="code" :label="code" :value="code"/></el-select>
      <el-button type="primary" :icon="Search" @click="load(true)">查询</el-button>
    </div>
    <el-table :data="items" v-loading="loading" border stripe>
      <el-table-column label="时间" width="180"><template #default="{row}">{{formatTime(row.startedAt)}}</template></el-table-column>
      <el-table-column label="Trace ID" min-width="245"><template #default="{row}"><span class="mono">{{row.traceId}}</span><el-button link :icon="Copy" title="复制 Trace ID" @click="copy(row.traceId)"/></template></el-table-column>
      <el-table-column label="渠道" width="80"><template #default="{row}">{{channelText(row.channel)}}</template></el-table-column>
      <el-table-column prop="instanceId" label="实例" min-width="150"/>
      <el-table-column label="总耗时" width="150"><template #default="{row}">{{formatElapsed(row)}}</template></el-table-column>
      <el-table-column prop="lastStage" label="最后阶段" min-width="210"/>
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="tag(row.status)">{{statusText(row.status)}}</el-tag></template></el-table-column>
      <el-table-column label="自动诊断" min-width="280"><template #default="{row}"><el-tag size="small" :type="diagnosisTag(row.diagnosisCode)">{{row.diagnosisCode}}</el-tag><div class="secondary">{{row.diagnosisMessage}}</div></template></el-table-column>
      <el-table-column label="操作" width="86" fixed="right"><template #default="{row}"><el-button link type="primary" @click="open(row)">详情</el-button></template></el-table-column>
    </el-table>
    <div class="pager"><el-pagination v-model:current-page="page" :page-size="size" :total="paginationTotal" layout="prev, pager, next" @current-change="load()"/></div>
    <el-drawer v-model="drawer" title="链路详情" size="min(720px, 100%)">
      <div v-if="selected" class="trace-summary"><div><strong>{{selected.diagnosisCode}}</strong><el-tag :type="tag(selected.status)">{{statusText(selected.status)}}</el-tag></div><span>{{selected.diagnosisMessage}}</span><span class="mono">{{selected.traceId}}</span></div>
      <div v-if="Object.keys(artifact).length" class="artifact-summary"><strong>Artifact</strong><span v-for="(value,key) in artifact" :key="key"><b>{{key}}</b> {{value}}</span></div>
      <section v-if="audits.length" class="audit-section">
        <h4>模型调用审计</h4>
        <el-alert title="以下为 OpenClaw 组装后发送给模型的输入、模型输出和 OpenViking 诊断信息，仅管理员可见。" type="warning" :closable="false"/>
        <el-collapse class="audit-list">
          <el-collapse-item v-for="audit in audits" :key="audit.id" :title="auditTitle(audit)" :name="audit.id">
            <div class="audit-meta">
              <span><b>时间</b> {{formatTime(audit.createdAt)}}</span>
              <span><b>runId</b> <span class="mono">{{audit.runId || '-'}}</span></span>
              <span><b>callId</b> <span class="mono">{{audit.callId || '-'}}</span></span>
              <span><b>Agent</b> {{audit.agentId || '-'}}</span>
              <span><b>Session</b> {{audit.sessionId || '-'}}</span>
              <span><b>API/transport</b> {{audit.apiTransport || '-'}}</span>
              <span><b>插件版本</b> {{audit.pluginVersion || '-'}}</span>
              <span><b>图片数</b> {{audit.imagesCount || 0}}</span>
              <span><b>耗时</b> {{audit.durationMs ?? '-'}} ms</span>
              <span><b>结果</b> {{audit.outcome || '-'}}</span>
              <span v-if="audit.errorCategory"><b>错误</b> {{audit.errorCategory}}</span>
            </div>
            <el-tag v-if="hasOpenVikingInjection(audit)" type="danger" size="small">检测到 OpenViking 记忆注入</el-tag>
            <h5>OpenClaw 组装后的模型输入 · systemPrompt</h5><pre class="audit-pre">{{pretty(audit.systemPrompt)}}</pre>
            <h5>OpenClaw 组装后的模型输入 · 当前 prompt</h5><pre class="audit-pre">{{pretty(audit.prompt)}}</pre>
            <h5>OpenClaw 组装后的模型输入 · historyMessages</h5><pre class="audit-pre">{{pretty(audit.historyMessages)}}</pre>
            <h5>OpenViking 诊断信息 · 注入片段</h5><pre class="audit-pre">{{injectedSnippet(audit)}}</pre>
            <h5>模型输出</h5><pre class="audit-pre">{{pretty(audit.output)}}</pre>
            <h5>Token usage / stop reason</h5><pre class="audit-pre">{{pretty({usage:audit.usage, stopReason:audit.stopReason})}}</pre>
          </el-collapse-item>
        </el-collapse>
      </section>
      <el-timeline v-loading="detailLoading">
        <el-timeline-item v-for="event in timeline" :key="event.createdAt+event.stage+event.requestId" :timestamp="formatTime(event.createdAt)" :type="tag(event.status)">
          <div class="event-title"><strong>{{event.stage}}</strong><el-tag size="small" :type="tag(event.status)">{{statusText(event.status)}}</el-tag></div>
          <div class="event-meta">
            {{event.component}} · {{event.requestId || '-'}}
            <template v-if="event.toolName"> · 工具 {{event.toolName}}</template>
            <template v-if="event.httpStatus"> · HTTP {{event.httpStatus}}</template>
            <template v-if="event.businessCode"> · 业务码 {{event.businessCode}}</template>
            <template v-if="event.elapsedMs"> · {{event.elapsedMs}} ms</template>
          </div>
          <div v-if="hasDetails(event)" class="event-details"><span v-for="(value,key) in event.details" :key="key"><b>{{key}}</b> {{value}}</span></div>
          <el-alert v-if="event.errorCode || event.errorMessage" :title="[event.errorCode,event.errorMessage].filter(Boolean).join(' · ')" type="error" :closable="false"/>
        </el-timeline-item>
      </el-timeline>
      <div v-if="related.length"><h4>相关 Request ID</h4><div v-for="id in related" :key="id" class="request-id"><span class="mono">{{id}}</span><el-button link :icon="Copy" title="复制 Request ID" @click="copy(id)"/></div></div>
    </el-drawer>
  </section>
</template>

<style scoped>
.toolbar-row{display:grid;grid-template-columns:minmax(230px,1fr) minmax(320px,1.5fr) minmax(150px,.8fr) 110px 110px 150px minmax(190px,1fr) auto;gap:8px;margin-bottom:12px;align-items:center}.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}.secondary,.event-meta{color:#667085;font-size:12px;margin-top:4px}.diagnosis-code{font-size:12px}.pager{display:flex;justify-content:flex-end;padding-top:12px}.trace-summary,.artifact-summary{display:flex;flex-direction:column;gap:7px;padding:12px;border:1px solid var(--border);border-radius:8px;margin-bottom:16px}.trace-summary>div{display:flex;align-items:center;gap:8px}.artifact-summary span,.event-details span{display:inline-flex;gap:5px;margin-right:12px;font-size:12px}.event-title{display:flex;gap:8px;align-items:center}.event-details{padding:7px 9px;background:#f6f7f9;border:1px solid #e7e9ee;margin:6px 0}.request-id{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--border);padding:6px 0}.audit-section{display:flex;flex-direction:column;gap:10px;padding:12px;border:1px solid #f0c36d;background:#fffaf0;border-radius:8px;margin-bottom:16px}.audit-section h4,.audit-section h5{margin:4px 0}.audit-list{background:#fff}.audit-meta{display:flex;flex-wrap:wrap;gap:8px 14px;color:#667085;font-size:12px;margin-bottom:8px}.audit-pre{max-height:220px;overflow:auto;white-space:pre-wrap;word-break:break-word;background:#101828;color:#f2f4f7;border-radius:6px;padding:10px;font-size:12px;line-height:1.45}.request-id{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--border);padding:6px 0}@media(max-width:1200px){.toolbar-row{grid-template-columns:repeat(3,minmax(0,1fr))}.toolbar-row>:nth-child(2){grid-column:span 2}}@media(max-width:700px){.toolbar-row{grid-template-columns:1fr}.toolbar-row>:nth-child(2){grid-column:auto}.pager{justify-content:center}}
</style>
