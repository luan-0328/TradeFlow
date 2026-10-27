import { createRouter, createWebHistory } from 'vue-router'
import Catalog from './views/Catalog.vue'
import Cart from './views/Cart.vue'
import Orders from './views/Orders.vue'
import OrderDetail from './views/OrderDetail.vue'
import RequestProgress from './views/RequestProgress.vue'
import Aftersales from './views/Aftersales.vue'
import AftersaleNew from './views/AftersaleNew.vue'
import AftersaleDetail from './views/AftersaleDetail.vue'
const Account=()=>import('./views/Account.vue')
const TradeGroups=()=>import('./views/TradeGroups.vue')
const TradeGroup=()=>import('./views/TradeGroup.vue')
const Disputes=()=>import('./views/Disputes.vue')
export default createRouter({history:createWebHistory(),routes:[{path:'/account',component:Account},{path:'/trade-groups',component:TradeGroups},{path:'/trade-groups/:no',component:TradeGroup},{path:'/disputes',component:Disputes},{path:'/',component:Catalog},{path:'/products/:id',component:Catalog},{path:'/cart',component:Cart},{path:'/orders',component:Orders},{path:'/orders/requests/:no',component:RequestProgress},{path:'/orders/:no',component:OrderDetail},{path:'/aftersales',component:Aftersales},{path:'/aftersales/new/:no',component:AftersaleNew},{path:'/aftersales/:no',component:AftersaleDetail},{path:'/:pathMatch(.*)*',redirect:'/'}]})
