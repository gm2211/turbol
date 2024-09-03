import { createRouter, createWebHistory } from 'vue-router'
import FlightsSearchView from '../views/FlightSearchView.vue'
import LiveView from "@/views/LiveView.vue";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/live-view'
    },
    {
      path: '/live-view',
      name: 'live-view-no-selection',
      component: LiveView
    },
    {
      path: '/live-view/:flightNumber',
      name: 'live-view-with-selection',
      component: LiveView
    },
    {
      path: '/search',
      name: 'search',
      component: FlightsSearchView
    },
  ]
})

export default router
