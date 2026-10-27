/** P0真实浏览器验收：全新headless上下文，所有业务写入均通过Vue表单/按钮；不读用户Chrome资料。 */
import {createRequire} from 'node:module';
import {readFile,writeFile,mkdir,realpath} from 'node:fs/promises';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const require=createRequire(join(ROOT,'.local/p0-browser-tools/package.json'));
const {chromium,expect:baseExpect}=require('playwright/test');
const expect=baseExpect.configure({timeout:20000});
const argv=process.argv.slice(2);
const only=argv.includes('--only')?argv[argv.indexOf('--only')+1]:'';
assert.ok(!only||only==='sent-replay','UnknownBrowserSubset');
const argument=name=>{const i=argv.indexOf(name);if(i<0||!argv[i+1])throw new Error('MissingArgument');return resolve(argv[i+1]);};
const ready=JSON.parse(await readFile(argument('--ready'),'utf8'));
const seeds=JSON.parse(await readFile(argument('--seeds'),'utf8'));
assert.equal(ready.fixtureOnly,true,'FixtureOnlyRequired');
const loopback=value=>{const url=new URL(value);assert.equal(url.protocol,'http:');assert.ok(['127.0.0.1','localhost','[::1]'].includes(url.hostname));assert.ok(url.port);return url.origin;};
const USER=loopback(ready.userWebUrl),ADMIN=loopback(ready.adminWebUrl);
loopback(ready.gatewayUrl);
for(const name of ['platform','ownerA','ownerB','operator','buyer','browserOwner'])assert.ok(seeds.accounts?.[name]?.username&&seeds.accounts[name].password,'AccountSeedRequired');
for(const key of ['a','b']){
 assert.match(seeds.merchants[key],/^merchant_[a-z0-9_]{1,48}$/);
 assert.match(seeds.products[key].productId,/^[1-9][0-9]{0,18}$/);assert.match(seeds.products[key].skuId,/^[1-9][0-9]{0,18}$/);
}
const fixtureRoot=await realpath(ready.root),out=join(fixtureRoot,'browser-evidence');await mkdir(out,{recursive:true});
const report={checks:[],pageErrors:[]};
const suffix=randomUUID().replaceAll('-','').slice(0,12),merchantC='merchant_browser_'+suffix;
const tokens=new WeakMap(),contexts=[];
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--disable-background-networking','--no-first-run','--no-default-browser-check']});
const deadline=(promise,milliseconds=20000)=>Promise.race([promise,new Promise((_,reject)=>{const timer=setTimeout(()=>reject(new Error('BoundedWaitTimeout')),milliseconds);timer.unref();})]);
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const safeType=error=>/^[A-Za-z][A-Za-z0-9_]{0,60}$/.test(error?.name||'')?error.name:'BrowserCheckError';
// 路由故障转为本检查失败，禁止未处理的异步异常让报告或browser关闭流程丢失。
const guardedRoute=(work,signal)=>async route=>{try{await work(route);}catch(error){signal.resolve({errorType:safeType(error)});await route.abort('failed').catch(()=>{});}};
async function gate(signal,milliseconds=20000){assert.equal(await deadline(signal.promise,milliseconds),undefined,'ActualBackendResponseRequired');}
const match=(response,path,method='GET')=>new URL(response.url()).pathname===path&&response.request().method()===method;
const data=async response=>{assert.ok(response.status()>=200&&response.status()<300,'RealHttpSuccessRequired');const envelope=await response.json();assert.equal(envelope.code,'OK','BusinessSuccessRequired');return envelope.data;};
const items=value=>Array.isArray(value)?value:value?.items||[];
async function newPage(){
 const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN',timezoneId:'Asia/Shanghai',serviceWorkers:'block'});contexts.push(context);
 await context.route('**/*',route=>{const u=new URL(route.request().url());return ['http:','https:'].includes(u.protocol)&&!['127.0.0.1','localhost','[::1]'].includes(u.hostname)?route.abort('blockedbyclient'):route.continue();});
 const page=await context.newPage();page.setDefaultTimeout(20000);page.setDefaultNavigationTimeout(30000);
 page.on('pageerror',error=>report.pageErrors.push({type:'PageError_'+safeType(error)}));
 page.on('console',message=>{if(message.type()==='error')report.pageErrors.push({type:'ConsoleError'});});
 return page;
}
async function login(page,base,account,path='/',navigate=true){
 if(navigate)await page.goto(base+path);await expect(page.locator('.tf-login')).toBeVisible();
 await page.getByLabel('邮箱 / 历史用户名',{exact:true}).fill(account.username);await page.getByLabel('密码',{exact:true}).fill(account.password);
 const pending=page.waitForResponse(response=>match(response,'/api/v1/auth/login','POST'));
 await page.getByRole('button',{name:'登录',exact:true}).click();const auth=await data(await pending);assert.ok(auth.accessToken);tokens.set(page,auth.accessToken);
 await expect(page.locator('.tf-login')).toHaveCount(0);await expect(page.getByRole('button',{name:base===ADMIN?'退出账号':'退出',exact:true})).toBeVisible();
}
async function read(page,base,path,merchant){
 const response=await page.request.get(base+'/api/v1'+path,{headers:{Authorization:'Bearer '+tokens.get(page),...(merchant?{'X-Merchant-Id':merchant}:{})},timeout:15000});return data(response);
}
async function poll(work,predicate,seconds=60){
 const end=Date.now()+seconds*1000;let value;
 while(Date.now()<end){value=await work();if(predicate(value))return value;await new Promise(r=>setTimeout(r,700));}
 throw new Error('BusinessStateTimeout');
}
async function clickResponse(page,button,path,method='POST'){
 const pending=page.waitForResponse(response=>match(response,path,method));await button.click();return data(await pending);
}
const dialog=(page,name)=>page.getByRole('dialog',{name,exact:true});
const field=(scope,label)=>scope.locator('.el-form-item').filter({hasText:label}).locator('input,textarea').first();
const tableRow=(page,value)=>page.locator('.el-table__body tr').filter({has:page.getByText(String(value),{exact:true})}).first();
async function shot(page,name){const path=join(out,name+'.png');await page.screenshot({path,fullPage:true,mask:[page.locator('input[type=password]')]});return path;}
async function additionalShot(page,name){report.checks.at(-1).screenshots.push(await shot(page,name));}
async function check(name,page,work){
 if(only==='sent-replay'&&!name.startsWith('14-'))return false;
 const entry={name,passed:false,businessNos:[],screenshots:[]};report.checks.push(entry);
 try{const result=await work();entry.businessNos=(result||[]).map(String).filter(value=>/^[A-Za-z0-9_:.-]{1,100}$/.test(value));entry.passed=true;}
 catch(error){entry.errorType=safeType(error);await writeFile(join(out,name+'-diagnostic.txt'),String(error.stack||error));}
 try{entry.screenshots.push(await shot(page,name));}catch(error){entry.errorType=safeType(error);entry.passed=false;}
 await writeFile(join(out,'report.json'),JSON.stringify(report,null,2));
 process.stdout.write(JSON.stringify({check:name,passed:entry.passed})+'\n');return entry.passed;
}
async function chooseMerchant(page,id){await expect(page.locator('#active-merchant')).toBeVisible();await page.locator('#active-merchant').selectOption(id);await expect(page.locator('#active-merchant')).toHaveValue(id);}

let owner,platform,operator,buyer,accountIsolation;
let createdProduct,createdSku,groupNo,children=[],aftersaleNo,disputeNo,paymentsCreated=[];
try {
 owner=await newPage();platform=await newPage();operator=await newPage();buyer=await newPage();
 await check('01-onboarding-application-review',owner,async()=>{
  await login(owner,USER,seeds.accounts.browserOwner,'/account');await owner.getByRole('button',{name:'商家入驻',exact:true}).click();
  await owner.getByLabel('商家编号',{exact:true}).fill(merchantC);await owner.getByLabel('商家名称',{exact:true}).fill('Browser店铺'+suffix);await owner.getByLabel('经营资料',{exact:true}).fill('本次独占浏览器验收合成经营资料');
  const application=await clickResponse(owner,owner.getByRole('button',{name:'提交入驻申请',exact:true}),'/api/v1/merchant-applications');
  await expect(owner.getByRole('status').filter({hasText:'申请已提交'})).toBeVisible();
  await login(platform,ADMIN,seeds.accounts.platform,'/platform/merchants');
  const row=tableRow(platform,merchantC);await expect(row).toBeVisible();await row.getByRole('button',{name:'审核',exact:true}).click();
  const review=dialog(platform,'审核入驻申请');await expect(review).toBeVisible();await field(review,'操作原因').fill('P0浏览器真实入驻审核');
  await clickResponse(platform,review.getByRole('button',{name:'确认提交',exact:true}),'/api/v1/admin/merchant-applications/'+application.id+'/review');
  await expect(review).toBeHidden();await expect(row.getByText('已审核',{exact:true})).toBeVisible();await additionalShot(platform,'01-platform-approved');
  await owner.getByRole('button',{name:'退出',exact:true}).click();await login(owner,ADMIN,seeds.accounts.browserOwner,'/merchant/products');await chooseMerchant(owner,merchantC);
  await expect(owner.getByRole('button',{name:'新增商品',exact:true})).toBeVisible();
  return [merchantC,String(application.id)];
 });
 await check('02-product-sku-image-inventory',owner,async()=>{
  assert.ok(tokens.has(owner)&&await owner.getByRole('button',{name:'新增商品',exact:true}).isVisible(),'OnboardingRequired');
  await owner.getByRole('button',{name:'新增商品',exact:true}).click();const create=dialog(owner,'新增商品');
  await field(create,'商品名称').fill('浏览器商品'+suffix);await field(create,'商品描述').fill('真实UI创建、图片与库存验收');await field(create,'SKU编码').fill('BROWSER_'+suffix);await field(create,'规格').fill('初始规格');await field(create,'销售单价（元）').fill('12.34');
  const result=await clickResponse(owner,create.getByRole('button',{name:'确认提交',exact:true}),'/api/v1/merchant/products');createdProduct=String(result.productId);assert.match(createdProduct,/^[1-9][0-9]*$/);
  const row=tableRow(owner,createdProduct);await expect(row).toBeVisible();await row.locator('.el-table__expand-icon').click();
  await owner.getByRole('button',{name:'新增规格',exact:true}).click();const add=dialog(owner,'新增SKU');await field(add,'SKU编码').fill('BROWSER_EXTRA_'+suffix);await field(add,'规格').fill('追加规格');await field(add,'销售单价（元）').fill('15.00');
  const sku=await clickResponse(owner,add.getByRole('button',{name:'确认提交',exact:true}),'/api/v1/merchant/products/'+createdProduct+'/skus');createdSku=String(sku.skuId);assert.match(createdSku,/^[1-9][0-9]*$/);
  // reload可能重建展开行，重新按商品行展开，不能点击其他商品的相同按钮。
  if(!await owner.getByRole('button',{name:'管理商品图片',exact:true}).isVisible())await tableRow(owner,createdProduct).locator('.el-table__expand-icon').click();
  await owner.getByRole('button',{name:'管理商品图片',exact:true}).click();const images=owner.getByRole('dialog').filter({has:owner.getByLabel('选择商品图片',{exact:true})});
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEUlEQVR4nGPQiG/CihiGlgQAzL9CQRVWhDUAAAAASUVORK5CYII=','base64');
  await images.getByLabel('选择商品图片',{exact:true}).setInputFiles({name:'p0-product.png',mimeType:'image/png',buffer:png});
  const image=await clickResponse(owner,images.getByRole('button',{name:'上传或恢复所选图片',exact:true}),'/api/v1/merchant/products/'+createdProduct+'/images');
  assert.ok(image.imageNo);const uploadedImage=images.locator('.image-gallery img');await expect(uploadedImage).toHaveCount(1);await expect.poll(()=>uploadedImage.evaluate(img=>img.complete&&img.naturalWidth>0)).toBeTruthy();await additionalShot(owner,'02-product-image');
  await images.getByRole('button',{name:'关闭此对话框',exact:true}).click();
  // SKU表展示编码而非ID，按唯一编码定位实际按钮。
  const extra=owner.locator('.sku-expansion .el-table__body tr').filter({has:owner.getByText('BROWSER_EXTRA_'+suffix,{exact:true})});await expect(extra).toBeVisible();await extra.getByRole('button',{name:'调整库存',exact:true}).click();
  const inventory=dialog(owner,'库存调整');await expect(field(inventory,'SKU编号')).toHaveValue(createdSku);await field(inventory,'调整数量（整数）').fill('5');await field(inventory,'调整原因').fill('浏览器真实库存入库');
  const receipt=await clickResponse(owner,inventory.getByRole('button',{name:'确认提交',exact:true}),'/api/v1/merchant/inventory/adjustments');assert.ok(receipt.businessNo);
  await poll(()=>read(owner,ADMIN,'/merchant/products?keyword='+encodeURIComponent('浏览器商品'+suffix),merchantC),value=>items(value).some(p=>p.productId===createdProduct&&p.skus.some(s=>s.skuId===createdSku&&s.availableQty===5)));
  await clickResponse(owner,owner.getByRole('button',{name:'刷新列表',exact:true}),'/api/v1/merchant/products','GET');
  if(!await tableRow(owner,'BROWSER_EXTRA_'+suffix).isVisible())await tableRow(owner,createdProduct).locator('.el-table__expand-icon').click();
  await expect(owner.locator('.sku-expansion .el-table__body tr').filter({has:owner.getByText('BROWSER_EXTRA_'+suffix,{exact:true})}).locator('td').nth(3)).toHaveText('5');
  return [createdProduct,createdSku,image.imageNo,receipt.businessNo];
 });
 await check('03-per-merchant-role-and-late-response',operator,async()=>{
  await login(operator,ADMIN,seeds.accounts.operator,'/merchant/products');await chooseMerchant(operator,seeds.merchants.a);
  await expect(operator.getByRole('button',{name:'新增商品',exact:true})).toBeVisible();await expect(operator.getByRole('navigation',{name:'后台业务导航'}).getByRole('link',{name:'运费规则',exact:true})).toBeVisible();
  const fetched=deferred(),release=deferred();let held=false;
  const handler=guardedRoute(async route=>{
   if(!held&&route.request().method()==='GET'&&route.request().headers()['x-merchant-id']===seeds.merchants.a){held=true;const response=await route.fetch();const result=await response.json();assert.ok(items(result.data).some(p=>String(p.productId)===seeds.products.a.productId));fetched.resolve();await release.promise;try{await route.fulfill({response});}catch{/* 商家切换已正确abort旧请求亦属于隔离。 */}}
   else await route.continue();
  },fetched);
  await operator.route('**/api/v1/merchant/products?*',handler);
  try{
   await operator.getByRole('button',{name:'刷新列表',exact:true}).click();await gate(fetched);
   await chooseMerchant(operator,seeds.merchants.b);await expect(tableRow(operator,seeds.products.b.productId)).toBeVisible();
   release.resolve();await operator.waitForTimeout(300);
   await expect(tableRow(operator,seeds.products.a.productId)).toHaveCount(0);await expect(operator.getByRole('button',{name:'新增商品',exact:true})).toHaveCount(0);
   await expect(operator.getByRole('navigation',{name:'后台业务导航'}).getByRole('link',{name:'运费规则',exact:true})).toHaveCount(0);
   await operator.goto(ADMIN+'/merchant/shipping');await chooseMerchant(operator,seeds.merchants.b);await expect(operator.getByText('当前账号没有此模块的权限',{exact:true})).toBeVisible();await expect(operator.getByRole('button',{name:'保存规则',exact:true})).toHaveCount(0);
  }finally{release.resolve();await operator.unroute('**/api/v1/merchant/products?*',handler);}
  return [seeds.merchants.a,seeds.merchants.b];
 });
 await check('04-read-network-failure-and-retry',buyer,async()=>{
  await login(buyer,USER,seeds.accounts.buyer);await expect(buyer.locator('.product-card').first()).toBeVisible();let aborted=0;
  const handler=route=>{if(route.request().method()==='GET'){aborted++;return route.abort('failed');}return route.continue();};
  await buyer.route('**/api/v1/products?*',handler);
  try{await buyer.getByRole('button',{name:'刷新商品',exact:true}).click();await expect(buyer.getByRole('alert').filter({hasText:'暂时无法读取'})).toBeVisible();assert.ok(aborted>0);}
  finally{await buyer.unroute('**/api/v1/products?*',handler);}
  await clickResponse(buyer,buyer.getByRole('button',{name:'刷新商品',exact:true}),'/api/v1/products','GET');await expect(buyer.locator('.product-card').first()).toBeVisible();
  return [];
 });
 const recipient='BrowserRecipient'+suffix;
 await check('05-buyer-address-ui',buyer,async()=>{
  assert.ok(tokens.has(buyer),'BuyerLoginRequired');await buyer.goto(USER+'/account');await buyer.getByLabel('收件人',{exact:true}).fill(recipient);await buyer.getByLabel('联系电话',{exact:true}).fill('13800000000');await buyer.getByLabel('省 / 市 / 区',{exact:true}).fill('测试省 测试市 测试区');await buyer.getByLabel('详细收货地址',{exact:true}).fill('独占验收合成地址1号');
  const address=await clickResponse(buyer,buyer.getByRole('button',{name:'保存地址',exact:true}),'/api/v1/users/me/addresses');await expect(buyer.getByRole('heading',{name:recipient+' · 13800000000'})).toBeVisible();return [address.id];
 });
 await check('06-search-two-merchants-cart',buyer,async()=>{
  for(const key of ['a','b']){
   const product=await read(buyer,USER,'/products/'+seeds.products[key].productId);await buyer.goto(USER+'/');await buyer.getByLabel('搜索商品',{exact:true}).fill(product.name);await buyer.getByLabel('店铺编号（可选）',{exact:true}).fill(seeds.merchants[key]);
   await clickResponse(buyer,buyer.getByRole('button',{name:'查找商品',exact:true}),'/api/v1/products','GET');
   await buyer.locator('a.product-card[href="/products/'+seeds.products[key].productId+'"]').click();await buyer.getByLabel('商品规格',{exact:true}).selectOption(seeds.products[key].skuId);await buyer.getByRole('button',{name:'加入购物袋',exact:true}).click();await expect(buyer.getByRole('status').filter({hasText:'已加入购物袋'})).toBeVisible();
  }
  await buyer.getByRole('link',{name:'前往购物袋',exact:true}).click();await expect(buyer.locator('.cart-line')).toHaveCount(2);await expect(buyer.getByText('2 个商家，共 2 种规格。每商家独立支付。',{exact:true})).toBeVisible();return [seeds.products.a.productId,seeds.products.b.productId];
 });
 await check('07-accepted-create-lost-response-refresh-original-key',buyer,async()=>{
  await expect(buyer.locator('.cart-line')).toHaveCount(2);await buyer.getByLabel('收货人',{exact:true}).fill(recipient);await buyer.getByLabel('联系电话',{exact:true}).fill('13800000000');await buyer.getByLabel('省 / 市 / 区',{exact:true}).fill('测试省 测试市 测试区');await buyer.getByLabel('详细地址',{exact:true}).fill('独占验收合成地址1号');
  let creates=0,originalKey,acceptedNo;const accepted=deferred();
  const count=request=>{if(request.method()==='POST'&&new URL(request.url()).pathname==='/api/v1/trade-groups')creates++;};buyer.on('request',count);
  const handler=guardedRoute(async route=>{
   if(route.request().method()!=='POST')return route.continue();
   originalKey=route.request().headers()['idempotency-key'];const response=await route.fetch();const result=await data(response);acceptedNo=result.groupNo;assert.ok(acceptedNo&&originalKey);accepted.resolve();await route.abort('failed');
  },accepted);
  await buyer.route('**/api/v1/trade-groups',handler);
  try{
   await buyer.getByRole('button',{name:'按商家创建订单',exact:true}).click();await gate(accepted,40000);await expect(buyer.getByRole('alert').filter({hasText:'尚未确认'}).first()).toBeVisible();await buyer.reload();
   await expect(buyer.getByRole('heading',{name:'恢复原分商家结算请求',exact:true})).toBeVisible();
   const pending=buyer.waitForResponse(response=>match(response,'/api/v1/trade-groups/requests/by-key'));
   await buyer.getByRole('button',{name:'查询原交易组',exact:true}).click();const response=await pending;assert.equal(response.request().headers()['idempotency-key'],originalKey);const recovered=await data(response);assert.equal(recovered.groupNo,acceptedNo);groupNo=recovered.groupNo;
   await expect(buyer).toHaveURL(USER+'/trade-groups/'+groupNo);await buyer.reload();await expect(buyer.getByText(new RegExp('交易组 '+groupNo))).toBeVisible();assert.equal(creates,1);
   const group=await poll(()=>read(buyer,USER,'/trade-groups/'+groupNo),value=>value.children.length===2&&value.children.every(c=>!['QUOTING','CREATING'].includes(c.status)),90);
   assert.ok(group.children.every(c=>!['REJECTED','CANCELLED','CLOSED'].includes(c.status)));children=group.children;return [groupNo,...children.map(c=>c.orderNo)];
  }finally{await buyer.unroute('**/api/v1/trade-groups',handler);buyer.off('request',count);}
 });
 await check('08-real-channel-child-payments',buyer,async()=>{
  assert.equal(children.length,2,'TradeGroupRequired');const payments=[];
  for(const child of children){
   await poll(()=>read(buyer,USER,'/orders/'+child.orderNo),value=>value.status==='PENDING_PAYMENT'&&value.allowedActions.includes('PAY'),90);await buyer.goto(USER+'/orders/'+child.orderNo);
   const payment=await clickResponse(buyer,buyer.getByRole('button',{name:'提交模拟支付',exact:true}),'/api/v1/payments');assert.ok(payment.paymentNo);payments.push(payment.paymentNo);paymentsCreated.push(payment.paymentNo);
   await poll(()=>read(buyer,USER,'/payments/'+payment.paymentNo),value=>value.status==='SUCCEEDED',90);
   await poll(()=>read(buyer,USER,'/orders/'+child.orderNo),value=>value.status==='PAID'&&value.stockSyncStatus==='CONFIRMED',90);
   await buyer.getByRole('button',{name:'刷新状态',exact:true}).click();await expect(buyer.getByText('待发货',{exact:true}).first()).toBeVisible();
  }return payments;
 });
 await check('09-merchant-shipment-buyer-receipt',buyer,async()=>{
  assert.equal(children.length,2,'PaidChildrenRequired');const shipped=[];
  for(const key of ['a','b']){
   const child=children.find(c=>c.merchantId===seeds.merchants[key]);assert.ok(child);const merchant=await newPage();
   await login(merchant,ADMIN,seeds.accounts[key==='a'?'ownerA':'ownerB'],'/merchant/orders');await chooseMerchant(merchant,seeds.merchants[key]);await merchant.goto(ADMIN+'/merchant/orders');
   const row=tableRow(merchant,child.orderNo);await expect(row).toBeVisible();await row.getByRole('button',{name:'查看订单',exact:true}).click();const detail=dialog(merchant,'商家订单详情');await detail.getByRole('button',{name:'填写发货物流',exact:true}).click();await field(detail,'承运商').fill('P0合成承运商');await field(detail,'物流单号').fill('P0_'+suffix+'_'+key);
   await clickResponse(merchant,detail.getByRole('button',{name:'确认发货',exact:true}),'/api/v1/merchant/orders/'+child.orderNo+'/ship');await additionalShot(merchant,'09-shipment-'+key);
   await poll(()=>read(buyer,USER,'/orders/'+child.orderNo),value=>value.status==='SHIPPED');await buyer.goto(USER+'/orders/'+child.orderNo);await clickResponse(buyer,buyer.getByRole('button',{name:'确认收货',exact:true}),'/api/v1/orders/'+child.orderNo+'/receive');
   await poll(()=>read(buyer,USER,'/orders/'+child.orderNo),value=>value.status==='COMPLETED');await buyer.getByRole('button',{name:'刷新状态',exact:true}).click();shipped.push(child.orderNo);
  }return shipped;
 });
 await check('10-aftersale-materials-dispute-platform',buyer,async()=>{
  assert.equal(children.length,2,'CompletedOrderRequired');const child=children.find(c=>c.merchantId===seeds.merchants.a);await buyer.goto(USER+'/orders/'+child.orderNo);await buyer.getByRole('link',{name:'申请售后',exact:true}).click();await buyer.getByLabel('当前可申请类型',{exact:true}).selectOption('REFUND_ONLY');await buyer.locator('.allocation-row input[type=checkbox]').first().check();await buyer.getByLabel('本件申请金额（元）',{exact:true}).fill('0.01');await buyer.getByLabel('申请原因',{exact:true}).fill('P0浏览器真实售后申请');
  await clickResponse(buyer,buyer.getByRole('button',{name:'预览退款分配',exact:true}),'/api/v1/aftersales/preview');const sale=await clickResponse(buyer,buyer.getByRole('button',{name:'提交售后申请',exact:true}),'/api/v1/aftersales');aftersaleNo=sale.aftersaleNo;assert.ok(aftersaleNo);
  await expect(buyer).toHaveURL(USER+'/aftersales/'+aftersaleNo);await expect(buyer.getByRole('heading',{name:'申请材料',exact:true})).toBeVisible();await clickResponse(buyer,buyer.getByRole('button',{name:'刷新材料',exact:true}),'/api/v1/aftersales/'+aftersaleNo+'/materials','GET');
  await buyer.goto(USER+'/disputes');await buyer.getByLabel('原售后编号',{exact:true}).fill(aftersaleNo);await buyer.getByLabel('申请依据',{exact:true}).fill('P0合成平台介入核验，保持原处理');const dispute=await clickResponse(buyer,buyer.getByRole('button',{name:'提交平台介入申请',exact:true}),'/api/v1/disputes');disputeNo=dispute.dispute_no||dispute.disputeNo;assert.ok(disputeNo);
  if(!tokens.has(platform))await login(platform,ADMIN,seeds.accounts.platform);await platform.goto(ADMIN+'/platform/disputes');const row=tableRow(platform,disputeNo);await expect(row).toBeVisible();await row.getByRole('button',{name:'处理',exact:true}).click();await platform.getByLabel('裁定',{exact:true}).selectOption('UPHOLD');await platform.getByLabel('裁定依据',{exact:true}).fill('P0浏览器裁定保持原处理，不伪造退款成功');await clickResponse(platform,platform.getByRole('button',{name:'提交裁定',exact:true}),'/api/v1/admin/disputes/'+disputeNo+'/decision');await expect(tableRow(platform,disputeNo).getByText('DECIDED',{exact:true})).toBeVisible();await additionalShot(platform,'10-platform-dispute');return [aftersaleNo,disputeNo];
 });
 await check('11-settlement-statements-ui',platform,async()=>{
  assert.equal(children.length,2,'CompletedChildrenRequired');
  if(!tokens.has(platform))await login(platform,ADMIN,seeds.accounts.platform);await platform.goto(ADMIN+'/platform/settlement');await clickResponse(platform,platform.getByRole('button',{name:'运行一页模拟结算',exact:true}),'/api/v1/admin/settlement/run');
  await poll(()=>read(platform,ADMIN,'/admin/settlement/statements?page=1&pageSize=20'),value=>children.length===2&&children.every(child=>items(value).some(row=>row.order_no===child.orderNo)),60);
  await platform.getByRole('button',{name:'刷新账单',exact:true}).click();for(const child of children)await expect(tableRow(platform,child.orderNo)).toBeVisible();
  await platform.getByRole('tab',{name:'追加账本',exact:true}).click();await expect(platform.getByRole('columnheader',{name:'本金变动',exact:true})).toBeVisible();
  const merchant=await newPage();await login(merchant,ADMIN,seeds.accounts.ownerA,'/merchant/settlement');await chooseMerchant(merchant,seeds.merchants.a);await expect(merchant.getByRole('heading',{name:'商家结算账单',exact:true})).toBeVisible();
  await expect(tableRow(merchant,children.find(c=>c.merchantId===seeds.merchants.a).orderNo)).toBeVisible();await expect(tableRow(merchant,children.find(c=>c.merchantId===seeds.merchants.b).orderNo)).toHaveCount(0);await additionalShot(merchant,'11-merchant-statement');return children.map(c=>c.orderNo);
 });
 accountIsolation=await newPage();
 await check('12-account-switch-late-address-isolation',accountIsolation,async()=>{
  await login(accountIsolation,USER,seeds.accounts.buyer,'/account');await expect(accountIsolation.getByRole('heading',{name:recipient+' · 13800000000'})).toBeVisible();
  const fetched=deferred(),release=deferred();let held=false;
  const handler=guardedRoute(async route=>{if(!held&&route.request().method()==='GET'){held=true;const response=await route.fetch();const envelope=await response.json();assert.ok(items(envelope.data).some(row=>row.recipient===recipient));fetched.resolve();await release.promise;try{await route.fulfill({response});}catch{/* 登出已中止旧请求时禁止强行重新投递。 */}}else await route.continue();},fetched);
  await accountIsolation.route('**/api/v1/users/me/addresses',handler);
  try{await accountIsolation.getByRole('button',{name:'刷新地址',exact:true}).click();await gate(fetched);await accountIsolation.getByRole('button',{name:'退出',exact:true}).click();await login(accountIsolation,USER,seeds.accounts.ownerB,'/account',false);release.resolve();await accountIsolation.waitForTimeout(300);await expect(accountIsolation.getByText(recipient,{exact:false})).toHaveCount(0);await expect(accountIsolation.getByLabel('收件人',{exact:true})).toHaveValue('');}
  finally{release.resolve();await accountIsolation.unroute('**/api/v1/users/me/addresses',handler);}
  return [];
 });
 await check('13-mobile-layout-readable-feedback',buyer,async()=>{
  await buyer.setViewportSize({width:390,height:844});await buyer.goto(USER+'/orders');await expect(buyer.getByRole('heading',{name:'我的订单',exact:true})).toBeVisible();
  assert.ok(await buyer.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'NoHorizontalOverflow');
  await expect(buyer.getByRole('navigation',{name:'主导航',exact:true})).toBeVisible();return [];
 });
 await check('14-sent-original-event-controlled-redelivery',platform,async()=>{
  // 可由API验收提供已真实成功的原支付；完整浏览器流也可使用刚完成的原子单支付。
  const paymentNo=seeds.orders?.replayPaymentNo||paymentsCreated[0];assert.ok(paymentNo,'ReplayPaymentSeedRequired');
  if(!tokens.has(platform))await login(platform,ADMIN,seeds.accounts.platform);
  if(!tokens.has(buyer))await login(buyer,USER,seeds.accounts.buyer);
  assert.equal((await read(buyer,USER,'/payments/'+paymentNo)).status,'SUCCEEDED');
  const snapshot=await poll(()=>read(platform,ADMIN,'/admin/payment-messages/outbox?page=1&pageSize=20'),value=>items(value).some(r=>r.aggregate_id===paymentNo&&r.event_type==='PaymentSucceeded'&&r.status==='SENT'&&r.allowedActions?.includes('REDELIVER_ORIGINAL')),90);
  const original=items(snapshot).find(r=>r.aggregate_id===paymentNo&&r.event_type==='PaymentSucceeded'&&r.status==='SENT');assert.ok(original.event_id);
  const before=items(await read(platform,ADMIN,'/admin/payment-messages/outbox?page=1&pageSize=100')).filter(r=>r.aggregate_id===paymentNo&&r.event_type==='PaymentSucceeded').map(r=>r.event_id).sort();
  await platform.goto(ADMIN+'/platform/operations');await platform.getByRole('navigation',{name:'待办来源',exact:true}).getByRole('button',{name:/支付 · 发布/}).click();
  await platform.getByRole('button',{name:'查看已发送原事件',exact:true}).click();const row=tableRow(platform,original.event_id);await expect(row).toBeVisible();
  await row.getByRole('button',{name:'查看原记录',exact:true}).click();const detail=dialog(platform,'原任务与关联详情');await expect(detail.getByText(new RegExp(original.event_id)).first()).toBeVisible();
  await detail.getByRole('button',{name:'补投原事件',exact:true}).click();const submit=detail.getByRole('button',{name:'提交原事件补投',exact:true});
  let writes=0;const path='/api/v1/admin/payment-messages/outbox/'+original.event_id+'/retry';const count=request=>{if(request.method()==='POST'&&new URL(request.url()).pathname===path)writes++;};platform.on('request',count);
  try{
   await expect(submit).toBeDisabled();await expect(field(detail,'补投原因')).toHaveValue('');assert.equal(writes,0);
   await field(detail,'补投原因').fill('P0浏览器受控补投演练：拓扑已核验，沿用原成功支付事实；核对重复消费幂等，不另建事件。');
   await expect(submit).toBeEnabled();const result=await clickResponse(platform,submit,path);assert.equal(result.id,original.event_id);assert.match(result.operationId,/^mop_[a-f0-9]{32}$/);assert.equal(writes,1);
   await poll(()=>read(platform,ADMIN,'/admin/payment-messages/outbox?page=1&pageSize=100'),value=>items(value).some(r=>r.event_id===original.event_id&&r.status==='SENT'),90);
   const after=items(await read(platform,ADMIN,'/admin/payment-messages/outbox?page=1&pageSize=100')).filter(r=>r.aggregate_id===paymentNo&&r.event_type==='PaymentSucceeded').map(r=>r.event_id).sort();assert.deepEqual(after,before);
   assert.equal((await read(buyer,USER,'/payments/'+paymentNo)).status,'SUCCEEDED');
   await expect(platform.getByRole('status').filter({hasText:'补投请求已提交'})).toBeVisible();
   // 仅公开原业务编号和真实响应operationId；主线用隔离DB只读检查该审计和原wire摘要。
   return [original.event_id,result.operationId,paymentNo];
  }finally{platform.off('request',count);}
 });
 await check('15-platform-real-merchant-admin-recovery',platform,async()=>{
  // 独立于交易阶段：只使用API预先注册的无业务账号；所有授权写入必须来自真实平台表单。
  const account=seeds.accounts.outsider;assert.ok(account?.username&&account.password,'OutsiderAccountSeedRequired');
  if(!tokens.has(platform))await login(platform,ADMIN,seeds.accounts.platform);
  await platform.goto(ADMIN+'/platform/users');await expect(platform.getByRole('heading',{name:'账号与权限',exact:true})).toBeVisible();
  // 当前账号页没有搜索输入；按真实分页查找用户名，不能借助内部API或凭据构造目标编号。
  let found;
  for(let page=0;page<100;page++){
   await expect(platform.locator('.el-table__body tr').first()).toBeVisible();
   const candidate=tableRow(platform,account.username);
   if(await candidate.count()){found=candidate;break;}
   const next=platform.getByRole('button',{name:'下一页',exact:true});if(!await next.isEnabled())break;
   await clickResponse(platform,next,'/api/v1/admin/users','GET');
  }
  assert.ok(found,'OutsiderVisibleInRealUserListRequired');
  const userId=(await found.locator('td').first().innerText()).trim();assert.match(userId,/^[1-9][0-9]{0,18}$/);
  await platform.getByRole('button',{name:'维护商家成员',exact:true}).click();const member=dialog(platform,'维护商家成员');await expect(member).toBeVisible();
  await field(member,'目标用户编号').fill(userId);await field(member,'目标商家编号').fill(seeds.merchants.b);
  await member.locator('.el-form-item').filter({hasText:'本商家职级'}).locator('.el-select').click();
  await platform.getByRole('option',{name:'管理员',exact:true}).click();
  await field(member,'操作原因').fill('P0浏览器平台恢复演练：为实际商家的无业务有效账号显式补授本店ADMIN，核验最新能力。');
  const pending=platform.waitForResponse(response=>match(response,'/api/v1/admin/merchant-members','POST'));
  await member.getByRole('button',{name:'确认提交',exact:true}).click();const response=await pending;
  const submitted=response.request().postDataJSON();assert.equal(submitted.userId,userId);assert.equal(submitted.merchantId,seeds.merchants.b);assert.equal(submitted.role,'ADMIN');assert.equal(submitted.status,'ACTIVE');
  const updated=await data(response);assert.equal(updated.merchantRoles?.[seeds.merchants.b],'ADMIN');
  await expect(member).toBeHidden();await expect(platform.getByRole('status').filter({hasText:'账号管理请求已返回'})).toBeVisible();
  const recovered=await newPage();await login(recovered,ADMIN,account,'/merchant/settings');
  const capabilities=await read(recovered,ADMIN,'/users/me/capabilities');assert.equal(capabilities.userId,userId);assert.equal(capabilities.merchantRoles?.[seeds.merchants.b],'ADMIN');
  await chooseMerchant(recovered,seeds.merchants.b);await expect(recovered.getByRole('button',{name:'保存成员关系',exact:true})).toBeVisible();
  await additionalShot(recovered,'15-recovered-merchant-admin');return [userId,seeds.merchants.b];
 });
} catch(error) {
 report.checks.push({name:'browser-runner',passed:false,businessNos:[],screenshots:[],errorType:safeType(error)});
} finally {
 for(const context of contexts)await context.close().catch(()=>{});await browser.close();
 await writeFile(join(out,'report.json'),JSON.stringify(report,null,2));
}
// 网络故障注入会产生预期ConsoleError；任何未捕获pageerror都使总验收失败。
const failed=report.checks.some(check=>!check.passed)||report.pageErrors.some(error=>error.type.startsWith('PageError_'));
process.stdout.write(JSON.stringify({checks:report.checks.length,passed:report.checks.filter(check=>check.passed).length,reportPath:join(out,'report.json')})+'\n');
process.exitCode=failed?1:0;
