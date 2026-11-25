/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.util.internal;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** {@link PlatformDependent} test cases. */
public class PlatformDependentTest {

    @Test
    public void testCreateTempFileIsOnlyReadableByOwner() throws Exception {
        File file = PlatformDependent.createTempFile("netty-perm-", ".tmp", null);
        try {
            assertTrue(file.isFile());
            assertTrue(file.getName().startsWith("netty-perm-"));
            assertTrue(file.getName().endsWith(".tmp"));
            assertEquals(new File(System.getProperty("java.io.tmpdir")).getCanonicalFile(),
                    file.getParentFile().getCanonicalFile());
            assertOnlyOwnerCanRead(file);
        } finally {
            file.delete();
        }
    }

    @Test
    public void testCreateTempFileInDirectoryIsOnlyReadableByOwner() throws Exception {
        File dir = newTempDirectory();
        try {
            File file = PlatformDependent.createTempFile("netty-perm-", ".tmp", dir);
            try {
                assertTrue(file.isFile());
                assertTrue(file.getName().startsWith("netty-perm-"));
                assertTrue(file.getName().endsWith(".tmp"));
                assertEquals(dir.getCanonicalFile(), file.getParentFile().getCanonicalFile());
                assertOnlyOwnerCanRead(file);
            } finally {
                file.delete();
            }
        } finally {
            dir.delete();
        }
    }

    @Test
    public void testCreateTempFileWithSeparatorInSuffixThrowsIOException() throws Exception {
        assumeTrue(DetectionUtil.javaVersion() >= 7);
        try {
            File file = PlatformDependent.createTempFile("netty-", File.separator + "evil.tmp", null);
            file.delete();
            fail("Expected IOException");
        } catch (IOException expected) {
            // Same exception type as File.createTempFile(...) on Java 7.
        }
    }

    @Test
    public void testRestrictReadToOwner() throws Exception {
        assumeTrue(DetectionUtil.javaVersion() >= 6);
        // File.createTempFile(...) creates the file with the default (umask based) permissions.
        File file = File.createTempFile("netty-perm-", ".tmp");
        try {
            // Make the file readable by everybody, which is what the assertion has to detect.
            assertTrue(file.setReadable(true, false));
            Set<String> permissions = posixFilePermissions(file);
            assumeNotNull(permissions);
            assertTrue(permissions.toString(), permissions.contains("OTHERS_READ"));

            PlatformDependent.restrictReadToOwner(file);
            assertTrue(file.isFile());
            assertOnlyOwnerCanRead(file);
        } finally {
            file.delete();
        }
    }

    @Test
    public void testRestrictReadToOwnerFailsIfPermissionsCanNotBeChanged() throws Exception {
        assumeTrue(DetectionUtil.javaVersion() >= 6);
        File file = File.createTempFile("netty-perm-", ".tmp");
        assertTrue(file.delete());
        try {
            PlatformDependent.restrictReadToOwner(file);
            fail("Expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(file.toString()));
        }
    }

    /**
     * Asserts that the specified file can be read by its owner only. Skips the test if the POSIX
     * permissions of the file can not be determined (Java 6 or older, non POSIX file system).
     */
    public static void assertOnlyOwnerCanRead(File file) throws Exception {
        Set<String> permissions = posixFilePermissions(file);
        assumeNotNull(permissions);
        assertTrue(permissions.toString(), permissions.contains("OWNER_READ"));
        assertFalse(permissions.toString(), permissions.contains("GROUP_READ"));
        assertFalse(permissions.toString(), permissions.contains("OTHERS_READ"));
    }

    /**
     * Returns the names of the {@code java.nio.file.attribute.PosixFilePermission}s of the specified
     * file, or {@code null} if they can not be determined. Uses reflection as Netty 3 is compiled
     * against Java 5.
     */
    private static Set<String> posixFilePermissions(File file) throws Exception {
        Method getPosixFilePermissions;
        Object path;
        Object linkOptions;
        try {
            Class<?> filesClass = Class.forName("java.nio.file.Files");
            Class<?> pathClass = Class.forName("java.nio.file.Path");
            linkOptions = Array.newInstance(Class.forName("java.nio.file.LinkOption"), 0);
            getPosixFilePermissions = filesClass.getMethod(
                    "getPosixFilePermissions", pathClass, linkOptions.getClass());
            path = File.class.getMethod("toPath").invoke(file);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        }

        Set<?> permissions;
        try {
            permissions = (Set<?>) getPosixFilePermissions.invoke(null, path, linkOptions);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof UnsupportedOperationException) {
                // Not a POSIX file system.
                return null;
            }
            throw e;
        }

        Set<String> names = new HashSet<String>();
        for (Object permission: permissions) {
            names.add(permission.toString());
        }
        return names;
    }

    private static File newTempDirectory() throws IOException {
        File dir = File.createTempFile("netty-dir-", "");
        if (!dir.delete() || !dir.mkdir()) {
            throw new IOException("Unable to create temporary directory " + dir);
        }
        return dir;
    }
}
