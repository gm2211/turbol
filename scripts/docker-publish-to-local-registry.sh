#!/bin/bash --
docker run -d -p 5050:5000 --restart=always --name registry registry:2 || true
sbt docker || true
docker tag turbol:latest localhost:5050/turbol || true
docker push localhost:5050/turbol ||true
