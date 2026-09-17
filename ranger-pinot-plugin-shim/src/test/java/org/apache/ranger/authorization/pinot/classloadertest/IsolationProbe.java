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

package org.apache.ranger.authorization.pinot.classloadertest;

/**
 * Shared marker interface for {@link RangerPluginClassLoaderIsolationTest}. Mirrors the role
 * Hive's {@code HiveAuthorizerFactory}/Pinot's {@code AccessControlFactory} play in the real
 * shim: a type that both the "ambient" classpath and a {@code RangerPluginClassLoader}-isolated
 * classpath can resolve identically (via the classloader's fallback-to-component mechanism), so
 * instances loaded by either side can be referenced through this common supertype.
 */
public interface IsolationProbe {
    String origin();
}
