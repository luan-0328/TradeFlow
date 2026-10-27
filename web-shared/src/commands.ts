import {ref} from 'vue'
import {ApiError,errorText,session} from './client'
interface Entry {key:string;digest:string;state:'unknown'|'accepted';reference?:Record<string,string>}
/** 固定对象键顺序生成摘要，不把请求正文、密码、地址或图片写入持久日记。 */
function canonical(value:unknown):string{if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';if(value&&typeof value==='object')return '{'+Object.entries(value).filter(([,v])=>v!==undefined).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>JSON.stringify(k)+':'+canonical(v)).join(',')+'}';return JSON.stringify(value)??'null'}
function commandDigest(scope:string,operation:string,merchant:string){return digest(scope.startsWith('admin-')&&merchant&&merchant!=='merchant_demo'?[scope,operation,merchant]:[scope,operation])}
async function digest(value:unknown){const raw=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(canonical(value)));return [...new Uint8Array(raw)].map(n=>n.toString(16).padStart(2,'0')).join('')}
function read(key:string):Entry|null{const value=localStorage.getItem(key);if(!value)return null;const row=JSON.parse(value) as Entry;if(!row.key||!row.digest||!['unknown','accepted'].includes(row.state))throw new Error('请求编号记录损坏，请先核对原业务结果');return row}
function reference(result:unknown){const data=result&&typeof result==='object'?result as Record<string,unknown>:{};return Object.fromEntries(['businessNo','orderNo','paymentNo','refundNo','aftersaleNo'].flatMap(key=>typeof data[key]==='string'&&/^[A-Za-z0-9_:.-]{1,64}$/.test(data[key] as string)?[[key,data[key] as string]]:[]))}
/** 一个表单意图一个原键；未知结果禁止同scope换参新建，同参可按原键恢复，已确认结果可开始下一意图。 */
export function useCommand(scope:string){
  const busy=ref(false),error=ref(''),pending=ref(false);const owned=new Map<string,string>()
  /** 只检查当前账号本表单的日记；不扫描其他账号，不读取或重建地址正文。 */
  async function refreshPending(operation:string){const epoch=session.epoch,user=session.user?.userId,merchant=session.merchantId;if(!user){pending.value=false;return}try{const key=`tradeflow.command.${user}.${await commandDigest(scope,operation,merchant)}`;const found=!!read(key);if(epoch===session.epoch)pending.value=found}catch(reason){if(epoch===session.epoch){pending.value=true;error.value=errorText(reason)}}}
  /**
   * 仅用原键查询权威结果；resolver返回null表示仍未知，保留原记录，绝不静默换键。
   * 原表单丢失也可恢复已受理编号；锁覆盖查询与落日记，防止其他标签页同时修改意图。
   */
  async function recover<T>(operation:string,resolver:(key:string)=>Promise<T|null>):Promise<T|undefined>{
    if(busy.value)return undefined;busy.value=true;error.value='';const epoch=session.epoch,user=session.user?.userId,merchant=session.merchantId
    try{
      if(!user)throw new ApiError('请先登录再查询原请求','LOGIN_REQUIRED',401)
      if(!navigator.locks)throw new Error('当前浏览器不支持安全命令协调，请使用新版Chrome或Edge')
      const storageKey=`tradeflow.command.${user}.${await commandDigest(scope,operation,merchant)}`
      return await navigator.locks.request(storageKey,async()=>{
        if(epoch!==session.epoch)return undefined
        const entry=read(storageKey);if(!entry){pending.value=false;return undefined}
        pending.value=true
        const result=await resolver(entry.key)
        if(epoch!==session.epoch)return undefined
        if(result===null){error.value='原请求暂未查到，仍可能迟到提交。请保留原键和内容重试，不要换键新建；也可稍后再次查询。';return undefined}
        localStorage.setItem(storageKey,JSON.stringify({...entry,state:'accepted',reference:reference(result)}));owned.set(storageKey,entry.key);return result
      })
    }catch(reason){if(epoch===session.epoch)error.value=errorText(reason);return undefined}finally{busy.value=false}
  }
  async function run<T>(operation:string,body:unknown,handler:(key:string)=>Promise<T>):Promise<T|undefined>{
    if(busy.value)return undefined
    busy.value=true;error.value='';const epoch=session.epoch,user=session.user?.userId,merchant=session.merchantId
    let storageKey='',entry:Entry|undefined
    try{
      if(!user)throw new ApiError('请先登录再操作','LOGIN_REQUIRED',401)
      const hash=await digest(body);storageKey=`tradeflow.command.${user}.${await commandDigest(scope,operation,merchant)}`
      // 同一源内从准备到结果落日记串行，避免旧标签页的迟到响应覆盖另一笔未知请求。
      if(!navigator.locks)throw new Error('当前浏览器不支持安全命令协调，请使用新版Chrome或Edge')
      return await navigator.locks.request(storageKey,async()=>{
        if(epoch!==session.epoch)throw new ApiError('账号已切换，操作已取消','SESSION_CHANGED',0)
        const old=read(storageKey)
        if(old&&old.state==='unknown'&&old.digest!==hash)throw new Error('上一笔结果尚未确认，请按原内容重试，或先到业务列表核对结果')
        entry=old?.digest===hash?old:{key:crypto.randomUUID(),digest:hash,state:'unknown'}
        localStorage.setItem(storageKey,JSON.stringify(entry));owned.set(storageKey,entry.key)
        try{
          const result=await handler(entry.key)
          if(epoch===session.epoch){localStorage.setItem(storageKey,JSON.stringify({...entry,state:'accepted',reference:reference(result)}));return result}
          return undefined
        }catch(reason){
          // 旧未知请求即使本次遇到撤权/冲突也不能删除原键，否则可能重复新建已提交业务。
          if(!old&&reason instanceof ApiError&&!reason.uncertain&&reason.status>=400&&reason.status<500&&reason.code!=='IDEMPOTENCY_CONFLICT')localStorage.removeItem(storageKey)
          throw reason
        }
      })
    }catch(reason){
      if(epoch===session.epoch)error.value=errorText(reason)
      return undefined
    }finally{busy.value=false}
  }
  /** 明确收到受理结果之后才能清理本组件日记，未知结果不能通过重置按钮绕过。 */
  async function reset(){try{for(const [key,ownedKey] of owned){await navigator.locks.request(key,()=>{const current=read(key);if(current?.key!==ownedKey)return;if(current.state==='unknown')throw new Error('仍有待确认请求，请按原内容重试');localStorage.removeItem(key)});owned.delete(key)}error.value=''}catch(reason){error.value=errorText(reason)}}
  return {busy,error,pending,refreshPending,recover,run,reset}
}
