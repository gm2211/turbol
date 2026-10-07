import { createRouter, createWebHistory } from 'vue-router'
import LiveMapView from '@/views/LiveMapView.vue'
import AnalyzeView from '@/views/AnalyzeView.vue'
import FollowView from '@/views/FollowView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/map'
    },
    {
      path: '/map',
      name: 'map',
      component: LiveMapView
    },
    {
      path: '/analyze',
      name: 'analyze',
      component: AnalyzeView
    },
    {
      path: '/follow/:hex?',
      name: 'follow',
      component: FollowView
    }
  ]
})

export default router
