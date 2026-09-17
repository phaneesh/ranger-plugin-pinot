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

# upgrade-pinot-plugin.sh — replace the plugin jars from a new tarball extract,
# keeping user-edited conf files intact (warn + diff if the shipped template
# differs). Usage: run from inside the NEW tarball's extracted directory, with
# the existing (currently installed) tarball directory passed as $1 (or found
# as a sibling ranger-*-pinot-plugin* dir).

PROJ_INSTALL_DIR=$(cd "$(dirname "$0")" && pwd)
STAMP=$(date '+%Y%m%d%H%M%S')

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

# ---------------------------------------------------------------------------
# Locate the previous install: explicit arg, else newest sibling tarball dir
# ---------------------------------------------------------------------------

OLD_DIR=${1:-}
if [ -z "${OLD_DIR}" ]; then
    OLD_DIR=$(ls -dt "${PROJ_INSTALL_DIR}/../"ranger-*-pinot-plugin* 2>/dev/null | grep -v "${PROJ_INSTALL_DIR}" | head -n 1)
fi
[ -n "${OLD_DIR}" ] || die "previous plugin install dir not found; pass it as an argument (the directory of the currently installed tarball)"
[ -d "${OLD_DIR}/lib" ] || die "${OLD_DIR} does not look like a plugin install (no lib/ dir)"

OLD_INSTALL_ARGS="${OLD_DIR}/install.properties"
[ -f "${OLD_INSTALL_ARGS}" ] || die "${OLD_INSTALL_ARGS} not found"

INSTALL_ARGS="${PROJ_INSTALL_DIR}/install.properties"

# ---------------------------------------------------------------------------
# 1. Copy the old install.properties into the new install (reuse settings)
# ---------------------------------------------------------------------------

if [ "${OLD_DIR}" != "${PROJ_INSTALL_DIR}" ]; then
    if [ -f "${INSTALL_ARGS}" ]; then
        cp "${INSTALL_ARGS}" "${INSTALL_ARGS}.ranger.${STAMP}"
    fi
    cp "${OLD_INSTALL_ARGS}" "${INSTALL_ARGS}" || die "failed to copy install.properties from ${OLD_DIR}"
fi

# ---------------------------------------------------------------------------
# 2. Locate Pinot install (same resolution as enable-pinot-plugin.sh)
# ---------------------------------------------------------------------------

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
[ -d "${PINOT_LIB_DIR}" ] || die "Pinot lib dir [${PINOT_LIB_DIR}] not found"

PINOT_BROKER_CONF_DIR=$(getInstallProperty 'PINOT_BROKER_CONF_DIR')
[ -n "${PINOT_BROKER_CONF_DIR}" ] || PINOT_BROKER_CONF_DIR="${PINOT_HOME}/conf"
PINOT_CONTROLLER_CONF_DIR=$(getInstallProperty 'PINOT_CONTROLLER_CONF_DIR')
[ -n "${PINOT_CONTROLLER_CONF_DIR}" ] || PINOT_CONTROLLER_CONF_DIR="${PINOT_HOME}/conf"

# ---------------------------------------------------------------------------
# 3. Back up and replace the installed jars (conf files are NOT touched)
# ---------------------------------------------------------------------------

log "Backing up current jars in ${PINOT_LIB_DIR} to .upgrade.${STAMP} backups"

for f in "${PINOT_LIB_DIR}/"ranger-pinot-plugin-shim-*.jar "${PINOT_LIB_DIR}/"ranger-plugin-classloader-*.jar; do
    [ -e "${f}" ] || continue
    cp "${f}" "${f}.upgrade.${STAMP}" || die "failed to back up ${f}"
    rm -f "${f}" || die "failed to remove ${f}"
done

if [ -d "${PINOT_LIB_DIR}/ranger-pinot-plugin-impl" ]; then
    cp -R "${PINOT_LIB_DIR}/ranger-pinot-plugin-impl" "${PINOT_LIB_DIR}/ranger-pinot-plugin-impl.upgrade.${STAMP}" || \
        die "failed to back up ranger-pinot-plugin-impl"
    rm -rf "${PINOT_LIB_DIR}/ranger-pinot-plugin-impl" || die "failed to remove ranger-pinot-plugin-impl"
fi

log "Installing new jars from ${PROJ_INSTALL_DIR}/lib"
for f in "${PROJ_INSTALL_DIR}/lib/"*; do
    cp -R "${f}" "${PINOT_LIB_DIR}/" || die "failed to copy $(basename "${f}") into ${PINOT_LIB_DIR}"
done

# ---------------------------------------------------------------------------
# 4. Warn about user-edited conf files that differ from the new templates
# ---------------------------------------------------------------------------

for confDir in "${PINOT_BROKER_CONF_DIR}" "${PINOT_CONTROLLER_CONF_DIR}"; do
    for cf in "${PROJ_INSTALL_DIR}/install/conf.templates/enable/"*.xml; do
        cfb=$(basename "${cf}")
        if [ -f "${confDir}/${cfb}" ]; then
            if ! diff -wq "${cf}" "${confDir}/${cfb}" > /dev/null 2>&1; then
                log "WARN: ${confDir}/${cfb} differs from the new template (kept your version); new template saved as ${confDir}/${cfb}.new.${STAMP}"
                cp "${cf}" "${confDir}/${cfb}.new.${STAMP}"
            fi
        else
            log "Installing ${cfb} into ${confDir}"
            cp "${cf}" "${confDir}/${cfb}"
        fi
    done
done

# Version file
if [ -f "${PROJ_INSTALL_DIR}/version" ]; then
    cp "${PROJ_INSTALL_DIR}/version" "${PINOT_BROKER_CONF_DIR}/ranger-pinot-plugin.version" 2>/dev/null || true
    cp "${PROJ_INSTALL_DIR}/version" "${PINOT_CONTROLLER_CONF_DIR}/ranger-pinot-plugin.version" 2>/dev/null || true
fi

log "Ranger Pinot plugin upgraded."
log "Please restart the Pinot broker and controller for changes to take effect."

exit 0
