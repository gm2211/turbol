import { createRouter, createWebHistory } from 'vue-router'
import LiveMapView from '@/views/LiveMapView.vue'
import AnalyzeView from '@/views/AnalyzeView.vue'
import FollowView from '@/views/FollowView.vue'
import View3DView from '@/views/View3DView.vue'

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
    },
    {
      path: '/3d',
      name: '3d',
      component: View3DView
    }
  ]
})

export default router
