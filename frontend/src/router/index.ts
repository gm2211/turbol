import { createRouter, createWebHistory } from 'vue-router'
import LiveMapView from '@/views/LiveMapView.vue'

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
      component: () => import('@/views/AnalyzeView.vue')
    },
    {
      path: '/follow/:hex?',
      name: 'follow',
      component: () => import('@/views/FollowView.vue')
    },
    {
      // The 3D view lives in the live map now: old links open it in 3D with the flight added.
      path: '/3d',
      redirect: (to) => ({
        path: '/map',
        query: { view: '3d', ...(to.query.q ? { flights: String(to.query.q) } : {}) }
      })
    }
  ]
})

export default router
