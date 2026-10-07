import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import WineView from '../views/WineView.vue'
import CellarBatchView from '../views/CellarBatchView.vue'
import MemberView from '../views/MemberView.vue'
import SaleOutView from '../views/SaleOutView.vue'

const routes = [
  { path: '/', redirect: '/wines' },
  { path: '/wines', name: 'wines', component: WineView },
  { path: '/cellar-batches', name: 'cellar-batches', component: CellarBatchView },
  { path: '/members', name: 'members', component: MemberView },
  { path: '/sale-outs', name: 'sale-outs', component: SaleOutView },
  { path: '/:pathMatch(.*)*', redirect: '/wines' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
