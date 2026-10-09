[![Scala CI](https://github.com/gm2211/turbol/actions/workflows/scala.yml/badge.svg)](https://github.com/gm2211/turbol/actions/workflows/scala.yml)

## Run locally

`./run.sh` starts Postgres, the backend and the frontend; open http://localhost:5173. Details in [dev/README.md](dev/README.md).

If getting errors about python versions when using `poetry install`, do:
  1. poetry env use <path_to_python_version [e.g. /opt/homebrew/bin/python3]>
  2. poetry install
If getting errors about debugpy when executing `poetry install`, do:
  1. poetry shell
  2. pip install debugpy
  3. exit
  4. poetry install

## Deploy to Render

[render.yaml](render.yaml) is a Render Blueprint: one Docker web service built from the root [Dockerfile](Dockerfile) (the backend serves the API and the built frontend) plus a Render Postgres. In Render, choose New > Blueprint, pick this repository and apply. Pushes to `develop` redeploy.

Both use Render's free plans. The free web service sleeps after 15 idle minutes and its disk is wiped, so the first request after a nap waits for the backend to start and the forecast takes about 2 more minutes to reload. The app fits in the free 512 MB (about 470 MB used with every 3D frame loaded). Switch the service to `starter` to keep it awake.
