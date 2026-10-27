import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath,URL} from 'node:url'
// 隔离验收仅允许显式回环网关，避免测试前端误接旧开发环境或外部站点。
const gatewayTarget=process.env.TRADEFLOW_GATEWAY_TARGET||'http://127.0.0.1:8080'
const gatewayUrl=new URL(gatewayTarget)
if(gatewayUrl.protocol!=='http:'||!['127.0.0.1','localhost','[::1]'].includes(gatewayUrl.hostname)||gatewayUrl.username||gatewayUrl.password||gatewayUrl.pathname!=='/'||gatewayUrl.search||gatewayUrl.hash)throw new Error('前端网关目标必须是纯回环HTTP地址')
const gatewayProxy={'/api/v1':{target:gatewayTarget,changeOrigin:false}}
export default defineConfig({plugins:[vue()],resolve:{alias:[{find:/^@tradeflow\/ui$/,replacement:fileURLToPath(new URL('../web-shared/src/index.ts',import.meta.url))}]},server:{host:'127.0.0.1',port:5174,strictPort:true,proxy:gatewayProxy,fs:{allow:[fileURLToPath(new URL('./',import.meta.url)),fileURLToPath(new URL('../web-shared/src',import.meta.url)),fileURLToPath(new URL('../node_modules',import.meta.url))],deny:['.env','.env.*','*.{crt,pem}','**/.git/**','**/.local/**']}},preview:{host:'127.0.0.1',port:5174,strictPort:true,proxy:gatewayProxy},build:{sourcemap:false}})
