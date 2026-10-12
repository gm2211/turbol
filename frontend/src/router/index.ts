import { START_LOCATION, createRouter, createWebHistory } from 'vue-router'
import { downloads, inOrder } from '@/fly/downloads'
import { estimate } from '@/fly/position'
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
      // Flights downloaded for offline use open in Analyze now; links to the old Fly page still find theirs.
      path: '/fly',
      redirect: (to) => ({ path: '/analyze', query: to.query })
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

/**
 * Opening Turbol with no connection, or while a downloaded flight is in the air, goes straight to that flight: the
 * live map needs the network, and plane Wi-Fi is better spent on the flight's turbulence. A link to the old Fly page
 * opens the flight it would have shown.
 */
router.beforeEach(async (to, from) => {
  const opening =
    from === START_LOCATION && to.path === '/map' && Object.keys(to.query).length === 0
  const oldFly = to.redirectedFrom?.path === '/fly' && !to.query.flight
  if (!opening && !oldFly) return
  // Never hold the app back on a slow or blocked IndexedDB.
  await Promise.race([downloads.load(), new Promise((done) => setTimeout(done, 1500))])
  const now = Date.now()
  const flight = inOrder(downloads.flights.value, now)[0]
  if (!flight) return
  const phase = estimate(flight, now).phase
  if (oldFly || (phase !== 'landed' && (!navigator.onLine || phase === 'air')))
    return { path: '/analyze', query: { flight: flight.id } }
})

export default router
