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
