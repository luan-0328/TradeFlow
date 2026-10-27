/** 注册表单浏览器回归；API在本测试中受控替身，真实SMTP/Redis/MySQL由UserApplicationTest单独验证。 */
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const require=createRequire(join(ROOT,'.local/p0-browser-tools/package.json'));
const {chromium,expect}=require('playwright/test');
const work=join(ROOT,'.local/verification','email-browser-'+randomUUID());await mkdir(work,{recursive:true});
const reservation=createServer();await new Promise(r=>reservation.listen(0,'127.0.0.1',r));
const port=reservation.address().port;await new Promise(r=>reservation.close(r));
const origin=`http://127.0.0.1:${port}`;
const child=spawn(process.execPath,[join(ROOT,'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',String(port),'--strictPort'],{
  cwd:join(ROOT,'web-user'),windowsHide:true,stdio:'ignore',env:{...process.env,TRADEFLOW_GATEWAY_TARGET:'http://127.0.0.1:9'}});
let browser;
const report={kind:'browser-registration-controlled-api',checks:[]};
try{
  let ready=false;
  for(let i=0;i<100;i++){
    try{if((await fetch(origin,{signal:AbortSignal.timeout(500)})).ok){ready=true;break}}catch{}
    await new Promise(r=>setTimeout(r,100));
  }
  assert.ok(ready,'Owned Vite not ready');
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const context=await browser.newContext({viewport:{width:390,height:844}});
  let mode='ok',sends=0,registered;
  await context.route('**/*',async route=>{
    const url=new URL(route.request().url());
    if(!['127.0.0.1','localhost'].includes(url.hostname))return route.abort();
    if(!url.pathname.startsWith('/api/v1/'))return route.continue();
    let status=200,code='OK',message='成功',data={items:[],total:0,page:1,pageSize:20},headers={};
    if(url.pathname==='/api/v1/auth/email-code'){
      sends++;
      if(mode==='limited'){status=429;code='EMAIL_RATE_LIMITED';message='邮件发送过于频繁';headers={'Retry-After':'120'}}
      else if(mode==='unknown'){status=503;code='MAIL_DELIVERY_UNCONFIRMED';message='邮件发送结果暂未确认'}
      else data={retryAfterSeconds:60,expiresInSeconds:300};
    }
    if(url.pathname==='/api/v1/auth/register'){
      registered=route.request().postDataJSON();status=201;
      data={userId:'123',username:registered.email,displayName:registered.displayName,status:'ACTIVE',roles:['USER']};
    }
    return route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({code,message,data})});
  });
  const page=await context.newPage();await page.goto(origin);
  await page.getByRole('button',{name:'还没有账号？邮箱注册',exact:true}).click();
  await page.getByLabel('邮箱',{exact:true}).fill('buyer@example.test');
  await page.getByRole('button',{name:'发送验证码',exact:true}).click();
  await expect(page.getByRole('button',{name:/秒后可重新发送/})).toBeDisabled();
  await expect(page.getByRole('status')).toContainText('5分钟');
  assert.equal(sends,1);report.checks.push('发送成功倒计时和禁止重复点击');
  await page.reload();await page.getByRole('button',{name:'还没有账号？邮箱注册',exact:true}).click();
  await page.getByLabel('邮箱',{exact:true}).fill('buyer@example.test');
  await expect(page.getByRole('button',{name:/秒后可重新发送/})).toBeDisabled();assert.equal(sends,1);
  report.checks.push('刷新后同邮箱恢复倒计时');
  await page.getByLabel('邮箱验证码',{exact:true}).fill('123456');
  await page.getByLabel('邮箱',{exact:true}).fill('limited@example.test');
  await expect(page.getByLabel('邮箱验证码',{exact:true})).toHaveValue('');
  mode='limited';await page.getByRole('button',{name:'发送验证码',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('邮件发送过于频繁');
  await expect(page.getByRole('button',{name:/1[12][0-9]秒后可重新发送/})).toBeDisabled();
  report.checks.push('切换邮箱清空验证码，429采用后端Retry-After');
  await page.getByLabel('邮箱',{exact:true}).fill('unknown@example.test');
  mode='unknown';await page.getByRole('button',{name:'发送验证码',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('发送结果暂未确认');
  await expect(page.getByRole('button',{name:/秒后可重新发送/})).toBeDisabled();
  report.checks.push('503不自动重发，保守冷却并展示未知结果');
  await page.getByLabel('邮箱',{exact:true}).fill('new@example.test');
  mode='ok';await page.getByRole('button',{name:'发送验证码',exact:true}).click();
  await page.getByLabel('邮箱验证码',{exact:true}).fill('123456');
  await page.getByLabel('显示名称',{exact:true}).fill('邮箱买家');
  await page.getByLabel('密码',{exact:true}).fill('Example-Password1!');
  await page.screenshot({path:join(work,'registration-mobile.png'),fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
  await page.getByRole('button',{name:'注册账号',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('注册成功');
  assert.deepEqual(registered,{email:'new@example.test',password:'Example-Password1!',displayName:'邮箱买家',code:'123456'});
  await expect(page.getByLabel('密码',{exact:true})).toHaveValue('');
  await expect(page.getByLabel('邮箱 / 历史用户名',{exact:true})).toHaveValue('new@example.test');
  report.checks.push('390px布局不溢出，注册字段契约正确，成功回登录并清除密码');
  report.status='PASS';
}finally{
  if(browser)await browser.close();
  if(child.exitCode===null){child.kill();await new Promise(r=>child.once('exit',r))}
  report.ownedViteStopped=child.exitCode!==null||child.signalCode!==null;
  await writeFile(join(work,'result.json'),JSON.stringify(report,null,2));
}
console.log(JSON.stringify({result:join(work,'result.json'),...report}));
