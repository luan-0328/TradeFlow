<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {api,useLoad,useCommand,session,cents,money,PageHeader,Feedback} from '@tradeflow/ui'
const fee=ref(''),command=useCommand('admin-shipping-rule')
const rule=useLoad<{feeCents:number;version:number}>(signal=>api('/shipping-quotes?merchantId='+encodeURIComponent(session.merchantId),{signal}))
async function load(){await rule.reload();if(rule.data.value)fee.value=(rule.data.value.feeCents/100).toFixed(2)}
async function save(){if(!rule.data.value)return;let feeCents:number;try{feeCents=/^0(?:\.0{1,2})?$/.test(fee.value.trim())?0:cents(fee.value)}catch{return}const body={feeCents,version:rule.data.value.version};const value=await command.run('update',body,key=>api('/merchant/shipping-rule',{method:'PUT',body,key}));if(value!==undefined){await command.reset();await load()}}
onMounted(load)
</script>
<template><section><PageHeader title="商家运费规则" description="每个子订单收取固定运费，历史订单保留原规则快照。退款按购买件实付包含分摊运费。"/><form class="panel" @submit.prevent="save"><p>当前商家：{{session.merchantId||'请先选择商家'}}</p><Feedback :error="rule.error.value||command.error.value" :loading="rule.loading.value"/><p v-if="rule.data.value">当前运费 {{money(rule.data.value.feeCents)}} · 版本{{rule.data.value.version}}</p><label for="shipping-fee">每单运费（元，可为0，最高10000元）</label><input id="shipping-fee" v-model="fee" type="number" min="0" max="10000" step="0.01" required/><button class="primary" :disabled="command.busy.value||!rule.data.value">保存规则</button><button type="button" @click="load">重新读取</button></form></section></template>
