<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue'
import {api,PageHeader,Feedback,EmptyState,StatusBadge,useCommand,session,money,type DataRow} from '@tradeflow/ui'
import {useRows,text,permits} from '../data'
import Pagination from '../components/Pagination.vue'
import RecordDetail from '../components/RecordDetail.vue'
const props=defineProps<{funds?:boolean}>()
const source=ref('order-tasks'),service=ref('order'),kind=ref('outbox')
const path=computed(()=>props.funds?'/api/v1/admin/payment-ledger':source.value==='messages'?`/api/v1/admin/${service.value}-messages/${kind.value}`:'/api/v1/admin/'+source.value)
const list=useRows(()=>path.value,props.funds?'page':'array')
const command=useCommand('admin-recovery'),selected=ref<DataRow|null>(null),details=ref(false),modal=ref(false),reason=ref(''),localError=ref(''),notice=ref('')
let generation=0
onBeforeUnmount(()=>{generation++})
/** 延期/隔离记录同时含event_id，重试接口要求各自记录主键，不能按字段存在顺序误取事件号。 */
const id=(r:DataRow)=>text(source.value==='messages'?r[kind.value==='outbox'?'event_id':kind.value==='deferred'?'deferred_id':'failure_id']:source.value==='order-tasks'?r.taskNo:source.value==='aftersale-tasks'?r.aftersale_no:r.source_no)
const columns=computed(()=>props.funds?[
 ['business_no','业务编号'],['payment_no','支付编号'],['refund_no','退款编号'],['entry_type','账务类型'],['amount_cents','金额（元）'],['created_at','记账时间']
]:source.value==='order-tasks'?[
 ['taskNo','任务编号'],['businessNo','业务编号'],['state','状态'],['attemptCount','尝试次数'],['lastError','最近异常']
]:source.value==='messages'?[
 [kind.value==='outbox'?'event_id':kind.value==='deferred'?'deferred_id':'failure_id','原消息编号'],['status','状态'],['attempt_count','尝试次数'],['next_retry_at','下次重试'],['last_error','最近异常']
]:source.value==='aftersale-tasks'?[
 ['aftersale_no','售后编号'],['phase','执行阶段'],['status','状态'],['attempts','尝试次数'],['last_error','最近异常']
]:[['source_no','原业务来源'],['state','状态'],['step','执行阶段'],['attempt_count','尝试次数'],['last_error','最近异常']])
const displayRows=computed(()=>source.value==='aftersale-tasks'&&!props.funds?list.items.value.slice((list.page.value-1)*20,list.page.value*20):list.items.value)
const hasNext=computed(()=>source.value==='aftersale-tasks'&&!props.funds?list.page.value*20<list.items.value.length:list.hasNext.value)
function inspect(r:DataRow){selected.value=r;details.value=true}
function open(r:DataRow){generation++;selected.value=r;modal.value=true;reason.value='';localError.value=''}
function close(){generation++;modal.value=false;details.value=false;selected.value=null}
/** 恢复只提交原任务编号，不编辑载荷、不生成新退款号；按钮由服务器allowedActions决定，返回READY不代表执行完成。 */
async function submit(){const r=selected.value;if(!r||!permits(r,'RETRY'))return;localError.value='';if(!reason.value.trim()){localError.value='请填写恢复原因';return}const g=generation,target=id(r);if(target==='—'){localError.value='记录缺少恢复编号，请刷新核对';return}const url=path.value+'/'+encodeURIComponent(target)+'/retry',body:DataRow={reason:reason.value.trim()};if(source.value==='refund-tasks')body.newAttempt=false;const result=await command.run('retry:'+url,body,key=>api(url,{method:'POST',body,key}));if(g!==generation||result===undefined)return;await command.reset();if(g!==generation)return;modal.value=false;notice.value='恢复请求已返回，后台将按原业务编号继续执行；请刷新列表核对，当前不代表业务终态。';await list.reload()}
async function change(delta:number){if(source.value==='aftersale-tasks'&&!props.funds)list.page.value+=delta;else await list.change(delta)}
watch(path,()=>{close();list.reset();notice.value='';void list.reload()})
watch(()=>session.user?.userId,()=>{close();list.clear();notice.value=''})
onMounted(()=>list.reload())
</script>
<template><PageHeader :title="funds?'资金流水':'消息与恢复'" :description="funds?'只读查看已形成的真实支付账务记录，金额均以整数分记录。':'核对消息和恢复义务，受控重启原任务，不手工改资金或库存终态。'"/><p v-if="notice" class="notice" role="status">{{notice}}</p><section class="panel"><div class="toolbar"><div v-if="!funds" class="toolbar-controls"><label for="recovery-source">记录类型</label><ElSelect id="recovery-source" v-model="source"><ElOption label="订单创建恢复" value="order-tasks"/><ElOption label="退款恢复" value="refund-tasks"/><ElOption label="退货入库恢复" value="return-stock-tasks"/><ElOption label="售后恢复" value="aftersale-tasks"/><ElOption label="消息恢复" value="messages"/></ElSelect><template v-if="source==='messages'"><label for="message-service">业务服务</label><ElSelect id="message-service" v-model="service"><ElOption label="订单" value="order"/><ElOption label="支付" value="payment"/><ElOption label="售后" value="aftersale"/></ElSelect><label for="message-kind">消息类别</label><ElSelect id="message-kind" v-model="kind"><ElOption label="待发布 / 已发布" value="outbox"/><ElOption label="延期消费" value="deferred"/><ElOption label="隔离失败" value="failure"/></ElSelect></template></div><h2 v-else>支付账务流水</h2><ElButton :loading="list.loading.value" @click="list.reload">刷新列表</ElButton></div><p v-if="source==='aftersale-tasks'&&!funds" class="field-hint">服务端最多返回100条人工处理任务；以下为该返回集合的分页，不代表全部历史任务。</p><Feedback :loading="list.loading.value" :error="list.error.value"/><template v-if="!list.loading.value&&!list.error.value"><EmptyState v-if="!displayRows.length" title="当前页暂无记录"/><ElTable v-else :data="displayRows"><ElTableColumn v-for="column in columns" :key="column[0]" :label="column[1]" :min-width="column[0]?.includes('no')||column[0]?.includes('id')?240:130"><template #default="{row}"><StatusBadge v-if="column[0]==='state'||column[0]==='status'" :status="text(row[column[0]])"/><span v-else>{{column[0]==='amount_cents'?money(Number(row[column[0]])):text(row[column[0]||''])}}</span></template></ElTableColumn><ElTableColumn label="操作" min-width="210" fixed="right"><template #default="{row}"><ElButton @click="inspect(row)">记录详情</ElButton><ElButton v-if="!funds&&permits(row,'RETRY')" @click="open(row)">受控重试</ElButton></template></ElTableColumn></ElTable></template><Pagination :page="list.page.value" :count="displayRows.length" :total="list.total.value" :has-next="hasNext" :loading="list.loading.value" @change="change"/></section>
<ElDialog :model-value="details" title="原始业务记录" width="800px" @close="details=false"><RecordDetail v-if="selected" :value="selected"/></ElDialog><ElDialog :model-value="modal" title="受控恢复原任务" width="620px" @close="close"><ElForm label-position="top" @submit.prevent="submit"><p class="notice">原编号：{{selected?id(selected):''}}<br>重试保留原资源和执行义务；未知退款结果不会另建新退款执行。</p><ElFormItem label="恢复原因" required><ElInput v-model="reason" type="textarea" maxlength="500" :rows="4"/></ElFormItem><Feedback :error="localError||command.error.value"/><ElButton native-type="submit" type="primary" :loading="command.busy.value">提交恢复请求</ElButton><ElButton @click="close">关闭</ElButton></ElForm></ElDialog></template>

