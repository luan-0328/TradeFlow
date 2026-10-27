import { reactive } from 'vue'
import type { Capabilities, User } from './types'

export const session = reactive<{token:string|null;user:User|null;capabilities:Capabilities|null;ready:boolean;epoch:number;merchantId:string}>({token:null,user:null,capabilities:null,ready:false,epoch:0,merchantId:''})
let appScope='user'
const requests=new Set<AbortController>()
const sessionKey=()=>`tradeflow.session.${appScope}`
export class ApiError extends Error {
  constructor(message:string,public code:string,public status:number,public requestId='',public uncertain=false,public retryAfterSeconds=0){super(message);this.name='ApiError'}
}
/** 两个入口各自保留tab会话，不把JWT放URL或持久localStorage；资料始终从后端重新读取。 */
export function configureClient(scope:'user'|'admin'){
  appScope=scope
  try{session.token=sessionStorage.getItem(sessionKey())}catch{session.token=null}
}
/** 退出使所有旧请求失效，迟到的401和资料不能覆盖新账号。 */
export function logout(){
  const changed=!!session.token||!!session.user||!!session.capabilities
  requests.forEach(controller=>controller.abort());requests.clear()
  session.merchantId='';session.token=null;session.user=null;session.capabilities=null;session.ready=true
  if(changed)session.epoch++
  try{sessionStorage.removeItem(sessionKey())}catch{/* 无法写存储时仍清除内存认证。 */}
}
/** 商家工作空间切换撤销旧请求和界面代际；授权仍由各服务最新成员事实决定。 */
export function selectMerchant(id:string){
  if(id&&!session.capabilities?.merchantIds.includes(id))throw new ApiError('无权选择该商家','MERCHANT_FORBIDDEN',403)
  if(id===session.merchantId)return
  requests.forEach(controller=>controller.abort());requests.clear();session.epoch++;session.merchantId=id
}
/** 导航权限必须收窄到当前店铺，不能用A店管理员能力开放B店管理动作。 */
export function can(action:string){
  const capabilities=session.capabilities
  if(!capabilities?.allowedActions.includes(action))return false
  const role=capabilities.merchantRoles?.[session.merchantId]
  if(['MANAGE_PRODUCTS','MANAGE_INVENTORY'].includes(action))return role==='ADMIN'
  if(['MERCHANT_PRODUCTS','MERCHANT_ORDERS','MERCHANT_AFTERSALES'].includes(action))return role==='ADMIN'||role==='OPERATOR'
  return true
}
export interface RequestOptions {method?:string;body?:unknown;key?:string;signal?:AbortSignal;headers?:Record<string,string>}

/** 只向同源业务网关发送令牌；超时/网络错误保持未知，不自动重试任何写请求。 */
async function request(path:string,options:RequestOptions={},blob=false):Promise<unknown>{
  if(!path.startsWith('/')||path.startsWith('//')||path.includes('\\')||path.includes('..'))throw new ApiError('请求地址无效','INVALID_API_PATH',0)
  path=path.startsWith('/api/v1/')?path:`/api/v1${path}`
  const generation=session.epoch,token=session.token,controller=new AbortController()
  requests.add(controller)
  const abort=()=>controller.abort();options.signal?.addEventListener('abort',abort,{once:true})
  if(options.signal?.aborted)controller.abort()
  const timer=setTimeout(abort,15000)
  const method=options.method??(options.body===undefined?'GET':'POST')
  const headers=new Headers({Accept:blob?'image/png,image/jpeg,image/webp':'application/json'})
  if(options.headers)for(const [key,value] of Object.entries(options.headers)){if(key.toLowerCase()!=='x-merchant-id')throw new ApiError('不允许自定义该请求头','INVALID_HEADER',0);headers.set(key,value)}
  if(session.merchantId&&!headers.has('X-Merchant-Id'))headers.set('X-Merchant-Id',session.merchantId)
  if(token)headers.set('Authorization',`Bearer ${token}`)
  if(options.key)headers.set('Idempotency-Key',options.key)
  const form=options.body instanceof FormData
  if(options.body!==undefined&&!form)headers.set('Content-Type','application/json')
  try{
    const response=await fetch(path,{method,headers,body:options.body===undefined?undefined:form?options.body as FormData:JSON.stringify(options.body),signal:controller.signal,credentials:'omit',redirect:'error',cache:'no-store'})
    if(generation!==session.epoch)throw new ApiError('账号已切换，请重新读取','SESSION_CHANGED',0)
    if(blob&&response.ok){const value=await response.blob();if(generation!==session.epoch)throw new ApiError('账号已切换，请重新读取','SESSION_CHANGED',0);return value}
    const envelope=await response.json().catch(()=>null) as {code?:string;message?:string;data?:unknown;requestId?:string}|null
    if(!response.ok||!envelope||envelope.code!=='OK'){
      if(response.status===401&&generation===session.epoch)logout()
      const uncertain=method!=='GET'&&(response.status>=500||response.ok)
      const retry=Number(response.headers.get('Retry-After'))
      throw new ApiError(envelope?.message??'暂时无法确认请求结果',envelope?.code??`HTTP_${response.status}`,response.status,envelope?.requestId??'',uncertain,Number.isFinite(retry)?Math.max(0,retry):0)
    }
    if(generation!==session.epoch)throw new ApiError('账号已切换，请重新读取','SESSION_CHANGED',0)
    return envelope.data
  }catch(error){
    if(error instanceof ApiError)throw error
    if(options.signal?.aborted||generation!==session.epoch)throw new ApiError('读取已取消','CANCELLED',0)
    throw new ApiError(method==='GET'?'暂时无法读取，请稍后刷新':'结果尚未确认，请保留原内容重试，不要重复新建','NETWORK_UNCONFIRMED',0,'',method!=='GET')
  }finally{clearTimeout(timer);options.signal?.removeEventListener('abort',abort);requests.delete(controller)}
}
export async function api<T>(path:string,options:RequestOptions={}):Promise<T>{return await request(path,options) as T}
export async function apiBlob(path:string,signal?:AbortSignal):Promise<Blob>{return await request(path,{signal},true) as Blob}
/** 证据上传不接受URL；用户主动选择后只上传文件，绑定仍由申请/验收接口检查。 */
export async function uploadEvidence(file:File){
  if(file.size>5242880||!['image/png','image/jpeg','image/webp'].includes(file.type))throw new ApiError('请选择5MB以内的PNG、JPEG或WebP图片','INVALID_EVIDENCE',400)
  const body=new FormData();body.append('file',file)
  return api<{objectKey:string;mediaType:string;sizeBytes:number}>('/api/v1/evidence-files',{method:'POST',body})
}
/** 身份与可见动作只取当前后端数据，JWT里的旧角色不用于生成导航。 */
export async function initSession(){
  if(!session.token){session.ready=true;return}
  const epoch=session.epoch
  try{
    const [user,capabilities]=await Promise.all([api<User>('/api/v1/users/me'),api<Capabilities>('/api/v1/users/me/capabilities')])
    if(epoch===session.epoch){session.user=user;session.capabilities=capabilities;if(!capabilities.merchantIds.includes(session.merchantId))session.merchantId=capabilities.merchantIds.length===1?capabilities.merchantIds[0]!: ''}
  }catch(error){if(epoch===session.epoch)logout();throw error}
  finally{if(epoch===session.epoch||!session.token)session.ready=true}
}
/** 登录结果先隔离旧会话，再核验最新资料；密码仅存在本次请求内，不保存到请求日记。 */
export async function login(username:string,password:string){
  const result=await api<{accessToken:string}>('/api/v1/auth/login',{method:'POST',body:{username,password}})
  logout();session.epoch++;session.token=result.accessToken;session.ready=false
  try{sessionStorage.setItem(sessionKey(),result.accessToken)}catch{/* 当前tab仍可使用内存会话。 */}
  await initSession()
}
export async function register(email:string,password:string,displayName:string,code:string){return api<User>('/api/v1/auth/register',{method:'POST',body:{email,password,displayName,code}})}
export async function sendRegistrationCode(email:string){return api<{retryAfterSeconds:number;expiresInSeconds:number}>('/api/v1/auth/email-code',{method:'POST',body:{email}})}
export function errorText(error:unknown){return error instanceof ApiError?`${error.message}${error.code?`（${error.code}）`:''}${error.requestId?` · 请求号 ${error.requestId}`:''}`:error instanceof Error?error.message:'暂时无法完成，请稍后重试'}
