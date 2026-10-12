export interface NavRoute {
  name: string
  /** Label under the icon in the phone tab bar. */
  short: string
  path: string
  mdiIcon: string
  /** Adds a pulsing dot to the desktop icon, for pages showing live data. */
  live?: boolean
  /** The phone tab bar uses icon-font icons only. */
  mobileIcon: string
}

export const navRoutes: NavRoute[] = [
  {
    name: 'Live map',
    short: 'Map',
    path: '/map',
    mdiIcon: 'mdi-map-outline',
    live: true,
    mobileIcon: 'mdi-map-outline'
  },
  {
    // Each page gets its own icon so the collapsed bar can be read at a glance.
    name: 'Analyze a flight',
    short: 'Analyze',
    path: '/analyze',
    mdiIcon: 'mdi-airplane-search',
    mobileIcon: 'mdi-airplane-search'
  }
]
