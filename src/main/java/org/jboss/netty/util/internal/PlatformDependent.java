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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Utility that provides operations whose implementation depends on the
 * current runtime environment, such as the Java version.
 * <p>
 * Netty 3 is compiled against Java 5, so APIs introduced in later Java
 * versions ({@code java.nio.file.Files}, {@code File.setReadable}) are only
 * accessed via reflection.
 */
public final class PlatformDependent {

    /** {@code java.nio.file.Files.createTempFile(String, String, FileAttribute...)} (Java 7+). */
    private static final Method FILES_CREATE_TEMP_FILE;
    /** {@code java.nio.file.Files.createTempFile(Path, String, String, FileAttribute...)} (Java 7+). */
    private static final Method FILES_CREATE_TEMP_FILE_IN_DIR;
    /** {@code java.io.File.toPath()} (Java 7+). */
    private static final Method FILE_TO_PATH;
    /** {@code java.nio.file.Path.toFile()} (Java 7+). */
    private static final Method PATH_TO_FILE;
    /** An empty {@code java.nio.file.attribute.FileAttribute[]} (Java 7+). */
    private static final Object EMPTY_FILE_ATTRIBUTES;
    /** {@code java.io.File.setReadable(boolean, boolean)} (Java 6+). */
    private static final Method FILE_SET_READABLE;

    static {
        Method createTempFile = null;
        Method createTempFileInDir = null;
        Method toPath = null;
        Method toFile = null;
        Object emptyAttributes = null;
        if (DetectionUtil.javaVersion() >= 7) {
            try {
                Class<?> filesClass = Class.forName("java.nio.file.Files");
                Class<?> pathClass = Class.forName("java.nio.file.Path");
                Class<?> fileAttributeClass = Class.forName("java.nio.file.attribute.FileAttribute");
                Object attributes = Array.newInstance(fileAttributeClass, 0);
                Class<?> attributesClass = attributes.getClass();

                Method m1 = filesClass.getMethod("createTempFile", String.class, String.class, attributesClass);
                Method m2 = filesClass.getMethod(
                        "createTempFile", pathClass, String.class, String.class, attributesClass);
                Method m3 = File.class.getMethod("toPath");
                Method m4 = pathClass.getMethod("toFile");

                createTempFile = m1;
                createTempFileInDir = m2;
                toPath = m3;
                toFile = m4;
                emptyAttributes = attributes;
            } catch (Throwable t) {
                // Not available - fall back to File.createTempFile(...) and adjust the permissions.
            }
        }
        FILES_CREATE_TEMP_FILE = createTempFile;
        FILES_CREATE_TEMP_FILE_IN_DIR = createTempFileInDir;
        FILE_TO_PATH = toPath;
        PATH_TO_FILE = toFile;
        EMPTY_FILE_ATTRIBUTES = emptyAttributes;

        Method setReadable = null;
        try {
            setReadable = File.class.getMethod("setReadable", boolean.class, boolean.class);
        } catch (Throwable t) {
            // Java 5 - File.setReadable(boolean, boolean) does not exist.
        }
        FILE_SET_READABLE = setReadable;
    }

    /**
     * Creates a new empty temporary file which is only readable by its owner,
     * unlike {@link File#createTempFile(String, String, File)} which creates the
     * file with the default (often world-readable) permissions.
     * <p>
     * On Java 7+ this uses {@code java.nio.file.Files.createTempFile(...)}.
     * Otherwise the file is created via {@link File#createTempFile(String, String, File)}
     * and its read permission is restricted to the owner.
     *
     * @param prefix    the prefix string to be used in generating the file's name
     * @param suffix    the suffix string to be used in generating the file's name
     * @param directory the directory in which the file is to be created, or
     *                  {@code null} if the default temporary-file directory is to be used
     * @return the newly-created file
     * @throws IOException if the file could not be created or its permissions
     *                     could not be restricted
     * @see <a href="https://github.com/netty/netty/security/advisories/GHSA-5mcr-gq6c-3hq2">
     *      CVE-2021-21290</a>
     */
    public static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        if (FILES_CREATE_TEMP_FILE != null) {
            return createTempFileJava7(prefix, suffix, directory);
        }
        final File file;
        if (directory == null) {
            file = File.createTempFile(prefix, suffix);
        } else {
            file = File.createTempFile(prefix, suffix, directory);
        }

        restrictReadToOwner(file);
        return file;
    }

    private static File createTempFileJava7(String prefix, String suffix, File directory) throws IOException {
        try {
            Object path;
            if (directory == null) {
                path = FILES_CREATE_TEMP_FILE.invoke(null, prefix, suffix, EMPTY_FILE_ATTRIBUTES);
            } else {
                Object dir = FILE_TO_PATH.invoke(directory);
                path = FILES_CREATE_TEMP_FILE_IN_DIR.invoke(null, dir, prefix, suffix, EMPTY_FILE_ATTRIBUTES);
            }
            return (File) PATH_TO_FILE.invoke(path);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof IllegalArgumentException) {
                // Files.createTempFile(...) rejects a prefix or suffix which would not result
                // in a plain file name (e.g. one containing a name separator), where
                // File.createTempFile(...) used to throw an IOException.
                throw newIOException("Unable to create temporary file: " + cause.getMessage(), cause);
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw newIOException("Unable to create temporary file", cause);
        } catch (IllegalAccessException e) {
            throw newIOException("Unable to create temporary file", e);
        }
    }

    /**
     * Revokes the read permission of everybody except the owner of the specified file.
     *
     * @throws IOException if the permissions of the file could not be adjusted
     */
    static void restrictReadToOwner(File file) throws IOException {
        if (FILE_SET_READABLE == null) {
            // Java 5 - there is not much else we can do...
            return;
        }
        if (!setReadable(file, false, false)) {
            throw new IOException("Failed to set permissions on temporary file " + file);
        }
        if (!setReadable(file, true, true)) {
            throw new IOException("Failed to set permissions on temporary file " + file);
        }
    }

    private static boolean setReadable(File file, boolean readable, boolean ownerOnly) throws IOException {
        try {
            return Boolean.TRUE.equals(
                    FILE_SET_READABLE.invoke(file, Boolean.valueOf(readable), Boolean.valueOf(ownerOnly)));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw newIOException("Failed to set permissions on temporary file " + file, cause);
        } catch (IllegalAccessException e) {
            throw newIOException("Failed to set permissions on temporary file " + file, e);
        }
    }

    private static IOException newIOException(String message, Throwable cause) {
        IOException e = new IOException(message);
        e.initCause(cause);
        return e;
    }

    private PlatformDependent() {
        // only static method supported
    }
}
