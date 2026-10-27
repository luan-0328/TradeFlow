import {createApp} from 'vue'
import App from './App.vue'
import router from './router'
import {configureClient} from '@tradeflow/ui'
import '../../web-shared/src/theme.css'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
configureClient('admin')
const app=createApp(App)
app.use(router)
app.use(ElementPlus,{locale:zhCn})
app.mount('#app')
