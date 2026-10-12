/**
 * Where Turbol keeps the flights downloaded for offline use and their Flight Packs on the phone: IndexedDB, so both
 * survive airplane mode, reloads and the browser being closed. Slabs are stored by URL as the server sent them (delta-coded, unzipped); a
 * slab URL never changes meaning, so a slab is shared by every manifest that lists it.
 */
import type { SavedFlight } from './flights'

const dbName = 'turbol-fly'
const version = 1

export interface FlyStore {
  flights(): Promise<SavedFlight[]>
  putFlight(flight: SavedFlight): Promise<void>
  /**
   * Changes a saved flight in one step from what is stored, so writers that own different fields (the pack download,
   * the takeoff and GPS) never undo each other. Resolves to the stored result, or undefined if the flight is gone.
   */
  updateFlight(
    id: string,
    change: (flight: SavedFlight) => SavedFlight
  ): Promise<SavedFlight | undefined>
  deleteFlight(id: string): Promise<void>
  hasSlab(url: string): Promise<boolean>
  getSlab(url: string): Promise<Uint8Array | undefined>
  putSlab(url: string, bytes: Uint8Array): Promise<void>
  /** Every stored slab's URL. */
  slabUrls(): Promise<string[]>
  deleteSlab(url: string): Promise<void>
}

let opened: Promise<IDBDatabase> | undefined

function open(): Promise<IDBDatabase> {
  opened ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(dbName, version)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains('flights'))
        db.createObjectStore('flights', { keyPath: 'id' })
      if (!db.objectStoreNames.contains('slabs')) db.createObjectStore('slabs', { keyPath: 'url' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => {
      opened = undefined
      reject(req.error)
    }
  })
  return opened
}

function run<T>(
  store: 'flights' | 'slabs',
  mode: IDBTransactionMode,
  body: (s: IDBObjectStore) => IDBRequest<T>
): Promise<T> {
  return open().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const tx = db.transaction(store, mode)
        const req = body(tx.objectStore(store))
        // Resolve once the write is durable, not just queued.
        tx.oncomplete = () => resolve(req.result)
        tx.onerror = () => reject(tx.error ?? req.error)
        tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'))
      })
  )
}

interface SlabRecord {
  url: string
  bytes: ArrayBuffer
}

/** The phone's IndexedDB. */
export const idbStore: FlyStore = {
  flights: () => run('flights', 'readonly', (s) => s.getAll() as IDBRequest<SavedFlight[]>),
  putFlight: (flight) =>
    run('flights', 'readwrite', (s) => s.put(JSON.parse(JSON.stringify(flight)))).then(
      () => undefined
    ),
  updateFlight: (id, change) =>
    open().then(
      (db) =>
        new Promise<SavedFlight | undefined>((resolve, reject) => {
          const tx = db.transaction('flights', 'readwrite')
          const s = tx.objectStore('flights')
          let changed: SavedFlight | undefined
          const req = s.get(id) as IDBRequest<SavedFlight | undefined>
          req.onsuccess = () => {
            if (!req.result) return
            changed = JSON.parse(JSON.stringify(change(req.result)))
            s.put(changed)
          }
          tx.oncomplete = () => resolve(changed)
          tx.onerror = () => reject(tx.error ?? req.error)
          tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'))
        })
    ),
  deleteFlight: (id) => run('flights', 'readwrite', (s) => s.delete(id)).then(() => undefined),
  hasSlab: (url) => run('slabs', 'readonly', (s) => s.count(url)).then((n) => n > 0),
  getSlab: (url) =>
    run('slabs', 'readonly', (s) => s.get(url) as IDBRequest<SlabRecord | undefined>).then((r) =>
      r ? new Uint8Array(r.bytes) : undefined
    ),
  putSlab: (url, bytes) =>
    run('slabs', 'readwrite', (s) =>
      s.put({ url, bytes: bytes.slice().buffer } satisfies SlabRecord)
    ).then(() => undefined),
  slabUrls: () =>
    run('slabs', 'readonly', (s) => s.getAllKeys()).then((keys) => keys.map((k) => String(k))),
  deleteSlab: (url) => run('slabs', 'readwrite', (s) => s.delete(url)).then(() => undefined)
}

/** An in-memory store with the same behaviour, for tests and for browsers without IndexedDB. */
export function memoryStore(): FlyStore {
  const flights = new Map<string, SavedFlight>()
  const slabs = new Map<string, Uint8Array>()
  const copy = <T>(x: T): T => JSON.parse(JSON.stringify(x))
  return {
    flights: async () => [...flights.values()].map(copy),
    putFlight: async (f) => void flights.set(f.id, copy(f)),
    updateFlight: async (id, change) => {
      const stored = flights.get(id)
      if (!stored) return undefined
      const changed = copy(change(copy(stored)))
      flights.set(id, changed)
      return copy(changed)
    },
    deleteFlight: async (id) => void flights.delete(id),
    hasSlab: async (url) => slabs.has(url),
    getSlab: async (url) => slabs.get(url)?.slice(),
    putSlab: async (url, bytes) => void slabs.set(url, bytes.slice()),
    slabUrls: async () => [...slabs.keys()],
    deleteSlab: async (url) => void slabs.delete(url)
  }
}
