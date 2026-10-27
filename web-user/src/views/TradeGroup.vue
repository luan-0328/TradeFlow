<script setup lang="ts">
import {onMounted} from 'vue'
import {useRoute} from 'vue-router'
import {api,useLoad,money,PageHeader,Feedback,StatusBadge} from '@tradeflow/ui'
import {usePoll,pathNo} from '../state'
interface Child {merchantId:string;orderNo:string;status:string;resultCode?:string;amountCents?:number;shippingCents:number}
interface Group {groupNo:string;state:string;children:Child[]}
const route=useRoute(),group=useLoad<Group>(signal=>api(`/trade-groups/${pathNo(String(route.params.no))}`,{signal}))
const poll=usePoll(async signal=>{const data=await api<Group>(`/trade-groups/${pathNo(String(route.params.no))}`,{signal});await group.reload();return !data.children.some(c=>['QUOTING','CREATING','CLOSING'].includes(c.status))})
onMounted(async()=>{await group.reload();if(group.data.value?.children.some(c=>['QUOTING','CREATING','CLOSING'].includes(c.status)))poll.start()})
</script>
<template><section><PageHeader title="分商家交易进度" description="各商家订单独立付款、履约和售后。部分订单失败不会取消其他已经成立的订单。"><template #actions><button @click="group.reload()">刷新</button><RouterLink class="button" to="/orders">我的订单</RouterLink></template></PageHeader><Feedback :error="group.error.value" :loading="group.loading.value"/><p v-if="poll.message.value" role="status">{{poll.message.value}}</p><div v-if="group.data.value" class="panel"><p>交易组 {{group.data.value.groupNo}} · {{group.data.value.state}}</p><article v-for="child in group.data.value.children" :key="child.orderNo" class="panel"><h2>{{child.merchantId}}</h2><StatusBadge :status="child.status"/><p>{{child.orderNo}}</p><p v-if="child.amountCents">应付 {{money(child.amountCents)}}（含运费 {{money(child.shippingCents)}}）</p><p v-if="child.resultCode">进度：{{child.resultCode}}</p><RouterLink class="button primary" :to="['QUOTING','CREATING','REJECTED','CANCELLED'].includes(child.status)?`/orders/requests/${pathNo(child.orderNo)}`:`/orders/${pathNo(child.orderNo)}`">查看此商家订单</RouterLink></article></div></section></template>
