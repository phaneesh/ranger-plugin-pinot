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

# enable-pinot-plugin.sh — install the Ranger Pinot plugin into a Pinot install.
# Pinot's config is java-properties (.conf) files, not Hadoop *-site.xml, so this
# script renders the Ranger XML configs from install.properties with sed instead
# of Ranger's generic XmlConfigChanger/JCEKS flow.
#
# Idempotent: safe to re-run.

PROJ_INSTALL_DIR=$(cd "$(dirname "$0")" && pwd)
INSTALL_ARGS="${PROJ_INSTALL_DIR}/install.properties"
CONF_TEMPLATES="${PROJ_INSTALL_DIR}/install/conf.templates/enable"
STAMP=$(date '+%Y%m%d%H%M%S')

BROKER_ACCESS_CONTROL_FACTORY="org.apache.ranger.authorization.pinot.authorizer.broker.RangerPinotAccessControlFactory"
CONTROLLER_ACCESS_CONTROL_FACTORY="org.apache.ranger.authorization.pinot.authorizer.controller.RangerPinotAccessControlFactory"

log() {
    echo "+ $(date) : $*"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

# getInstallProperty <name> — read KEY=VALUE from install.properties
getInstallProperty() {
    grep "^${1}[ \t]*=" "${INSTALL_ARGS}" 2>/dev/null | tail -n 1 | cut -d= -f2- | sed -e 's/^[ \t]*//' -e 's/[ \t]*$//'
}

# applyChanges <changes.cfg> <xml-file> — sed-render %PLACEHOLDER% values.
# The .cfg format mirrors Ranger's (name value mod ...) so the diff vs Ranger
# convention stays minimal; our renderer is plain sed because the values land
# in properties, not Hadoop XML.
applyChanges() {
    local cfgFile=$1
    local xmlFile=$2
    local line name value placeholder

    while IFS= read -r line; do
        line=$(echo "${line}" | sed -e 's/^[ \t]*//' -e 's/[ \t]*$//')
        case "${line}" in ''|'#'*) continue ;; esac

        name=$(echo "${line}"  | awk '{print $1}')
        value=$(echo "${line}" | awk '{print $2}')

        # value is either a %PLACEHOLDER% resolved from install.properties or a literal
        if echo "${value}" | grep -q '^%.*%$'; then
            placeholder=$(echo "${value}" | sed -e 's/^%//' -e 's/%$//')
            value=$(getInstallProperty "${placeholder}")
            if [ -z "${value}" ]; then
                log "WARN: property ${placeholder} not set in ${INSTALL_ARGS}; leaving ${name} unchanged"
                continue
            fi
        fi

        if grep -q "<name>${name}</name>" "${xmlFile}"; then
            # replace the <value>...</value> that follows <name>...</name>
            sed -i.bak -e "/<name>${name}<\/name>/{n;s|<value>[^<]*</value>|<value>${value}</value>|}" "${xmlFile}" || \
                die "failed to update ${name} in ${xmlFile}"
            rm -f "${xmlFile}.bak"
        else
            log "WARN: ${name} not found in ${xmlFile}; skipping"
        fi
    done < "${cfgFile}"
}

# setConfProperty <file> <key> <value> — idempotent append/update in a .conf file
setConfProperty() {
    local file=$1 key=$2 value=$3
    touch "${file}"
    if grep -q "^${key}=" "${file}"; then
        sed -i.bak "s|^${key}=.*|${key}=${value}|" "${file}" || die "failed to update ${key} in ${file}"
        rm -f "${file}.bak"
    else
        cp "${file}" "${file}.ranger.${STAMP}" 2>/dev/null || true
        echo "${key}=${value}" >> "${file}"
    fi
}

# removeConfProperty <file> <key>
removeConfProperty() {
    local file=$1 key=$2
    [ -f "${file}" ] || return 0
    if grep -q "^${key}=" "${file}"; then
        cp "${file}" "${file}.ranger.${STAMP}" 2>/dev/null || true
        grep -v "^${key}=" "${file}" > "${file}.tmp" || true
        cat "${file}.tmp" > "${file}"
        rm -f "${file}.tmp"
    fi
}

# ---------------------------------------------------------------------------
# Read and validate install.properties
# ---------------------------------------------------------------------------

[ -f "${INSTALL_ARGS}" ] || die "install.properties not found at ${INSTALL_ARGS}"

POLICY_MGR_URL=$(getInstallProperty 'POLICY_MGR_URL')
REPOSITORY_NAME=$(getInstallProperty 'REPOSITORY_NAME')

[ -n "${POLICY_MGR_URL}" ]   || die "POLICY_MGR_URL is not set in ${INSTALL_ARGS}"
[ -n "${REPOSITORY_NAME}" ] || die "REPOSITORY_NAME is not set in ${INSTALL_ARGS}"

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
[ -d "${PINOT_HOME}" ] || die "Pinot install dir [${PINOT_HOME}] not found (set COMPONENT_INSTALL_DIR_NAME in install.properties)"

PINOT_LIB_DIR="${PINOT_HOME}/lib"
[ -d "${PINOT_LIB_DIR}" ] || die "Pinot lib dir [${PINOT_LIB_DIR}] not found"
mkdir -p "${PINOT_LIB_DIR}"

PINOT_BROKER_CONF_DIR=$(getInstallProperty 'PINOT_BROKER_CONF_DIR')
[ -n "${PINOT_BROKER_CONF_DIR}" ] || PINOT_BROKER_CONF_DIR="${PINOT_HOME}/conf"
PINOT_CONTROLLER_CONF_DIR=$(getInstallProperty 'PINOT_CONTROLLER_CONF_DIR')
[ -n "${PINOT_CONTROLLER_CONF_DIR}" ] || PINOT_CONTROLLER_CONF_DIR="${PINOT_HOME}/conf"
mkdir -p "${PINOT_BROKER_CONF_DIR}" "${PINOT_CONTROLLER_CONF_DIR}"

POLICY_CACHE_FILE_PATH=$(getInstallProperty 'POLICY_CACHE_FILE_PATH')
[ -n "${POLICY_CACHE_FILE_PATH}" ] || POLICY_CACHE_FILE_PATH="/etc/ranger/${REPOSITORY_NAME}/policycache"

SSL_CONFIG_FILE=$(getInstallProperty 'SSL_KEYSTORE_FILE_PATH')
[ -z "${SSL_CONFIG_FILE}" ] && SSL_CONFIG_FILE="/dev/null"

PINOT_ENABLE_ROW_COLUMN_LEVEL_AUTH=$(getInstallProperty 'PINOT_ENABLE_ROW_COLUMN_LEVEL_AUTH')
[ -n "${PINOT_ENABLE_ROW_COLUMN_LEVEL_AUTH}" ] || PINOT_ENABLE_ROW_COLUMN_LEVEL_AUTH="true"

# ---------------------------------------------------------------------------
# 1. Copy + render the Ranger XML configs into the conf dirs
# ---------------------------------------------------------------------------

log "Installing Ranger configs into broker conf [${PINOT_BROKER_CONF_DIR}] and controller conf [${PINOT_CONTROLLER_CONF_DIR}]"

for cf in "${CONF_TEMPLATES}"/*; do
    cfb=$(basename "${cf}")
    for destDir in "${PINOT_BROKER_CONF_DIR}" "${PINOT_CONTROLLER_CONF_DIR}"; do
        if [ -f "${destDir}/${cfb}" ]; then
            log "Saving existing ${destDir}/${cfb} to ${destDir}/.${cfb}.${STAMP}"
            cp "${destDir}/${cfb}" "${destDir}/.${cfb}.${STAMP}"
        fi
        cp "${cf}" "${destDir}/${cfb}" || die "failed to copy ${cfb} to ${destDir}"
    done
done

# Render %PLACEHOLDER% values via the changes.cfg files (sed, not XmlConfigChanger)
for destDir in "${PINOT_BROKER_CONF_DIR}" "${PINOT_CONTROLLER_CONF_DIR}"; do
    for cfg in "${CONF_TEMPLATES}"/*-changes.cfg; do
        xmlName=$(basename "${cfg}" | sed -e 's:-changes.cfg:.xml:')
        [ -f "${destDir}/${xmlName}" ] || continue
        applyChanges "${cfg}" "${destDir}/${xmlName}"
    done
done

# Copy the version file
if [ -f "${PROJ_INSTALL_DIR}/version" ]; then
    cp "${PROJ_INSTALL_DIR}/version" "${PINOT_BROKER_CONF_DIR}/ranger-pinot-plugin.version"
    cp "${PROJ_INSTALL_DIR}/version" "${PINOT_CONTROLLER_CONF_DIR}/ranger-pinot-plugin.version"
fi

# ---------------------------------------------------------------------------
# 2. Install jars: shim + classloader into lib/, impl dir as their sibling
#    (RangerPluginClassLoader computes lib/ranger-pinot-plugin-impl/ as a
#    sibling of the shim jar's code source)
# ---------------------------------------------------------------------------

log "Installing plugin jars into ${PINOT_LIB_DIR}"

for f in "${PROJ_INSTALL_DIR}/lib/"*; do
    bn=$(basename "${f}")
    if [ -e "${PINOT_LIB_DIR}/${bn}" ]; then
        log "Saving existing ${PINOT_LIB_DIR}/${bn} to ${PINOT_LIB_DIR}/.${bn}.${STAMP}"
        mv "${PINOT_LIB_DIR}/${bn}" "${PINOT_LIB_DIR}/.${bn}.${STAMP}"
    fi
    cp -R "${f}" "${PINOT_LIB_DIR}/" || die "failed to copy ${bn} into ${PINOT_LIB_DIR}"
done

# ---------------------------------------------------------------------------
# 3. Policy cache dir
# ---------------------------------------------------------------------------

if [ ! -d "${POLICY_CACHE_FILE_PATH}" ]; then
    log "Creating policy cache dir ${POLICY_CACHE_FILE_PATH}"
    mkdir -p "${POLICY_CACHE_FILE_PATH}" || die "failed to create ${POLICY_CACHE_FILE_PATH}"
fi

# ---------------------------------------------------------------------------
# 4. Wire Pinot's access-control switch in the .conf files
# ---------------------------------------------------------------------------

log "Setting pinot.broker.access.control.class in ${PINOT_BROKER_CONF_DIR}"
setConfProperty "${PINOT_BROKER_CONF_DIR}/pinot-broker.conf" "pinot.broker.access.control.class" "${BROKER_ACCESS_CONTROL_FACTORY}"
setConfProperty "${PINOT_BROKER_CONF_DIR}/pinot-broker.conf" "pinot.broker.enable.row.column.level.auth" "${PINOT_ENABLE_ROW_COLUMN_LEVEL_AUTH}"

log "Setting controller.admin.access.control.factory.class in ${PINOT_CONTROLLER_CONF_DIR}"
setConfProperty "${PINOT_CONTROLLER_CONF_DIR}/pinot-controller.conf" "controller.admin.access.control.factory.class" "${CONTROLLER_ACCESS_CONTROL_FACTORY}"

log "Ranger plugin for Pinot has been enabled."
log "Next steps:"
log "  1. Review ${PINOT_BROKER_CONF_DIR}/ranger-pinot-security.xml (service name: ${REPOSITORY_NAME}, admin URL: ${POLICY_MGR_URL})"
log "  2. Restart the Pinot broker and controller for changes to take effect."

exit 0
