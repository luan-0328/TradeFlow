import { computed, onUnmounted, ref, watch } from 'vue'
import { session } from '@tradeflow/ui'
export interface CartLine {merchantId:string;skuId:string;productName:string;specification:string;quantity:number;expectedUnitPriceCents:number}
export const cart=ref<CartLine[]>([])
const owner=computed(()=>session.user?.userId || '')
let syncing=false,cartOwner=''
// 账号变化同步切换购物袋所有者，再同步持久化变更，避免同一事件内切账号将旧购物袋写入新账号；地址仅在页面内存。
watch(owner,id=>{syncing=true;cartOwner=id;cart.value=[];if(id){try{const v=JSON.parse(localStorage.getItem(`tf:user:cart:${id}`)||'[]');if(Array.isArray(v))cart.value=v.map(x=>({...x,merchantId:x.merchantId||'merchant_demo'})).filter(x=>/^merchant_[a-z0-9_]{1,48}$/.test(x.merchantId)&&typeof x.skuId==='string' && Number.isInteger(x.quantity)&&x.quantity>0&&x.quantity<=99&&Number.isSafeInteger(x.expectedUnitPriceCents)&&x.expectedUnitPriceCents>0).slice(0,20)}catch{}}syncing=false},{immediate:true,flush:'sync'})
watch(cart,v=>{if(owner.value&&cartOwner===owner.value&&!syncing){try{localStorage.setItem(`tf:user:cart:${owner.value}`,JSON.stringify(v))}catch{/* 存储受限时保留当前页购物袋，不影响服务端报价与下单校验。 */}}},{deep:true,flush:'sync'})
export function addLine(line:CartLine){const old=cart.value.find(x=>x.skuId===line.skuId);if(old){if(old.quantity+line.quantity>99)throw new Error('同一规格最多99件');old.quantity+=line.quantity;old.expectedUnitPriceCents=line.expectedUnitPriceCents}else{if(cart.value.length>=20)throw new Error('一次最多选择20种规格');cart.value.push(line)}}
// 只读轮询有次数上限，离开页面或切换身份立即取消；完成后不再安排下一次。
export function usePoll(task:(signal:AbortSignal)=>Promise<boolean>){const running=ref(false),message=ref('');let controller:AbortController|null=null,timer:ReturnType<typeof setTimeout>|undefined,count=0,disposed=false;const stop=()=>{controller?.abort();clearTimeout(timer);running.value=false};const start=()=>{if(disposed)return;stop();count=0;message.value='';controller=new AbortController();const signal=controller.signal;running.value=true;const tick=async()=>{try{const done=await task(signal);if(signal.aborted)return;if(done){stop();return}if(++count>=30){message.value='自动查询已暂停，可稍后手动刷新。';stop();return}timer=setTimeout(tick,2000)}catch(e){if(!signal.aborted){message.value=e instanceof Error?e.message:'查询失败，请手动刷新';stop()}}};void tick()};watch(owner,stop);onUnmounted(()=>{disposed=true;stop()});return {running,message,start,stop}}
export const pathNo=(no:string)=>encodeURIComponent(no)
