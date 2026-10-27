<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {api,useLoad,useCommand,PageHeader,Feedback} from '@tradeflow/ui'
const page=ref(1),aftersaleNo=ref(''),reason=ref(''),command=useCommand('user-disputes')
interface Dispute {dispute_no:string;aftersale_no:string;status:string;reason:string;decision?:string;decision_reason?:string}
const list=useLoad<{items:Dispute[];total:number}>(signal=>api(`/disputes?page=${page.value}&pageSize=20`,{signal}))
async function apply(){const body={aftersaleNo:aftersaleNo.value.trim(),reason:reason.value.trim()};const result=await command.run('apply:'+body.aftersaleNo,body,key=>api('/disputes',{method:'POST',body,key}));if(result!==undefined){await command.reset();reason.value='';await list.reload()}}
onMounted(()=>list.reload())
</script>
<template><section><PageHeader title="申请平台介入" description="关联原售后说明争议，原证据仍在售后材料中查看。平台裁定不等于退款已经到账。"/><form class="panel" @submit.prevent="apply"><label for="dispute-sale">原售后编号</label><input id="dispute-sale" v-model="aftersaleNo" pattern="as_[a-f0-9]{32}" required/><label for="dispute-reason">申请依据</label><textarea id="dispute-reason" v-model="reason" maxlength="1000" required/><Feedback :error="command.error.value"/><button class="primary" :disabled="command.busy.value">提交平台介入申请</button></form><Feedback :error="list.error.value" :loading="list.loading.value"/><article v-for="row in list.data.value?.items" :key="row.dispute_no" class="panel"><h2>{{row.dispute_no}}</h2><p>{{row.status}} · {{row.reason}}</p><p v-if="row.decision">平台裁定：{{row.decision}} · {{row.decision_reason}}</p><RouterLink :to="'/aftersales/'+row.aftersale_no">查看原售后及材料</RouterLink></article><div class="pagination"><button :disabled="page<=1" @click="page--;list.reload()">上一页</button><span>第{{page}}页 · 共{{list.data.value?.total??'—'}}条</span><button :disabled="page*20>=(list.data.value?.total??0)" @click="page++;list.reload()">下一页</button></div></section></template>
