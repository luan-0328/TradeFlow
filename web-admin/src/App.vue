<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {useRoute} from 'vue-router'
import {Layers,Package,ClipboardList,RefreshCw,Users,Wallet,ShieldCheck,LogOut} from 'lucide-vue-next'
import {LoginPanel,errorText,session,selectMerchant,initSession,logout,can,Feedback,EmptyState} from '@tradeflow/ui'
import {navigation} from './router'
import './style.css'
const route=useRoute()
const available=computed(()=>navigation.filter(n=>can(n.cap)))
const authorized=computed(()=>!route.meta.cap||can(String(route.meta.cap)))
const icons=[Package,ClipboardList,RefreshCw,Users,Wallet,ShieldCheck,Layers]
const sessionError=ref('')
onMounted(async()=>{try{await initSession()}catch(error){sessionError.value=errorText(error)}})
</script>
<template>
 <div v-if="!session.ready" class="session-wait"><Feedback loading /></div>
 <div v-else-if="!session.user" class="login-shell"><div class="login-intro"><Layers :size="36" aria-hidden="true"/><span class="eyebrow">TRADEFLOW · CONSOLE</span><h1>每一笔交易，<br>都有清晰的下一步。</h1><p>商家履约、售后协作与平台恢复，统一在此处理。</p><span class="login-note">使用已有账号登录 · 权限以服务器最新授权为准</span></div><div><Feedback :error="sessionError"/><LoginPanel admin /></div></div>
 <div v-else class="admin-shell">
  <aside class="sidebar"><RouterLink to="/" class="brand"><Layers :size="26" aria-hidden="true"/><span>TradeFlow<small>交易协作后台</small></span></RouterLink>
   <div class="side-kicker">WORKSPACE</div>
   <nav aria-label="后台业务导航"><template v-for="(item,index) in navigation" :key="item.path"><RouterLink v-if="available.includes(item)" :to="item.path"><component :is="icons[index%icons.length]" :size="19" aria-hidden="true"/><span>{{item.label}}</span></RouterLink></template></nav>
   <div class="sidebar-foot"><ShieldCheck :size="18" aria-hidden="true"/><span>真实记录 · 受控操作<small>资金与库存状态由服务端确认</small></span></div>
  </aside>
  <div class="workspace"><header class="topbar"><span>业务工作台 <span class="muted"> / {{navigation.find(n=>n.path===route.path)?.label||'概览'}}</span></span><div class="account"><label v-if="session.capabilities?.merchantIds.length" for="active-merchant">当前商家<select id="active-merchant" :value="session.merchantId" @change="selectMerchant(($event.target as HTMLSelectElement).value)"><option value="" disabled>请选择商家</option><option v-for="id in session.capabilities.merchantIds" :key="id" :value="id">{{id}}</option></select></label><span>{{session.user.displayName || session.user.username}}</span><button class="plain-button" @click="logout"><LogOut :size="16" aria-hidden="true"/>退出账号</button></div></header>
   <main id="main-content"><RouterView v-if="authorized" :key="`${session.user.userId}:${session.epoch}:${route.path}`"/><EmptyState v-else title="当前账号没有此模块的权限" description="请从左侧选择可用业务模块，或联系平台管理员核对授权。"/></main>
  </div>
 </div>
</template>
