import forecastIcon from '@/assets/icons/forecast.png'

export interface NavRoute {
  name: string
  /** Label under the icon in the phone tab bar. */
  short: string
  path: string
  icon?: string
  iconOpacity?: number
  mdiIcon?: string
  /** The phone tab bar uses icon-font icons only. */
  mobileIcon: string
}

export const navRoutes: NavRoute[] = [
  {
    name: 'Live map',
    short: 'Map',
    path: '/map',
    icon: forecastIcon,
    iconOpacity: 1,
    mobileIcon: 'mdi-weather-windy'
  },
  {
    // Each page gets its own icon so the collapsed bar can be read at a glance.
    name: 'Analyze a flight',
    short: 'Analyze',
    path: '/analyze',
    mdiIcon: 'mdi-airplane-search',
    mobileIcon: 'mdi-airplane-search'
  },
  {
    name: 'Follow a flight',
    short: 'Follow',
    path: '/follow',
    mdiIcon: 'mdi-crosshairs-gps',
    mobileIcon: 'mdi-crosshairs-gps'
  },
  {
    name: '3D view',
    short: '3D',
    path: '/3d',
    mdiIcon: 'mdi-cube-outline',
    mobileIcon: 'mdi-cube-outline'
  }
]
