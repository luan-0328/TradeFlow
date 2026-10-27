<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {api,can,useLoad,PageHeader,Feedback,EmptyState,session,type DataRow} from '@tradeflow/ui'
import {text} from '../data'
import Pagination from '../components/Pagination.vue'

const props=defineProps<{platform?:boolean}>()
// 平台页面必须由路由显式选择；拥有平台角色也不能把商家页面自动切成全平台范围。
const platform=computed(()=>props.platform===true&&can('PLATFORM_FUNDS'))
const permitted=computed(()=>!!session.user&&(props.platform?platform.value:!!session.merchantId))
const base=computed(()=>`/api/v1/${platform.value?'admin':'merchant'}/settlement`)
const tab=ref('statements'),page=ref(1),filter=ref(''),merchantFilter=ref(''),inputError=ref('')
const commandBusy=ref(false),commandError=ref(''),commandResult=ref<DataRow|null>(null)
let generation=0
interface PageResult {items:DataRow[];page:number;pageSize:number;hasMore:boolean}
function query(kind:string){return platform.value&&merchantFilter.value&&!['tasks','reconciliations'].includes(kind)?`&merchantId=${encodeURIComponent(merchantFilter.value)}`:''}
async function read<T>(kind:string,signal:AbortSignal):Promise<T>{if(!permitted.value)throw Error(props.platform?'缺少平台资金查看权限':'请在顶部选择所属商家后查看结算');return api<T>(`${base.value}/${kind}?page=${page.value}&pageSize=20${query(kind)}`,{signal})}
const summary=useLoad<DataRow>(signal=>read('summary',signal))
const list=useLoad<PageResult>(signal=>read(tab.value,signal))
const items=computed(()=>list.data.value?.items??[])
const tabs=computed(()=>[
 {key:'statements',label:'商家账单'},{key:'items',label:'结算单据'},{key:'ledger',label:'追加账本'},{key:'batches',label:'模拟批次'},
 ...(platform.value?[{key:'reconciliations',label:'本地对账'},{key:'tasks',label:'后台任务'}]:[])
])
type Column={key:string;label:string;money?:boolean}
function col(key:string,label:string,money=false):Column{return{key,label,money}}
const columns=computed<Column[]>(()=>{
 const owner=platform.value?[col('merchant_id','商家')]:[]
 if(tab.value==='ledger')return[col('entry_key','原事实 / 结算编号'),...owner,col('entry_type','账本类型'),col('principal_delta','本金变动',true),col('fee_delta','服务费变动',true),col('payable_delta','待结算应付变动',true),col('simulated_delta','净模拟完成变动',true),col('created_at','记账时间')]
 if(tab.value==='batches')return[col('batch_no','模拟批次'),...owner,col('payment_no','原支付号'),col('net_cents','本批净结算',true),col('simulated_payout_cents','模拟支付给商家',true),col('debt_offset_cents','历史负债抵扣',true),col('status','状态'),col('created_at','模拟完成时间')]
 if(tab.value==='reconciliations')return[col('run_no','核对编号'),col('result','核对结果'),col('detail','差异明细'),col('created_at','核对时间')]
 if(tab.value==='tasks')return[col('task_no','任务号'),col('scan_cursor','资金扫描游标'),col('candidate_cursor','资格扫描游标'),col('claim_version','领取版本'),col('lease_until','租约截止'),col('last_error','最近异常')]
 const common=[col('order_no','订单号'),...owner,col('payment_no','原支付号'),col('paid_cents','支付本金',true),col('refund_cents','累计退款',true),col('fee_cents','剩余服务费',true),col('fee_bps','费率快照（bps）'),col('freeze_days','冻结期（天）'),col('status','结算状态')]
 return tab.value==='statements'?[...common,col('current_net_cents','当前净权利',true),col('simulated_payout_cents','原批次模拟支付',true),col('debt_offset_cents','原批次负债抵扣',true),col('batch_no','原模拟批次')]:common
})
const totals=[['principalCents','净本金'],['feeCents','剩余服务费'],['payableCents','冻结 / 待结算应付'],['simulatedCents','净模拟完成权利'],['debtCents','待抵扣退款负债']]
/** 账单单位始终是分；超出浏览器安全整数时明确拒绝近似展示。 */
function amount(value:unknown){if(value==null)return '—';if(typeof value==='number'&&!Number.isSafeInteger(value))return '超出浏览器精度';const raw=String(value);if(!/^-?\d+$/.test(raw))return '金额格式无效';const n=BigInt(raw),abs=n<0n?-n:n;return `${n<0n?'-':''}¥${(abs/100n).toLocaleString('zh-CN')}.${String(abs%100n).padStart(2,'0')}`}
const labels:Record<string,string>={FROZEN:'冻结 / 待资格',SIMULATED_COMPLETED:'模拟完成',PAYMENT:'支付入账',REFUND:'结算前退款',POST_SETTLEMENT_REFUND:'完成后退款冲正',PASS:'核对一致',DIFFERENCE:'存在差异'}
function display(row:DataRow,c:Column){return c.money?amount(row[c.key]):labels[String(row[c.key])]??text(row[c.key])}
async function refresh(){summary.clear();list.clear();if(permitted.value)await Promise.all([summary.reload(),list.reload()])}
async function change(delta:number){if(list.loading.value||page.value+delta<1)return;page.value+=delta;list.clear();await list.reload()}
async function applyFilter(){inputError.value='';if(filter.value&&!/^[A-Za-z0-9_-]{1,64}$/.test(filter.value)){inputError.value='商家编号限1至64位字母、数字、下划线或短横线';return}merchantFilter.value=filter.value;page.value=1;commandResult.value=null;await refresh()}
/** 手动命令不自动重试；响应未知时可查看任务和原事实账本，重复运行仍由后端唯一事实和租约防重。 */
async function command(kind:'run'|'reconcile'){
 if(!platform.value||commandBusy.value)return
 const g=generation;commandBusy.value=true;commandError.value='';commandResult.value=null
 try{const result=await api<DataRow>(`${base.value}/${kind}`,{method:'POST'});if(g!==generation)return;commandResult.value=result;await refresh()}
 catch(e){if(g===generation)commandError.value=(e instanceof Error?e.message:'请求结果尚未确认')+'；请刷新后台任务与账本核对执行情况。'}
 finally{if(g===generation)commandBusy.value=false}
}
watch(tab,()=>{page.value=1;list.clear();if(permitted.value)void list.reload()})
// 切商家、账号、权限模式时立即撤销旧资料；迟到响应由useLoad和会话代际共同隔离。
watch(()=>[session.epoch,props.platform,permitted.value],()=>{generation++;page.value=1;filter.value='';merchantFilter.value='';inputError.value='';commandError.value='';commandResult.value=null;commandBusy.value=false;if(!tabs.value.some(t=>t.key===tab.value))tab.value='statements';void refresh()})
onMounted(()=>void refresh())
onBeforeUnmount(()=>{generation++})
</script>

<template>
 <PageHeader :title="platform?'平台模拟结算':'商家结算账单'" description="按原支付与退款事实核算本金、服务费和应付，保留每次退款冲正与负债抵扣。">
  <template #actions><ElButton :disabled="!permitted" :loading="summary.loading.value||list.loading.value" @click="refresh">刷新账单</ElButton></template>
 </PageHeader>
 <p class="notice">模拟资金：结算批次与支付给商家的金额均为模拟记录，不发生银行转账。完成后退款会冲减净权利并形成待抵扣负债，原批次始终保留。</p>
 <EmptyState v-if="!permitted" :title="props.platform?'当前账号无平台资金权限':'请在顶部选择所属商家'"/>
 <template v-else>
  <section v-if="platform" class="panel" aria-label="平台查询与结算任务">
   <form class="toolbar-controls" @submit.prevent="applyFilter"><label for="settlement-merchant">商家编号筛选</label><ElInput id="settlement-merchant" v-model="filter" clearable maxlength="64" placeholder="留空查看全部商家" style="max-width:300px"/><ElButton native-type="submit" :disabled="commandBusy">应用筛选</ElButton></form>
   <Feedback :error="inputError"/>
   <div class="inline-actions settlement-commands"><ElButton type="primary" :loading="commandBusy" @click="command('run')">运行一页模拟结算</ElButton><ElButton :disabled="commandBusy" @click="command('reconcile')">核对本地结算账</ElButton></div>
   <p class="field-hint">手动任务处理全平台；商家筛选仅限制查询。核对范围为已导入成功事实与本地账本，不能代替渠道和订单完整对账。</p>
   <Feedback :error="commandError"/>
   <div v-if="commandResult" class="notice" role="status">
    <template v-if="commandResult.runNo">核对编号 {{text(commandResult.runNo)}}：{{labels[String(commandResult.result)]||text(commandResult.result)}}。<p v-if="Array.isArray(commandResult.differences)&&commandResult.differences.length">差异：{{text(commandResult.differences)}}</p></template>
    <template v-else>{{commandResult.state==='LEASED_BY_OTHER_INSTANCE'?'已有实例持有任务租约，请稍后刷新任务进度。':`当前页处理已返回；扫描事实 ${text(commandResult.importedOrVerified)} 条，检查资格 ${text(commandResult.candidateCount)} 笔。`}} 单据是否完成请以账单状态为准。</template>
   </div>
  </section>
  <section class="panel" aria-label="结算汇总">
   <h2 class="section-title">{{platform?(merchantFilter||'全部商家'):session.merchantId}} · 结算汇总</h2>
   <Feedback :loading="summary.loading.value" :error="summary.error.value"/>
   <dl v-if="summary.data.value&&!summary.error.value" class="detail-grid"><div v-for="total in totals" :key="total[0]"><dt>{{total[1]}}</dt><dd>{{amount(summary.data.value[total[0]||''])}}</dd></div></dl>
   <p class="field-hint">净本金 − 剩余服务费 = 冻结 / 待结算应付 + 净模拟完成权利。负债单列，后续符合资格的结算优先抵扣。</p>
  </section>
  <section class="panel" aria-label="结算明细">
   <ElTabs v-model="tab"><ElTabPane v-for="item in tabs" :key="item.key" :label="item.label" :name="item.key"/></ElTabs>
   <p class="muted">{{tab==='ledger'?'每条账本只追加，负数表示退款或转出。':tab==='statements'?'账单显示当前退款后权利，同时保留原模拟批次金额。':tab==='tasks'?'资金与资格游标会周期归零；租约失效后由下一实例接管。':'单据费率与冻结期采用首次入账时的快照。'}}</p>
   <Feedback :loading="list.loading.value" :error="list.error.value"/>
   <template v-if="!list.loading.value&&!list.error.value">
    <EmptyState v-if="!items.length" title="当前页暂无结算记录"/>
    <ElTable v-else :data="items" class="settlement-table"><ElTableColumn v-for="column in columns" :key="column.key" :label="column.label" :min-width="column.key.includes('no')||column.key==='entry_key'?240:column.money?170:150"><template #default="{row}"><span :class="{'settlement-negative':column.money&&String(row[column.key]).startsWith('-')}">{{display(row,column)}}</span></template></ElTableColumn></ElTable>
   </template>
   <Pagination :page="page" :count="items.length" :has-next="!!list.data.value?.hasMore" :loading="list.loading.value" @change="change"/>
  </section>
 </template>
</template>

<style scoped>
.settlement-commands{margin-top:18px}.settlement-negative{color:#a62c32}.settlement-table{width:100%}.section-title{margin-top:0}.detail-grid dd{font-size:19px;font-weight:600;font-variant-numeric:tabular-nums}.notice p{overflow-wrap:anywhere}.toolbar-controls{gap:12px}
@media(max-width:650px){.settlement-commands .el-button{width:100%;margin-left:0}.detail-grid dd{font-size:17px}}
</style>
