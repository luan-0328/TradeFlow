<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {api,useLoad,money,can,PageHeader,Feedback,EmptyState,type Page,type Product} from '@tradeflow/ui'
import {Package,ArrowUpRight,ShoppingBag} from 'lucide-vue-next'
import {addLine,pathNo} from '../state'
interface ProductImage {imageNo:string;url:string}
const route=useRoute(),router=useRouter(),page=ref(1),selected=ref(''),quantity=ref(1),note=ref(''),error=ref('')
const keyword=ref(''),merchantId=ref(''),failedImage=ref<Record<string,boolean>>({})
const query=()=>new URLSearchParams({page:String(page.value),pageSize:'12',keyword:String(route.query.keyword||''),...(route.query.merchantId?{merchantId:String(route.query.merchantId)}:{})})
const listing=useLoad<Page<Product>>(signal=>api(`/products?${query()}`,{signal}))
const detail=useLoad<Product>(signal=>api(`/products/${pathNo(String(route.params.id))}`,{signal}))
const product=computed(()=>detail.data.value),sku=computed(()=>product.value?.skus.find(s=>s.skuId===selected.value))
/** 图片读取独立于商品资料；某个资源失败只影响对应图片，公开URL不携带令牌和业务选择。 */
const images=useLoad<{byProduct:Record<string,ProductImage[]>;failed:string[]}>(async signal=>{
 const ids=route.params.id?(product.value?[product.value.productId]:[]):listing.data.value?.items.map(p=>p.productId)||[]
 const results=await Promise.allSettled(ids.map(id=>api<ProductImage[]>(`/products/${pathNo(id)}/images`,{signal})))
 const byProduct:Record<string,ProductImage[]>={},failed:string[]=[]
 results.forEach((result,index)=>{const id=ids[index]!;if(result.status==='fulfilled')byProduct[id]=result.value;else failed.push(id)})
 return {byProduct,failed}
})
function pictures(id:string){return images.data.value?.byProduct[id]||[]}
watch(()=>[route.params.id,route.query.keyword,route.query.merchantId],async()=>{
 page.value=1;selected.value='';quantity.value=1;note.value='';error.value='';failedImage.value={};images.clear();detail.clear();listing.clear()
 keyword.value=String(route.query.keyword||'');merchantId.value=String(route.query.merchantId||'')
 if(route.params.id){await detail.reload();selected.value=product.value?.skus.find(s=>s.status==='ACTIVE'&&s.availableQty>0)?.skuId||''}else await listing.reload()
},{immediate:true})
watch([listing.data,detail.data],()=>{images.clear();failedImage.value={};if(product.value||listing.data.value)void images.reload()})
function add(){error.value='';note.value='';try{if(!sku.value||!product.value)return;if(!Number.isInteger(quantity.value)||quantity.value<1||quantity.value>99)throw Error('请选择1至99件');addLine({merchantId:product.value.merchantId,skuId:sku.value.skuId,productName:product.value.name,specification:sku.value.specification,quantity:quantity.value,expectedUnitPriceCents:sku.value.priceCents});note.value='已加入购物袋，可继续选购或前往结算。'}catch(reason){error.value=(reason as Error).message}}
async function search(){error.value='';if(merchantId.value&&!/^merchant_[a-z0-9_]{1,48}$/.test(merchantId.value)){error.value='请填写有效店铺编号，或清空查看所有店铺';return}page.value=1;await router.replace({path:'/',query:{...(keyword.value.trim()?{keyword:keyword.value.trim()}:{}),...(merchantId.value?{merchantId:merchantId.value}:{})}});if(!route.params.id)await listing.reload()}
async function change(value:number){page.value=value;listing.clear();await listing.reload()}
</script>
<template>
 <section v-if="route.params.id">
  <RouterLink to="/" class="back-link">← 返回商品目录</RouterLink><Feedback :error="detail.error.value" :loading="detail.loading.value"/>
  <div v-if="product" class="two-col product-detail">
   <div class="panel product-story"><span class="eyebrow">商品详情</span>
    <div v-if="pictures(product.productId).length" class="detail-gallery"><template v-for="image in pictures(product.productId)" :key="image.imageNo"><img v-if="!failedImage[image.imageNo]" :src="image.url" :alt="product.name+'商品图片'" width="480" height="360" @error="failedImage[image.imageNo]=true"/><p v-else class="muted">这张图片暂时无法显示</p></template></div><Package v-else class="product-symbol" :size="76"/>
    <Feedback :loading="images.loading.value" :error="images.error.value"/><p v-if="images.data.value?.failed.length" class="muted">图片暂时无法读取。<button @click="images.reload()">重试图片</button></p>
    <h1>{{product.name}}</h1><p class="muted description">{{product.description||'商家尚未补充商品说明。'}}</p><p class="small muted">商品编号 {{product.productId}}</p><RouterLink :to="{path:'/',query:{merchantId:product.merchantId}}">查看这家店铺的商品</RouterLink>
   </div>
   <form class="panel" @submit.prevent="add"><h2>选择适合你的规格</h2><label for="sku">商品规格</label><select id="sku" v-model="selected" required><option value="" disabled>请选择可售规格</option><option v-for="item in product.skus" :key="item.skuId" :value="item.skuId" :disabled="item.status!=='ACTIVE'||item.availableQty<1">{{item.specification||item.skuCode}} · {{money(item.priceCents)}} · 可用{{item.availableQty}}件</option></select><label for="quantity">购买数量</label><input id="quantity" v-model.number="quantity" type="number" min="1" max="99" required/><div v-if="sku" class="price-block"><span>单价</span><strong>{{money(sku.priceCents)}}</strong></div><p class="muted small">库存与价格在下单时重新确认。</p><p role="status" class="success-text">{{note}}</p><Feedback :error="error"/><button class="primary" :disabled="!can('SHOP')||!sku||sku.status!=='ACTIVE'||sku.availableQty<quantity"><ShoppingBag :size="18"/>加入购物袋</button><RouterLink class="button secondary" to="/cart">前往购物袋</RouterLink></form>
  </div>
 </section>
 <section v-else>
  <div class="catalog-intro"><div><span class="eyebrow">从一件好物开始</span><h1>安心选购，进度随行。</h1><p>查看真实在售商品，从选规格到售后，每一步都可追踪。</p></div><div class="intro-note"><Package :size="30"/><strong>选购 · 支付 · 履约</strong><span>交易信息，集中管理</span></div></div>
  <PageHeader title="在售商品" description="价格和库存由商家实时维护"><template #actions><button @click="listing.reload()">刷新商品</button></template></PageHeader>
  <form class="panel catalog-filters" @submit.prevent="search"><div><label for="catalog-keyword">搜索商品</label><input id="catalog-keyword" v-model="keyword" maxlength="100" placeholder="输入商品名称或描述" type="search"/></div><div><label for="catalog-merchant">店铺编号（可选）</label><input id="catalog-merchant" v-model.trim="merchantId" placeholder="查看所有店铺" maxlength="57"/></div><button class="primary" type="submit" :disabled="listing.loading.value">查找商品</button><RouterLink v-if="route.query.keyword||route.query.merchantId" to="/">清除筛选</RouterLink></form>
  <Feedback :error="error||listing.error.value" :loading="listing.loading.value"/><p v-if="images.data.value?.failed.length" class="muted" role="status">部分商品图片暂时无法读取。<button @click="images.reload()">重试图片</button></p>
  <EmptyState v-if="listing.data.value&&!listing.data.value.items.length" title="没有找到商品" description="试试其他关键词或清除店铺筛选。"><button @click="listing.reload()">重新查看</button></EmptyState>
  <div class="product-grid"><RouterLink v-for="item in listing.data.value?.items" :key="item.productId" :to="`/products/${pathNo(item.productId)}`" class="product-card"><div class="product-art"><img v-if="pictures(item.productId)[0]&&!failedImage[pictures(item.productId)[0]!.imageNo]" :src="pictures(item.productId)[0]!.url" :alt="item.name" loading="lazy" width="400" height="280" @error="failedImage[pictures(item.productId)[0]!.imageNo]=true"/><template v-else><Package :size="46"/><span>{{images.loading.value?'图片加载中':'商品图片'}}</span></template></div><div class="product-copy"><span class="eyebrow">在售商品</span><h2>{{item.name}}</h2><p>{{item.description||'查看商品详情与可选规格'}}</p><div class="card-bottom"><strong>{{item.skus.length?money(Math.min(...item.skus.map(s=>s.priceCents)))+' 起':'查看规格'}}</strong><ArrowUpRight :size="20"/></div></div></RouterLink></div>
  <div v-if="listing.data.value" class="pagination"><button :disabled="page<=1||listing.loading.value" @click="change(page-1)">上一页</button><span>第 {{page}} 页 · 共 {{listing.data.value.total}} 件商品</span><button :disabled="page*12>=listing.data.value.total||listing.loading.value" @click="change(page+1)">下一页</button></div>
 </section>
</template>
<style scoped>
.catalog-filters{display:flex;gap:16px;align-items:end;flex-wrap:wrap;margin-bottom:24px}.catalog-filters>div{flex:1;min-width:180px}.catalog-filters label{display:block;margin-bottom:8px}.catalog-filters input{width:100%}.product-art{overflow:hidden}.product-art img{width:100%;height:100%;object-fit:cover}.detail-gallery{display:grid;gap:12px;margin:20px 0}.detail-gallery img{max-width:100%;height:auto;aspect-ratio:4/3;object-fit:contain;border-radius:10px;background:#f6f7f9}@media(max-width:600px){.catalog-filters{align-items:stretch}.catalog-filters>div{flex-basis:100%}}
</style>
