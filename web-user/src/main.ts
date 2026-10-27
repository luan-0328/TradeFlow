import {createApp} from 'vue'
import App from './App.vue'
import router from './router'
import {configureClient} from '@tradeflow/ui'
import '../../web-shared/src/theme.css'
configureClient('user')
const app=createApp(App)
app.use(router)
app.mount('#app')
