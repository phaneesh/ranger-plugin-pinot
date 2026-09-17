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

/**
 * Impl-side factory, loaded in isolation by {@code RangerPluginClassLoader} from the shim
 * module's class of the exact same fully-qualified name (Ranger's own classloader-delegation
 * convention — see the shim module's class for the mechanism). Phase 1 is an allow-all stub;
 * {@code RangerBasePlugin} wiring for real broker-side enforcement lands in Phase 2.
 */
public class RangerPinotAccessControlFactory extends AccessControlFactory {
    @Override
    public AccessControl create() {
        return new RangerPinotAccessControl();
    }
}
