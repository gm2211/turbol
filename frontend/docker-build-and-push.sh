#!/bin/bash
script_path="$(readlink -f "$0")"
script_dir="$(cd -P -- "$(dirname -- "$script_path")" && pwd -P)"
orig_dir="$(pwd)"

# 0. Go to the frontend directory
cd "${script_dir}" || exit

# Build and push
./compile-and-build-docker.sh
./docker-push.sh

# 2. Go back to the original directory
cd "${orig_dir}" || exit
