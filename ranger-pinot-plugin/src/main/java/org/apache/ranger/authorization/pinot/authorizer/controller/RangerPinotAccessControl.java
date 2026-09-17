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

package org.apache.ranger.authorization.pinot.authorizer.controller;

import org.apache.pinot.controller.api.access.AccessControl;

/**
 * Phase 1 stub: every {@link AccessControl} method keeps the interface's own default
 * implementation, which allows everything (mirrors Pinot's own
 * {@code AllowAllAccessFactory}). Real Ranger policy enforcement (table CRUD ACLs, cluster
 * actions, audit logging) lands in Phase 4.
 */
public class RangerPinotAccessControl implements AccessControl {
}
