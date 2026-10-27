<script setup lang="ts">
import {onMounted} from 'vue'
import {api,useLoad,time,statusLabel,Feedback} from '@tradeflow/ui'
const props=defineProps<{orderNo:string}>()
const rows=useLoad<{toStatus:string;eventType:string;createdAt:string;reason:string}[]>(signal=>api('/orders/'+encodeURIComponent(props.orderNo)+'/timeline',{signal}))
onMounted(()=>rows.reload())
</script>
<template><section class="panel"><h2>订单进度记录</h2><Feedback :error="rows.error.value" :loading="rows.loading.value"/><ol><li v-for="(row,index) in rows.data.value" :key="index"><strong>{{statusLabel(row.toStatus)}}</strong> · {{time(row.createdAt)}}<p>{{row.reason}}</p></li></ol></section></template>
