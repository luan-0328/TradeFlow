import {computed,ref,watch} from 'vue'
import {api,useLoad,session,type DataRow} from '@tradeflow/ui'
export function row(value:unknown):DataRow {return value!==null&&typeof value==='object'&&!Array.isArray(value)?value as DataRow:{}}
export function rows(value:unknown):DataRow[]{return Array.isArray(value)?value.map(row):[]}
export function text(value:unknown):string {return value==null?'—':typeof value==='object'?JSON.stringify(value):String(value)}
export function permits(value:unknown,action:string):boolean {return Array.isArray(row(value).allowedActions)&& (row(value).allowedActions as unknown[]).includes(action)}
/** 列表读取只走GET；切页或账号切换立即清空旧记录，useLoad负责中止与代际隔离。 */
export function useRows(path:()=>string,mode:'page'|'array'|'cursor'|'local'='page'){
 const page=ref(1),before=ref(''),cursors=ref<string[]>([''])
 const load=useLoad<unknown>(signal=>api(path()+`${path().includes('?')?'&':'?'}page=${page.value}&pageSize=20&limit=20${before.value?'&before='+encodeURIComponent(before.value):''}`,{signal}))
 const items=computed(()=>Array.isArray(load.data.value)?rows(load.data.value):rows(row(load.data.value).items))
 const total=computed(()=>typeof row(load.data.value).total==='number'?Number(row(load.data.value).total):null)
 const visible=computed(()=>mode==='local'?items.value.slice((page.value-1)*20,page.value*20):items.value)
 const hasNext=computed(()=>mode==='local'?page.value*20<items.value.length:total.value!==null?page.value*20<total.value:items.value.length===20)
 async function reload(){load.clear();await load.reload()}
 function reset(){page.value=1;before.value='';cursors.value=[''];load.clear()}
 async function change(delta:number){if(mode==='cursor'){if(delta>0){const last=items.value.at(-1);if(!last)return;before.value=text(last.aftersaleNo);cursors.value[page.value]=before.value}else before.value=cursors.value[page.value-2]||''}page.value+=delta;if(mode!=='local')await reload()}
 watch(()=>session.user?.userId,()=>reset())
 return {...load,page,items:visible,total,hasNext,reload,change,reset}
}
