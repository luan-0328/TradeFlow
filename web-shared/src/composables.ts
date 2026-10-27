import {getCurrentScope,onScopeDispose,ref,shallowRef,watch} from 'vue'
import {errorText,session} from './client'

/** 读取独立代际与AbortController；翻页、账号切换和离开页面后，旧响应不会覆盖新状态。 */
export function useLoad<T>(loader:(signal:AbortSignal)=>Promise<T>){
 const data=shallowRef<T|null>(null),loading=ref(false),error=ref('');let generation=0,controller:AbortController|null=null,active=false,disposed=false
 function clear(){generation++;controller?.abort();controller=null;data.value=null;loading.value=false;error.value=''}
 async function reload(){if(disposed)return;active=true;const mine=++generation,epoch=session.epoch;controller?.abort();controller=new AbortController();loading.value=true;error.value='';try{const result=await loader(controller.signal);if(mine===generation&&epoch===session.epoch&&!disposed)data.value=result}catch(reason){if(mine===generation&&epoch===session.epoch&&!disposed)error.value=errorText(reason)}finally{if(mine===generation)loading.value=false}}
 const stop=watch(()=>session.epoch,()=>{clear();if(active&&!disposed)void reload()})
 if(getCurrentScope())onScopeDispose(()=>{disposed=true;clear();stop()})
 return {data,loading,error,reload,clear}
}
