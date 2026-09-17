#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# disable-pinot-plugin.sh — remove the Ranger Pinot plugin from a Pinot install.
# Removes exactly what enable-pinot-plugin.sh added: the access-control config
# keys, the copied jars/impl dir, and the Ranger XML configs (.bak copies are
# left behind). Idempotent.

PROJ_INSTALL_DIR=$(cd "$(dirname "$0")" && pwd)
INSTALL_ARGS="${PROJ_INSTALL_DIR}/install.properties"
STAMP=$(date '+%Y%m%d%H%M%S')

RANGER_CONF_FILES="ranger-pinot-security.xml ranger-pinot-audit.xml ranger-policymgr-ssl.xml ranger-pinot-security-changes.cfg ranger-pinot-audit-changes.cfg ranger-policymgr-ssl-changes.cfg ranger-pinot-plugin.version"

log() {
    echo "+ $(date) : $*"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

getInstallProperty() {
    grep "^${1}[ \t]*=" "${INSTALL_ARGS}" 2>/dev/null | tail -n 1 | cut -d= -f2- | sed -e 's/^[ \t]*//' -e 's/[ \t]*$//'
}

# removeConfProperty <file> <key> — drop key, backing up first if present
removeConfProperty() {
    local file=$1 key=$2
    [ -f "${file}" ] || return 0
    if grep -q "^${key}=" "${file}"; then
        log "Removing ${key} from ${file} (backup: ${file}.ranger.${STAMP})"
        cp "${file}" "${file}.ranger.${STAMP}"
        grep -v "^${key}=" "${file}" > "${file}.tmp" || true
        cat "${file}.tmp" > "${file}"
        rm -f "${file}.tmp"
    fi
}

# ---------------------------------------------------------------------------
# Locate the Pinot install (same resolution as enable-pinot-plugin.sh)
# ---------------------------------------------------------------------------

[ -f "${INSTALL_ARGS}" ] || die "install.properties not found at ${INSTALL_ARGS}"

REPOSITORY_NAME=$(getInstallProperty 'REPOSITORY_NAME')
[ -n "${REPOSITORY_NAME}" ] || REPOSITORY_NAME="pinotdev"

COMPONENT_INSTALL_DIR_NAME=$(getInstallProperty 'COMPONENT_INSTALL_DIR_NAME')
[ -n "${COMPONENT_INSTALL_DIR_NAME}" ] || COMPONENT_INSTALL_DIR_NAME="../pinot"

firstChar=${COMPONENT_INSTALL_DIR_NAME:0:1}
if [ "${firstChar}" = "/" ]; then
    PINOT_HOME=${COMPONENT_INSTALL_DIR_NAME}
else
    # relative paths resolve against the extracted plugin directory itself,
    # so the default "../pinot" means a sibling of ranger-*-pinot-plugin/
    PINOT_HOME="${PROJ_INSTALL_DIR}/${COMPONENT_INSTALL_DIR_NAME}"
fi
[ -d "${PINOT_HOME}" ] || die "Pinot install dir [${PINOT_HOME}] not found"

PINOT_LIB_DIR="${PINOT_HOME}/lib"

PINOT_BROKER_CONF_DIR=$(getInstallProperty 'PINOT_BROKER_CONF_DIR')
[ -n "${PINOT_BROKER_CONF_DIR}" ] || PINOT_BROKER_CONF_DIR="${PINOT_HOME}/conf"
PINOT_CONTROLLER_CONF_DIR=$(getInstallProperty 'PINOT_CONTROLLER_CONF_DIR')
[ -n "${PINOT_CONTROLLER_CONF_DIR}" ] || PINOT_CONTROLLER_CONF_DIR="${PINOT_HOME}/conf"

# ---------------------------------------------------------------------------
# 1. Remove the access-control config keys the enable script set
# ---------------------------------------------------------------------------

removeConfProperty "${PINOT_BROKER_CONF_DIR}/pinot-broker.conf" "pinot.broker.access.control.class"
removeConfProperty "${PINOT_BROKER_CONF_DIR}/pinot-broker.conf" "pinot.broker.enable.row.column.level.auth"
removeConfProperty "${PINOT_CONTROLLER_CONF_DIR}/pinot-controller.conf" "controller.admin.access.control.factory.class"

# ---------------------------------------------------------------------------
# 2. Remove the copied Ranger conf files (.bak copies left behind)
# ---------------------------------------------------------------------------

for confDir in "${PINOT_BROKER_CONF_DIR}" "${PINOT_CONTROLLER_CONF_DIR}"; do
    for f in ${RANGER_CONF_FILES}; do
        if [ -f "${confDir}/${f}" ]; then
            log "Moving ${confDir}/${f} to ${confDir}/${f}.disabled.${STAMP}"
            mv "${confDir}/${f}" "${confDir}/${f}.disabled.${STAMP}"
        fi
    done
done

# ---------------------------------------------------------------------------
# 3. Remove the plugin jars from Pinot's lib dir (backup as .disabled)
# ---------------------------------------------------------------------------

for f in "${PINOT_LIB_DIR}/"ranger-pinot-plugin-shim-*.jar "${PINOT_LIB_DIR}/"ranger-plugin-classloader-*.jar; do
    if [ -e "${f}" ]; then
        bn=$(basename "${f}")
        log "Moving ${f} to ${PINOT_LIB_DIR}/.${bn}.disabled.${STAMP}"
        mv "${f}" "${PINOT_LIB_DIR}/.${bn}.disabled.${STAMP}"
    fi
done

if [ -d "${PINOT_LIB_DIR}/ranger-pinot-plugin-impl" ]; then
    log "Moving ${PINOT_LIB_DIR}/ranger-pinot-plugin-impl to ${PINOT_LIB_DIR}/.ranger-pinot-plugin-impl.disabled.${STAMP}"
    mv "${PINOT_LIB_DIR}/ranger-pinot-plugin-impl" "${PINOT_LIB_DIR}/.ranger-pinot-plugin-impl.disabled.${STAMP}"
fi

log "Ranger plugin for Pinot has been disabled."
log "Please restart the Pinot broker and controller for changes to take effect."

exit 0
