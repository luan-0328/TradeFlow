<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue'
import {api,useLoad,useCommand,PageHeader,Feedback,EmptyState,StatusBadge,session,can,type DataRow} from '@tradeflow/ui'
import {useRows,text,row,permits} from '../data'
import Pagination from '../components/Pagination.vue'
import RecordDetail from '../components/RecordDetail.vue'

type Definition={id:string;label:string;path:string;key:string;local?:boolean;cap?:string;kind?:string}
const definitions:Definition[]=[
 {id:'notifications',label:'订单通知',path:'/api/v1/admin/order-notifications',key:'id'},
 {id:'orders',label:'订单创建',path:'/api/v1/admin/order-tasks',key:'taskNo'},
 {id:'refunds',label:'退款恢复',path:'/api/v1/admin/refund-tasks',key:'source_no'},
 {id:'returns',label:'退货入库',path:'/api/v1/admin/return-stock-tasks',key:'source_no'},
 {id:'aftersales',label:'售后恢复',path:'/api/v1/admin/aftersale-tasks',key:'aftersale_no'},
 ...(['order','payment','aftersale'] as const).flatMap((service,index)=>(['outbox','deferred','failure'] as const).map((kind):Definition=>({
  id:service+'-'+kind,label:['订单','支付','售后'][index]+' · '+({outbox:'发布',deferred:'延期',failure:'隔离'}[kind]),
  path:`/api/v1/admin/${service}-messages/${kind}`,key:kind==='outbox'?'event_id':kind==='deferred'?'deferred_id':'failure_id',kind}))),
 {id:'issues',label:'对账差异',path:'/api/v1/admin/reconciliation/issues',key:'task_no',cap:'PLATFORM_RECONCILIATION'},
]
// 每个来源独立保存页码、AbortController 和错误；不把多个服务当前页拼成全量队列。
const sources=definitions.map(def=>({...def,list:useRows(()=>def.path,def.local?'local':'array'),checked:ref(false)}))
const available=computed(()=>sources.filter(s=>!s.cap||can(s.cap)))
const selectedId=ref('orders'),onlyPending=ref(true),notice=ref('')
const current=computed(()=>available.value.find(s=>s.id===selectedId.value)||available.value[0]!)
const failures=computed(()=>available.value.filter(s=>s.list.error.value))
const reading=computed(()=>available.value.filter(s=>s.list.loading.value).length)
const readied=computed(()=>available.value.filter(s=>s.checked.value&&!s.list.loading.value&&!s.list.error.value).length)
const active=(r:DataRow)=>permits(r,'RETRY')||permits(r,'REPAIR')||r.state==='MANUAL_REVIEW'||r.status==='MANUAL_REVIEW'||r.review_required==='YES'
function pending(s:typeof sources[number],r:DataRow){return active(r)||(s.kind==='failure'&&r.disposition!=='RESOLVED')||(s.id==='issues'&&r.state!=='RESOLVED')}
const visible=computed(()=>current.value.list.items.value.filter(r=>!onlyPending.value||pending(current.value,r)))
const value=(r:DataRow,...keys:string[])=>text(keys.map(k=>r[k]).find(v=>v!==undefined&&v!==null))
const state=(r:DataRow)=>value(r,'state','status')
const identifier=(s:typeof sources[number],r:DataRow)=>text(r[s.key])
function business(r:DataRow){return value(r,'businessNo','business_no','order_no','orderNo','aftersale_no','aggregate_id','source_no','event_id')}
function actions(r:DataRow){const labels=[];if(permits(r,'RETRY'))labels.push('恢复原任务');if(permits(r,'REDELIVER_ORIGINAL'))labels.push('补投原事件');if(permits(r,'REPAIR'))labels.push('核查并修复');return labels.length?labels.join('、'):'仅可查看'}
let disposed=false
async function refresh(s:typeof sources[number]){const epoch=session.epoch;await s.list.reload();if(!disposed&&epoch===session.epoch)s.checked.value=true}
async function refreshAll(){await Promise.allSettled(available.value.map(refresh))}
async function change(delta:number){const s=current.value,epoch=session.epoch;await s.list.change(delta);if(!disposed&&epoch===session.epoch)s.checked.value=true}

const selected=ref<{source:string;record:DataRow}|null>(null),detailVisible=ref(false),action=ref(''),reason=ref(''),localError=ref('')
const selectedSource=computed(()=>sources.find(s=>s.id===selected.value?.source))
const detail=useLoad<DataRow>(signal=>api('/api/v1/admin/reconciliation/issues/'+encodeURIComponent(text(selected.value?.record.task_no)),{signal}))
const actionDetail=useLoad<DataRow>(signal=>api('/api/v1/admin/reconciliation/actions/'+encodeURIComponent(text(row(detail.data.value).action_no)),{signal}))
const command=useCommand('platform-pending-tasks')
let generation=0
/** 单条对账详情复用原入口；其他来源没有平台详情接口，只展示已接受的原任务记录。 */
async function inspect(s:typeof sources[number],r:DataRow){const g=++generation;action.value='';selected.value={source:s.id,record:r};detailVisible.value=true;detail.clear();actionDetail.clear();if(s.id==='issues'){await detail.reload();if(g===generation&&detailVisible.value&&detail.data.value?.action_no)await actionDetail.reload()}}
function close(){generation++;detailVisible.value=false;action.value='';selected.value=null;detail.clear();actionDetail.clear();localError.value='';reason.value=''}
/** SENT历史补投有独立动作与原因表单，不把Broker确认误当成所有消费者已完成。 */
function choose(kind:string){generation++;action.value=kind;reason.value='';localError.value=''}
const actionable=computed(()=>selectedSource.value?.id==='issues'?detail.data.value:selected.value?.record)
/** 原任务、原正文语义、稳定幂等键恢复；未知结果保留日记，不能换来源号或新建退款执行。 */
async function submit(){
 const target=selected.value,s=selectedSource.value,r=actionable.value,kind=action.value
 if(!target||!s||!r||!permits(r,kind))return
 localError.value='';if(!reason.value.trim()){localError.value='请填写本次恢复原因';return}
 const id=identifier(s,target.record);if(id==='—'){localError.value='原任务编号缺失，请刷新来源后核对';return}
 if(kind==='REDELIVER_ORIGINAL'&&(s.kind!=='outbox'||r.status!=='SENT')){localError.value='仅可补投有原文的已发送事件，请刷新核对';return}
 const g=generation,epoch=session.epoch,url=s.path+'/'+encodeURIComponent(id)+'/'+(kind==='REPAIR'?'repair':'retry')
 const body:DataRow={reason:reason.value.trim()};if(s.id==='refunds')body.newAttempt=false
 const result=await command.run(kind+':'+url,body,key=>api<DataRow>(url,{method:'POST',body,key}))
 if(g!==generation||epoch!==session.epoch||result===undefined)return
 await command.reset();if(g!==generation||epoch!==session.epoch)return
 action.value='';notice.value=kind==='REDELIVER_ORIGINAL'?`原事件 ${id} 的补投请求已提交，请核对缺失目标的实际应用；已处理目标仍可能收到重复投递。`:`${s.label}原记录 ${id} 的恢复请求已返回，后台继续确认结果；受理不代表业务完成。`
 await refresh(s);if(g!==generation||epoch!==session.epoch)return
 if(s.id==='issues'){await detail.reload();if(g===generation&&detail.data.value?.action_no)await actionDetail.reload()}
 else {const latest=s.list.items.value.find(item=>identifier(s,item)===id);if(latest)selected.value={source:s.id,record:latest}}
}
watch(selectedId,()=>{close();notice.value=''})
watch(()=>session.epoch,()=>{close();notice.value='';for(const s of sources){s.list.reset();s.checked.value=false}})
onBeforeUnmount(()=>{disposed=true;generation++})
onMounted(refreshAll)
</script>

<template>
 <PageHeader title="人工待办" description="逐项核对恢复义务、消息异常与对账差异。所有恢复沿用原记录，后续结果由后台确认。"><template #actions><ElButton :loading="reading>0" @click="refreshAll">刷新全部来源</ElButton></template></PageHeader>
 <p class="coverage" role="status">已读取 {{readied}} / {{available.length}} 个来源<span v-if="reading">，{{reading}} 个读取中</span>。下方只统计各来源当前页，不代表全平台待办总量。</p>
 <div v-if="failures.length" class="source-errors" role="alert"><strong>{{failures.length}} 个来源读取失败，待办情况尚未确认</strong><ul><li v-for="s in failures" :key="s.id">{{s.label}}：{{s.list.error.value}} <ElButton link type="primary" @click="refresh(s)">重新读取</ElButton></li></ul></div>
 <p v-if="notice" class="notice" role="status">{{notice}}</p>
 <nav class="source-grid" aria-label="待办来源"><button v-for="s in available" :key="s.id" type="button" :class="['source-button',{'is-active':current.id===s.id,'has-error':!!s.list.error.value}]" :aria-pressed="current.id===s.id" @click="selectedId=s.id"><strong>{{s.label}}</strong><span v-if="s.list.loading.value">正在读取</span><span v-else-if="s.list.error.value">读取失败 · 待核对</span><span v-else-if="!s.checked.value">尚未读取</span><span v-else>本页待处理 {{s.list.items.value.filter(r=>pending(s,r)).length}} / {{s.list.items.value.length}} 条</span></button></nav>
 <section class="panel" :aria-label="current.label+'待办列表'"><div class="toolbar"><h2>{{current.label}}</h2><div class="toolbar-controls"><ElCheckbox v-model="onlyPending">仅显示本页待处理记录</ElCheckbox><ElButton :loading="current.list.loading.value" @click="refresh(current)">刷新此来源</ElButton></div></div>
 <p v-if="current.kind==='outbox'" class="notice">已发送仅表示Broker确认。核验拓扑及故障影响区间后，可按原事件补投；历史SENT不计入待处理数量。<ElButton v-if="onlyPending" @click="onlyPending=false">查看已发送原事件</ElButton></p>
 <p v-if="current.local" class="field-hint">此接口最多返回100条人工任务，以下仅对该返回集合分页；无法据此判断更早任务是否已全部解决。</p>
 <p v-else class="field-hint">该来源独立分页。{{current.list.total.value===null?'服务未返回全量总数；满20条时可继续翻页核对。':'来源记录总数 '+current.list.total.value+' 条（含非待办记录）。'}} 本页原始记录 {{current.list.items.value.length}} 条，当前显示 {{visible.length}} 条。</p>
 <Feedback :loading="current.list.loading.value" :error="current.list.error.value"/>
 <template v-if="current.checked.value&&!current.list.loading.value&&!current.list.error.value"><EmptyState v-if="!visible.length" :title="onlyPending?'当前页未发现待处理记录':'当前页暂无记录'" description="此结论仅针对当前来源与当前页；可以关闭筛选或继续翻页核对。"/>
 <ElTable v-else :data="visible"><ElTableColumn label="类别 / 原任务号" min-width="240"><template #default="{row:r}"><small class="muted">{{current.label}}</small><div class="identifier">{{identifier(current,r)}}</div></template></ElTableColumn><ElTableColumn label="关联业务编号" min-width="240"><template #default="{row:r}"><span class="identifier">{{business(r)}}</span></template></ElTableColumn><ElTableColumn label="状态 / 处置" min-width="160"><template #default="{row:r}"><StatusBadge :status="state(r)"/><div v-if="r.disposition" class="field-hint">{{r.disposition}}</div></template></ElTableColumn><ElTableColumn label="错误摘要" min-width="230"><template #default="{row:r}"><span class="error-summary">{{value(r,'lastError','last_error','reason','issue_type','result_code')}}</span></template></ElTableColumn><ElTableColumn label="尝试次数" width="110"><template #default="{row:r}">{{value(r,'attemptCount','attempt_count','attempts','consecutive_failures')}}</template></ElTableColumn><ElTableColumn label="更新时间 / 下次重试" min-width="220"><template #default="{row:r}"><div>{{value(r,'updated_at','updatedAt','created_at')}}</div><small class="muted">下次：{{value(r,'nextRetryAt','next_retry_at')}}</small></template></ElTableColumn><ElTableColumn label="允许动作" min-width="180" fixed="right"><template #default="{row:r}"><div class="field-hint">{{actions(r)}}</div><ElButton @click="inspect(current,r)">查看原记录</ElButton></template></ElTableColumn></ElTable>
 </template>
 <Pagination :page="current.list.page.value" :count="current.list.items.value.length" :has-next="current.list.hasNext.value" :loading="current.list.loading.value||!!current.list.error.value" @change="change"/>
 </section>
 <ElDialog :model-value="detailVisible" title="原任务与关联详情" width="min(900px, 96vw)" @close="close"><template v-if="selected&&selectedSource"><p class="notice">来源：{{selectedSource.label}}<br>原编号：{{identifier(selectedSource,selected.record)}}<br>关联业务：{{business(selected.record)}}</p><Feedback :loading="detail.loading.value" :error="detail.error.value"/><RecordDetail :value="selectedSource.id==='issues'?(detail.data.value||selected.record):selected.record"/><p v-if="selectedSource.id!=='issues'" class="field-hint">以上为该来源返回的原任务记录。关联业务号保留用于核验；此入口不读取买家地址或借用商家权限。</p><template v-if="selectedSource.id==='issues'&&detail.data.value?.action_no"><h3 class="section-title">原差异关联动作</h3><Feedback :loading="actionDetail.loading.value" :error="actionDetail.error.value"/><RecordDetail v-if="actionDetail.data.value" :value="actionDetail.data.value"/></template><div class="inline-actions"><ElButton v-if="permits(actionable,'RETRY')" type="primary" @click="choose('RETRY')">恢复原任务</ElButton><ElButton v-if="selectedSource.kind==='outbox'&&permits(actionable,'REDELIVER_ORIGINAL')" type="primary" @click="choose('REDELIVER_ORIGINAL')">补投原事件</ElButton><ElButton v-if="permits(actionable,'REPAIR')" type="primary" @click="choose('REPAIR')">核查并修复原差异</ElButton><RouterLink v-if="selectedSource.id==='issues'&&can('PLATFORM_RECONCILIATION')" to="/platform/reconciliation">前往交易对账查看历史观察</RouterLink></div>
 <ElForm v-if="action" label-position="top" class="recovery-form" @submit.prevent="submit"><p v-if="action==='REDELIVER_ORIGINAL'" class="notice">请填写故障工单、影响区间、缺失目标及拓扑恢复核验结果。补投保持原事件编号和正文，仍经过资金核验与租约围栏，并会到达已成功的队列；重复消费由各目标幂等处理。请求提交不代表缺失目标已应用。</p><p v-else class="notice">只提交原任务的受控恢复。结果未知时保留原原因与原键重试，退款不会另建执行号。</p><ElFormItem :label="action==='REDELIVER_ORIGINAL'?'补投原因':'恢复原因'" required><ElInput v-model="reason" type="textarea" :rows="3" maxlength="500" aria-describedby="recovery-error"/></ElFormItem><div id="recovery-error"><Feedback :error="localError||command.error.value"/></div><ElButton native-type="submit" type="primary" :loading="command.busy.value" :disabled="!reason.trim()">{{action==='REDELIVER_ORIGINAL'?'提交原事件补投':'提交原记录恢复'}}</ElButton><ElButton :disabled="command.busy.value" @click="action=''">取消</ElButton></ElForm></template></ElDialog>
</template>

<style scoped>
.coverage{color:#52637c;line-height:1.7;margin:0 0 16px}.source-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:8px;margin-bottom:22px}.source-button{border:1px solid #d9e1ed;background:#fff;text-align:left;padding:12px 14px;min-height:70px;border-radius:6px;color:#172b4d;cursor:pointer}.source-button strong,.source-button span{display:block}.source-button span{font-size:12px;margin-top:6px;color:#52637c}.source-button.is-active{border-color:#3563a6;background:#edf3fc;box-shadow:inset 3px 0 #3563a6}.source-button.has-error{border-color:#bc4545}.source-button.has-error span{color:#a32828}.source-button:focus-visible{outline:3px solid #3563a6;outline-offset:2px}.source-errors{border-left:4px solid #bc4545;background:#fff1f0;color:#8c2424;padding:14px 18px;margin:0 0 18px;line-height:1.7}.source-errors ul{margin:8px 0 0;padding-left:20px}.identifier,.error-summary{overflow-wrap:anywhere}.recovery-form{margin-top:22px;padding-top:18px;border-top:1px solid #d9e1ed}@media(max-width:650px){.source-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.source-button{padding:10px;min-height:76px}.toolbar-controls{flex-wrap:wrap}}
</style>
