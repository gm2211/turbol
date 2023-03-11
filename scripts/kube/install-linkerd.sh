#!/bin/bash --
script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

linkerd check --pre
linkerd install | kubectl apply -f -
linkerd check
