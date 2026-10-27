<script setup lang="ts">
import { computed,reactive,ref,onUnmounted,onMounted,watch } from 'vue'
import { useRouter } from 'vue-router'
import { api,useCommand,money,can,PageHeader,Feedback,EmptyState,useLoad } from '@tradeflow/ui'
import { cart,pathNo } from '../state'
let alive=true;onUnmounted(()=>{alive=false})
const router=useRouter(),command=useCommand('user-place-order'),validation=ref('')
const legacyChecked=ref(false)
const recoveryNotice=ref(''),groupCommand=useCommand('user-trade-group')
const groups=computed(()=>[...new Set(cart.value.map(x=>x.merchantId))].sort())
const shipping=useLoad<{merchantId:string;feeCents:number;version:number}[]>(signal=>Promise.all(groups.value.map(merchantId=>api<{merchantId:string;feeCents:number;version:number}>('/shipping-quotes?merchantId='+encodeURIComponent(merchantId),{signal}))))
const addresses=useLoad<{id:string;recipient:string;phone:string;region?:string;address:string}[]>(async signal=>{const result=await api<unknown>('/users/me/addresses',{signal});return (Array.isArray(result)?result:(result as {items?:unknown[]})?.items||[]) as {id:string;recipient:string;phone:string;region?:string;address:string}[]})
watch(()=>groups.value.join(','),()=>shipping.reload())
onMounted(async()=>{await shipping.reload();await addresses.reload();await groupCommand.refreshPending('create')})
async function recoverGroup(){const result=await groupCommand.recover<{groupNo:string}>('create',key=>api('/trade-groups/requests/by-key',{key}));if(result&&alive){await groupCommand.reset();if(!alive)return;await router.push('/trade-groups/'+pathNo(result.groupNo))}}
function useAddress(event:Event){const entry=addresses.data.value?.find(x=>x.id===(event.target as HTMLSelectElement).value);if(entry)Object.assign(address,{receiverName:entry.recipient,receiverPhone:entry.phone,region:entry.region||'',detailAddress:entry.address})}
onMounted(async()=>{await command.refreshPending('place');legacyChecked.value=true})
// 查询不携带原地址；未受理封键只有显式点击后才发起，服务端已受理时直接恢复原订单。
async function recover(seal=false){
 recoveryNotice.value=''
 const result=await command.recover<{businessNo:string;state:string;resultCode:string|null}>('place',async key=>{
  if(seal)return api('/orders/requests/seal',{method:'POST',key,body:{reason:'用户显式阻止尚未受理的原下单请求'}})
  const found=await api<{found:boolean;progress:{businessNo:string;state:string;resultCode:string|null}|null}>('/orders/requests/by-key',{key})
  return found.found?found.progress:null
 })
 if(!alive)return
 if(result){await command.reset();await command.refreshPending('place');if(!alive)return;if(result.resultCode==='REQUEST_SEALED'){recoveryNotice.value='原请求已被安全阻止，不会再创建订单。可以核对购物袋后重新提交。'}else await router.push(`/orders/requests/${pathNo(result.businessNo)}`)}
}
const address=reactive({receiverName:'',receiverPhone:'',region:'',detailAddress:''})
const total=computed(()=>cart.value.reduce((sum,line)=>sum+line.quantity*line.expectedUnitPriceCents,0)+(shipping.data.value||[]).reduce((sum,row)=>sum+row.feeCents,0))
// 请求使用持久幂等键；202仅跳转创建进度，地址不进入购物袋或持久命令正文。
async function submit(){validation.value='';if(!legacyChecked.value||command.pending.value){validation.value='请先确认或安全阻止上一笔原下单请求，再创建新交易组';return}if(!cart.value.length||cart.value.length>20||groups.value.length>10||cart.value.some(x=>!Number.isInteger(x.quantity)||x.quantity<1||x.quantity>99)){validation.value='最多20种规格、10个商家，每种1至99件';return}
 if(shipping.error.value||shipping.loading.value||!shipping.data.value||shipping.data.value.length!==groups.value.length){validation.value='请先刷新并确认各商家运费';return}
 const body={orders:groups.value.map(merchantId=>({merchantId,expectedShippingCents:shipping.data.value!.find(x=>x.merchantId===merchantId)!.feeCents,items:cart.value.filter(x=>x.merchantId===merchantId).map(({skuId,quantity,expectedUnitPriceCents})=>({skuId,quantity,expectedUnitPriceCents})),address:{...address}}))}
 const result=await groupCommand.run<{groupNo:string}>('create',body,key=>api('/trade-groups',{method:'POST',body,key}));if(alive)await groupCommand.refreshPending('create');if(result&&alive){await groupCommand.reset();if(!alive)return;cart.value=[];await router.push(`/trade-groups/${pathNo(result.groupNo)}`)}
}

</script>
<template><section><PageHeader title="购物袋" description="确认规格与数量，填写本次订单收货信息。"/><div v-if="groupCommand.pending.value" class="panel"><h2>恢复原分商家结算请求</h2><p>未查到仍保持未知，保留原请求，避免重复创建。</p><Feedback :error="groupCommand.error.value"/><button :disabled="groupCommand.busy.value" @click="recoverGroup">查询原交易组</button></div><div v-if="command.pending.value" class="panel"><h2>恢复上一次下单请求</h2><p>检测到原请求记录。即使页面刷新丢失地址，也可按原键查询已受理订单。</p><p class="notice">暂时查不到不代表失败。“阻止未受理请求”会安全封住原键；如果订单已经受理，则返回原订单，不会将它取消。</p><Feedback :error="command.error.value"/><div class="actions"><button :disabled="command.busy.value" @click="recover()">查询原请求结果</button><button :disabled="command.busy.value" @click="recover(true)">查询并阻止未受理的原请求</button></div></div><p v-if="recoveryNotice" class="notice" role="status">{{recoveryNotice}}</p><EmptyState v-if="!cart.length" title="购物袋还空着" description="选好商品与规格后，在这里完成结算。"><RouterLink class="button primary" to="/">去选购商品</RouterLink></EmptyState><form v-else class="two-col checkout" @submit.prevent="submit"><div><div class="panel"><h2>商品清单</h2><article v-for="(line,index) in cart" :key="line.skuId" class="cart-line"><div><h3>{{line.productName}}</h3><p class="muted">{{line.merchantId}} · {{line.specification}}</p><strong>{{money(line.expectedUnitPriceCents)}}</strong></div><label :for="`qty-${index}`">数量<input :id="`qty-${index}`" v-model.number="line.quantity" type="number" min="1" max="99" required/></label><button type="button" class="text-button" @click="cart.splice(index,1)">移除</button></article></div><div class="panel"><h2>收货信息</h2><Feedback :error="addresses.error.value"/><label for="saved-address">从地址簿选择</label><select id="saved-address" @change="useAddress"><option value="">手动填写本次地址</option><option v-for="a in addresses.data.value" :key="a.id" :value="a.id">{{a.recipient}} · {{a.address}}</option></select><RouterLink to="/account">维护地址簿</RouterLink><p class="muted small">仅用于本次下单，不保存在浏览器本地。</p><div class="field-grid"><div><label for="receiver">收货人</label><input id="receiver" v-model.trim="address.receiverName" maxlength="64" autocomplete="name" required/></div><div><label for="phone">联系电话</label><input id="phone" v-model.trim="address.receiverPhone" maxlength="32" type="tel" autocomplete="tel" required/></div></div><label for="region">省 / 市 / 区</label><input id="region" v-model.trim="address.region" maxlength="255" required/><label for="address">详细地址</label><textarea id="address" v-model.trim="address.detailAddress" maxlength="500" required/></div></div><aside class="panel summary"><span class="eyebrow">订单确认</span><h2>本次合计</h2><strong class="large-price">{{money(total)}}</strong><p class="muted">{{groups.length}} 个商家，共 {{cart.length}} 种规格。每商家独立支付。</p><Feedback :error="shipping.error.value" :loading="shipping.loading.value"/><p v-for="fee in shipping.data.value" :key="fee.merchantId">{{fee.merchantId}} 运费 {{money(fee.feeCents)}}</p><button type="button" @click="shipping.reload()">刷新运费</button><Feedback :error="validation || groupCommand.error.value" :loading="groupCommand.busy.value"/><button class="primary full" :disabled="!legacyChecked || command.pending.value || groupCommand.busy.value || shipping.loading.value || !can('SHOP')">按商家创建订单</button><p class="small muted">提交后可查看创建进度。受理中请保留原请求，勿重复下单。</p></aside></form></section></template>
