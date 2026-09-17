/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.authorization.pinot.authorizer.broker;

import org.apache.pinot.broker.api.AccessControl;
import org.apache.pinot.broker.broker.AccessControlFactory;
import org.apache.pinot.spi.env.PinotConfiguration;
import org.apache.ranger.plugin.classloader.RangerPluginClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the class Pinot's broker actually instantiates by reflection (configured under
 * {@code pinot.broker.access.control.class}), so it must be loaded by Pinot's own classloader
 * and directly extend Pinot's {@link AccessControlFactory}.
 *
 * <p>It shares its fully-qualified class name with the impl-side {@code RangerPinotAccessControlFactory}
 * in the ranger-pinot-plugin module — this is intentional and matches Ranger's own shim
 * convention (e.g. RangerHiveAuthorizerFactory, RangerKafkaAuthorizer): the constructor loads
 * the impl class of the same name from an isolated {@link RangerPluginClassLoader} via
 * {@code Class.forName}, so the impl jar's own dependencies never leak onto Pinot's classpath.
 * The cast back to {@link AccessControlFactory}/{@link AccessControl} is safe because those
 * types are Pinot's own classes, resolved identically on both sides via the classloader's
 * fallback-to-host mechanism.</p>
 *
 * <p>Every delegated call is bracketed by {@code pluginClassLoader.activate()}/{@code deactivate()}
 * (published {@code ranger-plugin-classloader:2.8.0} does not yet contain the newer
 * {@code PluginClassLoaderActivator} AutoCloseable helper, so this mirrors the try/finally style
 * Ranger's own Kafka shim uses at this release).</p>
 */
public class RangerPinotAccessControlFactory extends AccessControlFactory {
    private static final Logger LOG = LoggerFactory.getLogger(RangerPinotAccessControlFactory.class);

    private static final String RANGER_PLUGIN_TYPE                           = "pinot";
    private static final String RANGER_ACCESS_CONTROL_FACTORY_IMPL_CLASSNAME = "org.apache.ranger.authorization.pinot.authorizer.broker.RangerPinotAccessControlFactory";

    private final RangerPluginClassLoader pluginClassLoader;
    private final AccessControlFactory    implFactory;

    public RangerPinotAccessControlFactory() {
        LOG.debug("==> RangerPinotAccessControlFactory.RangerPinotAccessControlFactory()");

        AccessControlFactory createdImplFactory;

        try {
            pluginClassLoader = RangerPluginClassLoader.getInstance(RANGER_PLUGIN_TYPE, this.getClass());

            @SuppressWarnings("unchecked")
            Class<AccessControlFactory> implClass = (Class<AccessControlFactory>) Class.forName(RANGER_ACCESS_CONTROL_FACTORY_IMPL_CLASSNAME, true, pluginClassLoader);

            pluginClassLoader.activate();

            try {
                createdImplFactory = implClass.getDeclaredConstructor().newInstance();
            } finally {
                pluginClassLoader.deactivate();
            }
        } catch (Exception e) {
            LOG.error("Error enabling RangerPinotAccessControlFactory (broker)", e);

            throw new IllegalStateException("Error enabling RangerPinotAccessControlFactory (broker)", e);
        }

        this.implFactory = createdImplFactory;

        LOG.debug("<== RangerPinotAccessControlFactory.RangerPinotAccessControlFactory()");
    }

    @Override
    public void init(PinotConfiguration configuration) {
        pluginClassLoader.activate();

        try {
            implFactory.init(configuration);
        } finally {
            pluginClassLoader.deactivate();
        }
    }

    @Override
    public AccessControl create() {
        pluginClassLoader.activate();

        try {
            return implFactory.create();
        } finally {
            pluginClassLoader.deactivate();
        }
    }
}
