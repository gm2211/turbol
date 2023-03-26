import { createRouter, createWebHistory } from 'vue-router'
import FlightsView from '../views/TurbulenceView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'flights',
      component: FlightsView
    }
  ]
})

export default router
