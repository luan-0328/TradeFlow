<script setup lang="ts">
import {onMounted,onUnmounted,watch} from 'vue'
import {useRouter} from 'vue-router'
import {api,useCommand,Feedback,type Payment,type Aftersale} from '@tradeflow/ui'
import {pathNo} from '../state'

const props=defineProps<{orderNo:string;kind:'payment'|'aftersale';activity?:boolean}>()
const emit=defineEmits<{payment:[value:Payment]}>()
const router=useRouter()
const operation=props.kind==='payment'?'pay':'create'
const command=useCommand(props.kind==='payment'?`user-order:${props.orderNo}`:`user-aftersale-create:${props.orderNo}`)
let alive=true
/** 仅检查当前表单日记；首次挂载、提交结束、切回窗口及其他标签页变化均不发业务请求。 */
function refresh(){if(alive)void command.refreshPending(operation)}
function storage(event:StorageEvent){if(event.key?.startsWith('tradeflow.command.'))refresh()}
onMounted(()=>{refresh();window.addEventListener('focus',refresh);window.addEventListener('storage',storage)})
onUnmounted(()=>{alive=false;window.removeEventListener('focus',refresh);window.removeEventListener('storage',storage)})
watch(()=>props.activity,value=>{if(!value)refresh()})
/** 只按原键GET；未找到/超时保留原日记，不补造申请或付款。迟到结果不能跳转新账号页面。 */
async function recover(){
 if(props.kind==='payment'){
  const result=await command.recover<Payment>(operation,key=>api<Payment|null>(`/payments/requests/by-key?orderNo=${pathNo(props.orderNo)}`,{key}))
  if(result&&alive)emit('payment',result)
 }else{
  const result=await command.recover<Aftersale>(operation,key=>api<Aftersale|null>(`/aftersales/requests/by-key?orderNo=${pathNo(props.orderNo)}`,{key}))
  if(!result||!alive)return
  await command.reset()
  if(!alive)return
  await router.push(`/aftersales/${pathNo(result.aftersaleNo)}`)
 }
}
</script>

<template><section v-if="command.pending.value" class="panel" aria-label="原请求恢复">
 <h2>{{kind==='payment'?'找回原支付记录':'找回原售后申请'}}</h2>
 <p class="small muted">{{kind==='payment'?'本机留有这笔订单的原支付请求编号，可查询已受理状态。':'本机留有这笔订单的原申请编号，表单或证据选择丢失后仍可查询已受理申请。'}}</p>
 <p class="small muted">查询不会发起支付或退款；暂时查不到也不表示失败，请保留原键。</p>
 <Feedback :error="command.error.value" :loading="command.busy.value"/>
 <button class="secondary" :disabled="command.busy.value||activity" @click="recover">{{kind==='payment'?'查询原支付请求':'查询原售后申请'}}</button>
</section></template>
