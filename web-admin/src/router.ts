import {createRouter,createWebHistory} from 'vue-router'
import {h} from 'vue'
const Products=()=>import('./pages/Products.vue')
const Orders=()=>import('./pages/Orders.vue')
const Aftersales=()=>import('./pages/Aftersales.vue')
const Users=()=>import('./pages/Users.vue')
const Operations=()=>import('./pages/Operations.vue')
const PendingTasks=()=>import('./pages/PendingTasks.vue')
const Reconciliation=()=>import('./pages/Reconciliation.vue')
const Merchants=()=>import('./pages/Merchants.vue')
const ShippingRule=()=>import('./pages/ShippingRule.vue')
const Governance=()=>import('./pages/Governance.vue')
const Settlement=()=>import('./pages/Settlement.vue')
export const navigation=[
 {path:'/merchant/settings',label:'商家与成员',cap:'MERCHANT_PRODUCTS',section:'商家工作台'},
 {path:'/merchant/shipping',label:'运费规则',cap:'MANAGE_PRODUCTS',section:'商家工作台'},
 {path:'/merchant/settlement',label:'结算账单',cap:'MERCHANT_ORDERS',section:'商家工作台'},
 {path:'/platform/merchants',label:'商家入驻',cap:'PLATFORM_USERS',section:'平台管理'},
 {path:'/platform/disputes',label:'争议与审计',cap:'PLATFORM_USERS',section:'平台管理'},
 {path:'/platform/settlement',label:'模拟结算',cap:'PLATFORM_FUNDS',section:'平台管理'},
 {path:'/merchant/products',label:'商品与库存',cap:'MERCHANT_PRODUCTS',section:'商家工作台'},
 {path:'/merchant/orders',label:'订单履约',cap:'MERCHANT_ORDERS',section:'商家工作台'},
 {path:'/merchant/aftersales',label:'售后协作',cap:'MERCHANT_AFTERSALES',section:'商家工作台'},
 {path:'/platform/users',label:'账号与权限',cap:'PLATFORM_USERS',section:'平台管理'},
 {path:'/platform/funds',label:'资金流水',cap:'PLATFORM_FUNDS',section:'平台管理'},
 {path:'/platform/reconciliation',label:'交易对账',cap:'PLATFORM_RECONCILIATION',section:'平台管理'},
 {path:'/platform/operations',label:'人工待办',cap:'PLATFORM_OPERATIONS',section:'平台管理'},
]
export default createRouter({history:createWebHistory(),routes:[
 {path:'/merchant/settings',component:Merchants,meta:{cap:'MERCHANT_PRODUCTS'}},
 {path:'/merchant/shipping',component:ShippingRule,meta:{cap:'MANAGE_PRODUCTS'}},
 {path:'/merchant/settlement',component:Settlement,meta:{cap:'MERCHANT_ORDERS'}},
 {path:'/platform/merchants',component:Merchants,meta:{cap:'PLATFORM_USERS'}},
 {path:'/platform/disputes',component:Governance,meta:{cap:'PLATFORM_USERS'}},
 {path:'/platform/settlement',component:Settlement,props:{platform:true},meta:{cap:'PLATFORM_FUNDS'}},
 {path:'/',component:{render:()=>h('div',{class:'welcome'},[h('h1','交易协作工作台'),h('p','从左侧选择已授权的业务模块，查询真实业务记录。')])}},
 {path:'/merchant/products',component:Products,meta:{cap:'MERCHANT_PRODUCTS'}},
 {path:'/merchant/orders',component:Orders,meta:{cap:'MERCHANT_ORDERS'}},
 {path:'/merchant/aftersales',component:Aftersales,meta:{cap:'MERCHANT_AFTERSALES'}},
 {path:'/platform/users',component:Users,meta:{cap:'PLATFORM_USERS'}},
 {path:'/platform/funds',component:Operations,props:{funds:true},meta:{cap:'PLATFORM_FUNDS'}},
 {path:'/platform/reconciliation',component:Reconciliation,meta:{cap:'PLATFORM_RECONCILIATION'}},
 {path:'/platform/operations',component:PendingTasks,meta:{cap:'PLATFORM_OPERATIONS'}},
 {path:'/:pathMatch(.*)*',redirect:'/'},
]})
