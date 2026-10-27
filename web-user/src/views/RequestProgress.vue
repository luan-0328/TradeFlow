<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { useRoute } from 'vue-router'
import { api,useCommand,Feedback,PageHeader,StatusBadge } from '@tradeflow/ui'
import { pathNo,usePoll } from '../state'
const no=String(useRoute().params.no),command=useCommand(`user-order:${no}`),reason=ref('');const progress=ref<{businessNo:string;state:string;resultCode:string;orderQueryUrl:string;recoveryState:string;allowedActions?:string[]}|null>(null)
const poll=usePoll(async signal=>{const p=await api<NonNullable<typeof progress.value>>(`/orders/requests/${pathNo(no)}`,{signal});if(signal.aborted)return true;progress.value=p;return !!p.orderQueryUrl || ['REJECTED','CANCELLED','CLOSED','FAILED'].includes(p.state)})
onMounted(poll.start)
async function cancel(){if(!reason.value.trim())return;const body={reason:reason.value.trim()};const result=await command.run('cancel',body,key=>api(`/orders/${pathNo(no)}/cancel`,{method:'POST',body,key}));if(result!==undefined)poll.start()}
</script>
<template><section><PageHeader title="订单创建进度" description="请求已受理，报价与库存预占正在由服务处理。"/><div class="panel narrow"><span class="eyebrow">订单请求</span><p class="mono wrap">{{no}}</p><StatusBadge v-if="progress" :status="progress.state"/><p v-if="progress?.resultCode">处理结果：{{progress.resultCode}}</p><p v-if="progress?.recoveryState">恢复进度：{{progress.recoveryState}}</p><Feedback :error="poll.message.value || command.error.value" :loading="poll.running.value"/><p class="muted">受理并不表示创建或支付成功。查询只读取状态，最多自动查询30次。</p><div class="actions"><button v-if="poll.running.value" @click="poll.stop">停止查询</button><button v-else @click="poll.start">重新查询</button><RouterLink v-if="progress?.orderQueryUrl" class="button primary" :to="`/orders/${pathNo(no)}`">查看订单</RouterLink><RouterLink class="button secondary" to="/orders">我的订单</RouterLink></div><form v-if="progress?.allowedActions?.includes('CANCEL_CREATING')" @submit.prevent="cancel"><label for="creation-cancel">取消原因</label><textarea id="creation-cancel" v-model.trim="reason" maxlength="255" required/><button class="danger-button" :disabled="command.busy.value">取消创建中的订单</button></form></div></section></template>
