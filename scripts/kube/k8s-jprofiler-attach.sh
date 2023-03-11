#!/bin/bash
set -e

if [[ -z ${K8S_JVM_POD} ]]; then
    echo "K8S_JVM_POD not defined"
    exit 1
fi

EXEC="kubectl exec ${K8S_JVM_POD}"
CP="kubectl cp ${K8S_JVM_POD}"

if [[ -z ${K8S_JVM_PID} ]]; then
    echo "K8S_JVM_PID not defined, pick one:"
    ${EXEC} jps 2>/dev/null
    exit 1
fi

JPROFILER_PACKAGE=jprofiler_linux_11_1_4.tar.gz
JPROFILER_PACKAGE_URL=https://download-gcdn.ej-technologies.com/jprofiler/${JPROFILER_PACKAGE}


if ! ${EXEC} -- find /root/${JPROFILER_PACKAGE} &>/dev/null; then
    echo "${JPROFILER_PACKAGE} not found on the server, copying..."
    ${EXEC} -- wget -O "/root/${JPROFILER_PACKAGE}" "${JPROFILER_PACKAGE_URL}"
    echo "copied"
else
    echo "${JPROFILER_PACKAGE}  already found in the server"
fi

JPROFILER_PORT=31757

${EXEC} -- tar -C /root -xf "/root/${JPROFILER_PACKAGE}"
${EXEC} -- /root/jprofiler10.1/bin/jpenable --pid=${K8S_JVM_PID} --port=${JPROFILER_PORT} --noinput --gui
kubectl port-forward pods/${K8S_JVM_POD} $JPROFILER_PORT:$JPROFILER_PORT
echo "Connect jprofiler to localhost $JPROFILER_PORT"
