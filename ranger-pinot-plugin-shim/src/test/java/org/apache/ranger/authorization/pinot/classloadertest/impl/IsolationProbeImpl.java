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

package org.apache.ranger.authorization.pinot.classloadertest.impl;

import org.apache.ranger.authorization.pinot.classloadertest.IsolationProbe;

/**
 * The "ambient" implementation: compiled normally into this module's {@code target/test-classes},
 * reachable via the test JVM's ordinary classpath. {@link org.apache.ranger.authorization.pinot.classloadertest.RangerPluginClassLoaderIsolationTest}
 * proves isolation by showing that a {@code RangerPluginClassLoader} resolves a <em>different</em>
 * compiled class of this exact fully-qualified name from an isolated impl directory, instead of
 * this one.
 */
public class IsolationProbeImpl implements IsolationProbe {
    @Override
    public String origin() {
        return "AMBIENT";
    }
}
