# Running turbol locally

Needs Docker (for Postgres), JDK 21, sbt and Node 22.

```bash
# 1. Postgres (airport search lives there)
docker compose -f dev/docker-compose.yml up -d

# 2. Backend on :8081 (first start downloads the latest GTG-N nowcast in ~15 s,
#    then the 18-hour GTG forecast in ~2 min; both are cached in var/data/turbulence)
dev/run-backend.sh

# 3. Frontend on :5173 (talks to the backend on :8081)
cd frontend && npm ci && npm run dev
```

Open http://localhost:5173.

## Data sources

| What | Source | Notes |
| --- | --- | --- |
| Turbulence now | NOAA GTG-N nowcast via NOMADS (`gtgn/prod`) | every 15 min, 3 km, CONUS, 51 levels |
| Turbulence ahead | NOAA GTG v4 forecast via NOMADS (`dafs/prod`) | hourly cycles, F001-F018, 12 levels fetched by HTTP Range |
| Live aircraft | [adsb.lol](https://api.adsb.lol) | keyless, ODbL; 250 nm circles cached 60 s (180 s for a whole-country view, positions dead-reckoned), throttled to 24 requests/min |
| Basemap | Esri World Light Gray Canvas | keyless |

All of them are free and need no API key. Turbulence coverage is the contiguous US and nearby (the GTG grid).

## Useful endpoints

- `GET /api/turbulence/status`: loaded nowcast and forecast frames
- `GET /api/turbulence/tiles/{frameId}/{levelFt}/{z}/{x}/{y}.png`: map overlay tiles
- `GET /api/turbulence/point?lat=..&lon=..&alt=..[&time=epochSeconds]`: EDR and category at a point
- `GET /api/live/aircraft?south=..&west=..&north=..&east=..`: live aircraft with the turbulence each one is in
- `GET /api/flights/lookup?q=UA1517`: route (adsbdb) and live position for a flight number or callsign
- `POST /api/flights/analyze`: turbulence forecast along a flight, gate to gate
- `GET /api/live/flights/{hex}/stream`: server-sent events, one update every 10 s for a followed flight
