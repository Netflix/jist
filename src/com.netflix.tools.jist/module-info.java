/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

/**
 * Enumerates Java compilation contexts and renders source-enhanced declarations.
 *
 * @mainClass com.netflix.tools.jist.Jist
 */
module com.netflix.tools.jist {
    requires java.compiler;
    requires jdk.compiler;

    exports com.netflix.tools.jist to com.netflix.tools.jist.test;

    provides java.util.spi.ToolProvider with com.netflix.tools.jist.Jist;
}
