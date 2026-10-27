<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue'
import {ApiError,login,register,sendRegistrationCode,errorText,session,logout} from './client'
defineProps<{admin?:boolean}>()
const username=ref(''),password=ref(''),displayName=ref(''),code=ref('')
const registering=ref(false),busy=ref(false),sending=ref(false),error=ref(''),notice=ref('')
const now=ref(Date.now()),deadline=ref(0)
const normalized=()=>username.value.trim().toLowerCase()
const storageKey=()=>`tradeflow.registration.cooldown.${normalized()}`
const remaining=computed(()=>Math.max(0,Math.ceil((deadline.value-now.value)/1000)))
const timer=setInterval(()=>{now.value=Date.now()},1000)
onUnmounted(()=>clearInterval(timer))
watch(username,()=>{
  code.value='';error.value='';notice.value='';deadline.value=0
  try{const saved=Number(sessionStorage.getItem(storageKey()));if(Number.isFinite(saved))deadline.value=saved}catch{/* 后端仍执行真实限额。 */}
})
function cooldown(seconds:number){
  now.value=Date.now();deadline.value=now.value+seconds*1000
  try{sessionStorage.setItem(storageKey(),String(deadline.value))}catch{/* 无存储时只影响刷新后的展示。 */}
}
/** 发信和注册分别禁用重复提交；超时不自动重发，迟到响应只应用到同一邮箱及当前模式。 */
async function sendCode(){
  if(sending.value||busy.value||remaining.value)return
  const email=normalized()
  if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)){error.value='请先输入有效的邮箱地址';return}
  sending.value=true;error.value='';notice.value=''
  try{
    const result=await sendRegistrationCode(email)
    if(normalized()!==email||!registering.value)return
    cooldown(result.retryAfterSeconds);notice.value='验证码已提交发送，5分钟内有效，请检查收件箱及垃圾邮件。'
  }catch(reason){
    if(normalized()!==email||!registering.value)return
    if(reason instanceof ApiError){
      if(reason.retryAfterSeconds>0)cooldown(reason.retryAfterSeconds)
      else if(reason.uncertain)cooldown(60)
    }
    error.value=errorText(reason)
  }finally{sending.value=false}
}
/** 密码只保留到本次提交结束；邮箱注册只授普通用户，不自动建立会话。 */
async function submit(){
  if(busy.value||sending.value)return
  busy.value=true;error.value='';notice.value=''
  try{
    if(registering.value){
      await register(normalized(),password.value,displayName.value,code.value)
      registering.value=false;code.value='';notice.value='注册成功，请使用邮箱和密码登录'
    }else await login(username.value,password.value)
  }catch(reason){error.value=errorText(reason)}finally{password.value='';busy.value=false}
}
function switchMode(){registering.value=!registering.value;password.value='';code.value='';error.value='';notice.value=''}
</script>
<template><section class="tf-login">
<span class="tf-eyebrow">TRADEFLOW ACCOUNT</span><h2>{{admin?'登录管理工作台':registering?'邮箱注册':'登录 TradeFlow'}}</h2>
<p class="tf-login-description">{{admin?'使用已分配商家或平台权限的账号登录。':'登录后查看订单、跟踪履约和申请售后。'}}</p>
<template v-if="session.user"><p>当前账号：{{session.user.displayName||session.user.username}}</p><p>该账号暂未获得此入口的操作权限，请联系平台管理员。</p><button class="tf-button" @click="logout">退出当前账号</button></template>
<form v-else @submit.prevent="submit" :aria-busy="busy||sending">
<label>{{registering?'邮箱':'邮箱 / 历史用户名'}}<input v-model.trim="username" :type="registering?'email':'text'" autocomplete="username" required maxlength="254" :disabled="busy||sending"/></label>
<template v-if="registering">
<label>邮箱验证码<input v-model.trim="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" required minlength="6" maxlength="6" :disabled="busy"/></label>
<button type="button" class="tf-button" :disabled="busy||sending||remaining>0||!username.trim()" @click="sendCode">{{sending?'正在发送…':remaining>0?`${remaining}秒后可重新发送`:'发送验证码'}}</button>
<p class="tf-hint">验证码5分钟内有效；重新发送后请使用最新验证码。</p>
<label>显示名称<input v-model.trim="displayName" autocomplete="nickname" required maxlength="64" :disabled="busy||sending"/></label>
</template>
<label>密码<input v-model="password" type="password" :autocomplete="registering?'new-password':'current-password'" required :minlength="registering?12:undefined" :disabled="busy||sending"/></label>
<p v-if="registering" class="tf-hint">密码需12至72个UTF-8字节，包含大小写字母、数字和符号。</p>
<p v-if="error" class="tf-error" role="alert">{{error}}</p><p v-if="notice" class="tf-success" role="status">{{notice}}</p>
<button type="submit" class="tf-button tf-primary" :disabled="busy||sending">{{busy?'正在处理…':registering?'注册账号':'登录'}}</button>
<button v-if="!admin" type="button" class="tf-button tf-link" :disabled="busy||sending" @click="switchMode">{{registering?'已有账号，返回登录':'还没有账号？邮箱注册'}}</button>
</form></section></template>
