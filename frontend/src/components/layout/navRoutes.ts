export interface NavRoute {
  name: string
  /** Label under the icon in the phone tab bar. */
  short: string
  path: string
  mdiIcon: string
  /** Adds a pulsing dot to the desktop header tab, for pages showing live data. */
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
    // Flights downloaded for offline use live here too, at the top of the page.
    name: 'Analyze a flight',
    short: 'Analyze',
    path: '/analyze',
    mdiIcon: 'mdi-airplane-search',
    mobileIcon: 'mdi-airplane-search'
  }
]
