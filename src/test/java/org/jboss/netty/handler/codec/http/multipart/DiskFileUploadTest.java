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
package org.jboss.netty.handler.codec.http.multipart;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.CharsetUtil;
import org.jboss.netty.util.internal.DetectionUtil;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.jboss.netty.util.internal.PlatformDependentTest.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** {@link DiskFileUpload} and {@link DiskAttribute} test cases. */
public class DiskFileUploadTest {

    @Test
    public void testDiskFileUploadTempFileIsOnlyReadableByOwner() throws Exception {
        DiskFileUpload upload = new DiskFileUpload(
                "file", "secret.txt", "text/plain", null, CharsetUtil.UTF_8, 0);
        try {
            upload.setContent(ChannelBuffers.copiedBuffer("secret", CharsetUtil.UTF_8));
            File file = upload.getFile();
            assertTrue(file.isFile());
            assertEquals("secret", upload.getString(CharsetUtil.UTF_8));
            assertOnlyOwnerCanRead(file);
        } finally {
            upload.delete();
        }
    }

    @Test
    public void testDiskFileUploadTempFileFromStreamIsOnlyReadableByOwner() throws Exception {
        DiskFileUpload upload = new DiskFileUpload(
                "file", "secret.txt", "text/plain", null, CharsetUtil.UTF_8, 0);
        try {
            upload.setContent(new ByteArrayInputStream("secret".getBytes("UTF-8")));
            File file = upload.getFile();
            assertTrue(file.isFile());
            assertOnlyOwnerCanRead(file);
        } finally {
            // setContent(InputStream) marks the file as renamed, so delete() keeps it.
            upload.getFile().delete();
        }
    }

    @Test
    public void testDiskFileUploadTempFileInBaseDirectoryIsOnlyReadableByOwner() throws Exception {
        File dir = File.createTempFile("netty-dir-", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        String oldBaseDirectory = DiskFileUpload.baseDirectory;
        DiskFileUpload.baseDirectory = dir.getAbsolutePath();
        try {
            DiskFileUpload upload = new DiskFileUpload(
                    "file", "secret.txt", "text/plain", null, CharsetUtil.UTF_8, 0);
            try {
                upload.addContent(ChannelBuffers.copiedBuffer("secret", CharsetUtil.UTF_8), true);
                File file = upload.getFile();
                assertTrue(file.isFile());
                assertEquals(dir.getCanonicalFile(), file.getParentFile().getCanonicalFile());
                assertOnlyOwnerCanRead(file);
            } finally {
                upload.delete();
            }
        } finally {
            DiskFileUpload.baseDirectory = oldBaseDirectory;
            dir.delete();
        }
    }

    @Test
    public void testDiskAttributeTempFileIsOnlyReadableByOwner() throws Exception {
        DiskAttribute attribute = new DiskAttribute("password", "secret");
        try {
            File file = attribute.getFile();
            assertTrue(file.isFile());
            assertEquals("secret", attribute.getValue());
            assertOnlyOwnerCanRead(file);
        } finally {
            attribute.delete();
        }
    }

    @Test
    public void testDiskAttributeNameWithSeparatorThrowsIOException() throws Exception {
        assumeTrue(DetectionUtil.javaVersion() >= 7);
        try {
            DiskAttribute attribute = new DiskAttribute("a" + File.separator + "b", "secret");
            attribute.delete();
            fail("Expected IOException");
        } catch (IOException expected) {
            // The temporary file could not be created, as with File.createTempFile(...).
        }
    }

    @Test
    public void testDiskFactoryAttributeNameWithSeparatorRevertsToMemory() throws Exception {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        DefaultHttpDataFactory factory = new DefaultHttpDataFactory(true);
        try {
            Attribute attribute = factory.createAttribute(request, "a" + File.separator + "b", "value");
            assertEquals("value", attribute.getValue());
        } finally {
            factory.cleanRequestHttpDatas(request);
        }
    }

    @Test
    public void testMultipartUploadToDiskIsOnlyReadableByOwner() throws Exception {
        final String boundary = "dLV9Wyq26L_-JQxk6ferf-RT153LhOO";

        final DefaultHttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "http://localhost");
        req.setContent(ChannelBuffers.EMPTY_BUFFER);
        req.setHeader(HttpHeaders.Names.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);
        req.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
        req.setChunked(true);

        final String body =
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"password\"\r\n" +
                "\r\n" +
                "secret-attribute\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"secret.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "secret-file\r\n" +
                "--" + boundary + "--\r\n";

        // Force to use disk-based data.
        final HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(true), req);
        try {
            decoder.offer(new DefaultHttpChunk(ChannelBuffers.copiedBuffer(body, CharsetUtil.UTF_8)));
            decoder.offer(new DefaultHttpChunk(ChannelBuffers.EMPTY_BUFFER));

            List<InterfaceHttpData> datas = decoder.getBodyHttpDatas();
            assertEquals(2, datas.size());

            DiskAttribute attribute = (DiskAttribute) decoder.getBodyHttpData("password");
            assertEquals("secret-attribute", attribute.getValue());
            assertOnlyOwnerCanRead(attribute.getFile());

            DiskFileUpload upload = (DiskFileUpload) decoder.getBodyHttpData("file");
            assertEquals("secret-file", upload.getString(CharsetUtil.UTF_8));
            assertOnlyOwnerCanRead(upload.getFile());
        } finally {
            decoder.cleanFiles();
        }
    }
}
