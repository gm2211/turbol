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
      // Following a flight now happens on the live map; old links open the map with that flight followed.
      path: '/follow/:hex?',
      redirect: (to) =>
        to.params.hex ? { path: '/map', query: { follow: String(to.params.hex) } } : '/map'
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
