import { createRouter, createWebHistory } from 'vue-router'
import FlightsSearchView from '../views/FlightSearchView.vue'
import ForecastView from "@/views/ForecastView.vue";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/search'
    },
    {
      path: '/search',
      name: 'search',
      component: FlightsSearchView
    },
    {
      path: '/forecast',
      name: 'forecast-no-selection',
      component: ForecastView
    },
    {
      path: '/forecast/:flightNumber/:dateInEpochDays',
      name: 'forecast-with-selection',
      component: ForecastView
    }
  ]
})

export default router
