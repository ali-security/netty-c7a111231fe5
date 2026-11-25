/*
 * Copyright 2013 The Netty Project
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
package org.jboss.netty.handler.codec.spdy;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.CharsetUtil;
import org.junit.Test;

import static org.junit.Assert.*;

public class SpdyHttpDecoderTest {

    private static final int SPDY_VERSION = 3;
    private static final int STREAM_ID = 1;

    @Test
    public void testSynStreamWithFinOverridesContentLength() {
        SpdySynStreamFrame frame = newRequestFrame(true);
        frame.addHeader(HttpHeaders.Names.CONTENT_LENGTH, "40");

        HttpRequest request = decodeRequest(frame);
        assertEquals(0, request.getContent().readableBytes());
        assertEquals(1, request.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals(0, HttpHeaders.getContentLength(request, -1));
    }

    @Test
    public void testSynStreamWithFinOverridesMultipleContentLength() {
        SpdySynStreamFrame frame = newRequestFrame(true);
        frame.addHeader(HttpHeaders.Names.CONTENT_LENGTH, "0");
        frame.addHeader(HttpHeaders.Names.CONTENT_LENGTH, "40");

        HttpRequest request = decodeRequest(frame);
        assertEquals(0, request.getContent().readableBytes());
        assertEquals(1, request.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals(0, HttpHeaders.getContentLength(request, -1));
    }

    @Test
    public void testSynStreamWithFinOverridesSignedContentLength() {
        SpdySynStreamFrame frame = newRequestFrame(true);
        frame.addHeader(HttpHeaders.Names.CONTENT_LENGTH, "+1");

        HttpRequest request = decodeRequest(frame);
        assertEquals(0, request.getContent().readableBytes());
        assertEquals(1, request.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals(0, HttpHeaders.getContentLength(request, -1));
    }

    @Test
    public void testSynStreamWithFinWithoutContentLength() {
        HttpRequest request = decodeRequest(newRequestFrame(true));
        assertEquals(0, request.getContent().readableBytes());
        assertFalse(request.containsHeader(HttpHeaders.Names.CONTENT_LENGTH));
    }

    @Test
    public void testDataFrameOverridesContentLength() {
        SpdySynStreamFrame frame = newRequestFrame(false);
        frame.addHeader(HttpHeaders.Names.CONTENT_LENGTH, "40");

        DecoderEmbedder<Object> embedder = newEmbedder();
        embedder.offer(frame);
        assertNull(embedder.poll());

        SpdyDataFrame dataFrame = new DefaultSpdyDataFrame(STREAM_ID);
        dataFrame.setData(ChannelBuffers.copiedBuffer("abc", CharsetUtil.US_ASCII));
        dataFrame.setLast(true);
        embedder.offer(dataFrame);

        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpRequest);
        HttpRequest request = (HttpRequest) msg;
        assertEquals("abc", request.getContent().toString(CharsetUtil.US_ASCII));
        assertEquals(1, request.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals(3, HttpHeaders.getContentLength(request, -1));
        assertFalse(embedder.finish());
    }

    private static DecoderEmbedder<Object> newEmbedder() {
        return new DecoderEmbedder<Object>(new SpdyHttpDecoder(SPDY_VERSION, 1024));
    }

    private static HttpRequest decodeRequest(SpdySynStreamFrame frame) {
        DecoderEmbedder<Object> embedder = newEmbedder();
        embedder.offer(frame);
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpRequest);
        assertFalse(embedder.finish());
        return (HttpRequest) msg;
    }

    private static SpdySynStreamFrame newRequestFrame(boolean last) {
        SpdySynStreamFrame frame = new DefaultSpdySynStreamFrame(STREAM_ID, 0, (byte) 0);
        SpdyHeaders.setMethod(SPDY_VERSION, frame, HttpMethod.POST);
        SpdyHeaders.setUrl(SPDY_VERSION, frame, "/some/path");
        SpdyHeaders.setVersion(SPDY_VERSION, frame, HttpVersion.HTTP_1_1);
        SpdyHeaders.setScheme(SPDY_VERSION, frame, "https");
        SpdyHeaders.setHost(frame, "example.com");
        frame.setLast(last);
        return frame;
    }
}
