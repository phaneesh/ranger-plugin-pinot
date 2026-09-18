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

import org.apache.ranger.plugin.classloader.RangerPluginClassLoader;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link RangerPluginClassLoader} genuinely isolates a plugin's implementation
 * classes from the host's ambient classpath, rather than merely compiling the delegation code.
 *
 * <p>Mirrors the approach in Ranger's own {@code ranger-plugin-classloader} module test suite
 * ({@code org.apache.ranger.plugin.classloader.test.impl.TestChildFistClassLoader}, which loads a
 * class with a fixed FQCN from a sibling {@code ranger-<type>-plugin-impl/} directory next to the
 * shim class's own code-source location) — but drives it through JUnit with assertions instead of
 * a manual {@code main()} + {@code System.out.println}, and builds the "impl" version of the class
 * on the fly with the JDK's in-process compiler rather than requiring a separately packaged jar or
 * an extra Maven module. This keeps the proof real (two genuinely different compiled classes of the
 * identical fully-qualified name, resolved through the classloader isolation boundary) without new
 * build machinery.
 *
 * <h2>What this test actually proves, and what it does not</h2>
 * It proves: when a {@code ranger-<type>-plugin-impl/} directory exists next to a shim class's own
 * code-source location (exactly the layout the real distro tarball produces — see
 * {@code RangerPluginClassLoaderUtil.getPluginImplLibPath}), {@link RangerPluginClassLoader} loads
 * the FQCN from THAT isolated directory in preference to an identically-named class already
 * reachable on the ambient test classpath. That is the entire isolation contract our shim relies on.
 * It does not exercise a real packaged jar (a directory of {@code .class} files is used instead,
 * which {@link RangerPluginClassLoader} — a plain {@link java.net.URLClassLoader} — treats
 * identically to a jar as a classpath root) and does not span two separate JVMs; both are
 * acceptable simplifications since the isolation mechanism itself is classloader-boundary logic,
 * not jar-format or process-boundary logic.
 */
class RangerPluginClassLoaderIsolationTest {
    private static final String IMPL_FQCN = "org.apache.ranger.authorization.pinot.classloadertest.impl.IsolationProbeImpl";

    /**
     * Compiles an alternate {@code IsolationProbeImpl} (same FQCN as the ambient one in this
     * module, but returning "ISOLATED" instead of "AMBIENT") into
     * {@code target/ranger-<pluginType>-plugin-impl/}, matching
     * {@code RangerPluginClassLoaderUtil.getPluginImplLibPath}'s hard-coded
     * {@code ranger-%-plugin-impl} directory-naming convention relative to the parent of this
     * test class's own code-source location (which, under {@code mvn test}, is
     * {@code target/test-classes/}, so the sibling directory lands at {@code target/...}).
     */
    private Path compileIsolatedImplInto(String pluginType) throws IOException, URISyntaxException {
        URL testClassesUrl = RangerPluginClassLoaderIsolationTest.class.getProtectionDomain().getCodeSource().getLocation();
        Path testClassesDir = Path.of(testClassesUrl.toURI());
        Path implLibDir = testClassesDir.getParent().resolve("ranger-" + pluginType + "-plugin-impl");
        // RangerPluginClassLoaderUtil.getFilesInDirectory() lists the DIRECT children of implLibDir
        // and adds EACH ONE as its own URLClassLoader root (this is how the real distro tarball's
        // lib/ranger-<type>-plugin-impl/*.jar layout works: each jar is a direct child). A directory
        // of loose .class files must therefore live one level below implLibDir (here: "classes/"),
        // not directly inside it -- otherwise the package path resolves one directory too deep and
        // isolation silently degrades to the componentClassLoader fallback instead of really testing
        // the isolated URL set (this was caught by this test's first failing run: it silently fell
        // through to "AMBIENT" instead of failing loudly, which is exactly the kind of false-positive
        // this test exists to prevent).
        Path implClassesDir = implLibDir.resolve("classes");
        Path sourceRoot = Files.createTempDirectory("isolation-probe-src");
        Path packageDir = sourceRoot.resolve("org/apache/ranger/authorization/pinot/classloadertest/impl");

        Files.createDirectories(packageDir);
        Files.createDirectories(implClassesDir);

        Path sourceFile = packageDir.resolve("IsolationProbeImpl.java");

        Files.writeString(sourceFile, ""
                + "package org.apache.ranger.authorization.pinot.classloadertest.impl;\n"
                + "import org.apache.ranger.authorization.pinot.classloadertest.IsolationProbe;\n"
                + "public class IsolationProbeImpl implements IsolationProbe {\n"
                + "    @Override public String origin() { return \"ISOLATED\"; }\n"
                + "}\n");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler, "A JDK (not just a JRE) is required to run this test");

        // The interface IsolationProbe is on this test's own classpath already; javac needs it on
        // its classpath to resolve the "implements" clause.
        String classpath = System.getProperty("java.class.path");

        int result = compiler.run(null, null, null,
                "-d", implClassesDir.toString(),
                "-classpath", classpath,
                sourceFile.toString());

        assertEquals(0, result, "Compiling the isolated IsolationProbeImpl fixture must succeed");

        return implLibDir;
    }

    @Test
    void ambientImplementationReturnsAmbient() throws Exception {
        IsolationProbe ambient = (IsolationProbe) Class.forName(IMPL_FQCN).getDeclaredConstructor().newInstance();

        assertEquals("AMBIENT", ambient.origin(), "Sanity check: the ordinary, non-isolated class must be reachable and behave as expected");
    }

    @Test
    void rangerPluginClassLoaderResolvesTheIsolatedImplNotTheAmbientOne() throws Exception {
        String pluginType = "pinot-isolation-test";
        Path implLibDir = compileIsolatedImplInto(pluginType);

        try {
            RangerPluginClassLoader pluginClassLoader = new RangerPluginClassLoader(pluginType, RangerPluginClassLoaderIsolationTest.class);

            assertTrue(pluginClassLoader.getURLs().length > 0,
                    "RangerPluginClassLoader must have picked up the isolated impl directory as a classpath root: " + implLibDir);

            Class<?> isolatedClass = Class.forName(IMPL_FQCN, true, pluginClassLoader);
            Object instance = isolatedClass.getDeclaredConstructor().newInstance();

            assertTrue(instance instanceof IsolationProbe,
                    "The isolated class must still satisfy the shared IsolationProbe interface via the classloader's fallback-to-host resolution");

            IsolationProbe probe = (IsolationProbe) instance;

            assertEquals("ISOLATED", probe.origin(),
                    "RangerPluginClassLoader must resolve " + IMPL_FQCN + " from the isolated ranger-" + pluginType + "-plugin-impl directory, "
                            + "NOT from the ambient test classpath (which would return \"AMBIENT\") -- this is the entire isolation contract the shim relies on");
        } finally {
            deleteRecursively(implLibDir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best-effort cleanup of a temp test fixture directory
                }
            });
        }

        File dirFile = dir.toFile();

        if (dirFile.exists()) {
            dirFile.delete();
        }
    }
}
