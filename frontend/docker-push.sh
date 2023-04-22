#!/bin/bash
script_path="$(readlink -f "$0")"
script_dir="$(cd -P -- "$(dirname -- "$script_path")" && pwd -P)"
orig_dir="$(pwd)"

# 0. Go to the frontend directory
cd "${script_dir}" || exit

# 1. Check if docker buildx is installed
if ! docker buildx inspect multi-arch-builder > /dev/null 2>&1; then
  docker buildx create --use --name multi-arch-builder "$PWD"
fi

# 2. Creates a Docker image named turbol and sets the version using git tag
tag=$(git describe --tag)
image_name="docker.io/gm2211/turbol-fe"

if [ -n "$tag" ]
then
echo "Building image ${image_name}:${tag} and pushing it to Docker Hub..."
docker buildx build --platform=linux/arm64,linux/amd64 --push -t "${image_name}":"${tag}" .
fi

echo "Building image ${image_name}:latest and pushing it to Docker Hub..."
docker buildx build --platform=linux/arm64,linux/amd64 --push -t "${image_name}:latest" .

if [ -n "$tag" ]
then
echo "Building image ${image_name}:${tag} latest locally..."
docker build -t "${image_name}":"${tag}" .
fi

echo "Building image and ${image_name}:latest locally..."
docker build -t "${image_name}:latest" .

# 3. Go back to the original directory
cd "${orig_dir}" || exit
