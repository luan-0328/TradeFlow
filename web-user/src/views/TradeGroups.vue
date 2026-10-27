<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {api,useLoad,PageHeader,Feedback} from '@tradeflow/ui'
const page=ref(1)
interface Group {groupNo:string;state:string;children:{orderNo:string;merchantId:string;status:string}[]}
const groups=useLoad<Group[]>(signal=>api(`/trade-groups?page=${page.value}&pageSize=20`,{signal}))
onMounted(()=>groups.reload())
</script>
<template><section><PageHeader title="我的分商家交易" description="每个交易组包含独立付款和履约的商家订单。"/><Feedback :error="groups.error.value" :loading="groups.loading.value"/><article v-for="group in groups.data.value" :key="group.groupNo" class="panel"><h2>{{group.groupNo}}</h2><p>{{group.state}} · {{group.children.length}} 个商家订单</p><RouterLink class="button" :to="'/trade-groups/'+group.groupNo">查看各商家进度</RouterLink></article><p v-if="groups.data.value?.length===0">当前页没有交易组。</p><div class="pagination"><button :disabled="page<=1||groups.loading.value" @click="page--;groups.reload()">上一页</button><span>第{{page}}页</span><button :disabled="(groups.data.value?.length??0)<20||groups.loading.value" @click="page++;groups.reload()">下一页</button></div></section></template>
