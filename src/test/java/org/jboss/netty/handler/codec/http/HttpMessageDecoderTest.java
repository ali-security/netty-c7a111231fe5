/*
 * Copyright 2012 The Netty Project
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
package org.jboss.netty.handler.codec.http;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.util.CharsetUtil;

import org.junit.Test;

import static org.junit.Assert.*;

public class HttpMessageDecoderTest {

    @Test
    public void testHeaderWithNoValueAndMissingColon() {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Host:\r\n" +
                "netty.io\r\n\r\n";

        try {
            embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testMultipleContentLengthHeaders() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeaders2() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithCommaValue() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1,1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersWithFolding() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 5\r\n" +
                "Content-Length:\r\n" +
                "\t6\r\n\r\n" +
                "123456";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersHttp10() {
        testInvalidHeaders0(multipleContentLengthRequest("HTTP/1.0"));
    }

    @Test
    public void testMultipleContentLengthHeadersHttp09() {
        testInvalidHeaders0(multipleContentLengthRequest("HTTP/0.9"));
    }

    @Test
    public void testEmptyContentLength() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length:\r\n\r\n" +
                "ab";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthAndTransferEncodingChunked() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: 5\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpRequest);
        HttpRequest request = (HttpRequest) msg;
        assertTrue(request.isChunked());
        assertEquals(HttpHeaders.Values.CHUNKED, request.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        assertFalse(request.containsHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertTrue(embedder.poll() instanceof HttpChunkTrailer);
        assertFalse(embedder.finish());
    }

    @Test
    public void testContentLengthHeaderWithPositiveSign() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithNegativeSign() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: -1\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithNegativeValue() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: -10\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithPositiveSignHttp10() {
        String requestStr = "GET /some/path HTTP/1.0\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderNotANumber() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1a\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderOverflow() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 99999999999999999999\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValue() {
        // When allowDuplicateContentLengths is false (default), even identical values should be rejected
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Content-Length: 0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValueWithComma() {
        // When allowDuplicateContentLengths is false (default), even identical comma-separated values should be rejected
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0, 0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValueHttp10() {
        String requestStr = "PUT /some/path HTTP/1.0\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsSameValue() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        assertDuplicateContentLengthsNormalized(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsSameValueWithComma() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 1, 1\r\n\r\n" +
                "a";
        assertDuplicateContentLengthsNormalized(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsSameValueHttp10() {
        String requestStr = "PUT /some/path HTTP/1.0\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        assertDuplicateContentLengthsNormalized(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValues() {
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), multipleContentLengthRequest("HTTP/1.1"));
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValuesWithComma() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 1, 2\r\n\r\n" +
                "ab";
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValuesHttp10() {
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), multipleContentLengthRequest("HTTP/1.0"));
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValuesHttp09() {
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), multipleContentLengthRequest("HTTP/0.9"));
    }

    @Test
    public void testAllowDuplicateContentLengthsWithSign() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: +1\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "a";
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsEmptyValue() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: ,\r\n\r\n" +
                "a";
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersResponse() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 2\r\n\r\n" +
                "ab";
        testInvalidHeaders0(new HttpResponseDecoder(), responseStr);
    }

    @Test
    public void testContentLengthHeaderWithPositiveSignResponse() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "a";
        testInvalidHeaders0(new HttpResponseDecoder(), responseStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsResponse() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        DecoderEmbedder<Object> embedder =
                new DecoderEmbedder<Object>(new HttpResponseDecoder(4096, 8192, 8192, true));
        embedder.offer(ChannelBuffers.copiedBuffer(responseStr, CharsetUtil.US_ASCII));
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpResponse);
        HttpResponse response = (HttpResponse) msg;
        assertEquals(1, response.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals("1", response.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertEquals("a", response.getContent().toString(CharsetUtil.US_ASCII));
        assertFalse(embedder.finish());
    }

    @Test
    public void testValidContentLength() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 2\r\n\r\n" +
                "ab";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpRequest);
        HttpRequest request = (HttpRequest) msg;
        assertEquals("2", request.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertEquals("ab", request.getContent().toString(CharsetUtil.US_ASCII));
        assertFalse(embedder.finish());
    }

    private static HttpRequestDecoder newAllowDuplicateContentLengthsDecoder() {
        return new HttpRequestDecoder(4096, 8192, 8192, true);
    }

    private static void assertDuplicateContentLengthsNormalized(String requestStr) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(newAllowDuplicateContentLengthsDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpRequest);
        HttpRequest request = (HttpRequest) msg;
        // The duplicated field-values must be replaced with a single valid Content-Length field
        assertEquals(1, request.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals("1", request.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertEquals("a", request.getContent().toString(CharsetUtil.US_ASCII));
        assertFalse(embedder.finish());
    }

    private static String multipleContentLengthRequest(String version) {
        return "PUT /some/path " + version + "\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 2\r\n\r\n" +
                "ab";
    }

    // ---------------------------------------------------------------------
    // GHSA-wx5j-54mm-rqqq: header names must be bracketed by OWS only.
    // The decoder used to silently strip every Character.isWhitespace char,
    // which let an attacker smuggle control characters around a header name.
    // The strict OWS check now rejects 0x0c (FF) and 0x1c-0x1f (FS/GS/RS/US)
    // both before the name and between the name and the colon.
    // ---------------------------------------------------------------------

    @Test
    public void testRequestHeaderNameStartsWithControlChar1c() {
        testRequestHeaderNameStartsWithControlChar(0x1c);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1d() {
        testRequestHeaderNameStartsWithControlChar(0x1d);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1e() {
        testRequestHeaderNameStartsWithControlChar(0x1e);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1f() {
        testRequestHeaderNameStartsWithControlChar(0x1f);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar0c() {
        testRequestHeaderNameStartsWithControlChar(0x0c);
    }

    private static void testRequestHeaderNameStartsWithControlChar(int controlChar) {
        ChannelBuffer requestBuffer = ChannelBuffers.dynamicBuffer();
        requestBuffer.writeBytes("GET /some/path HTTP/1.1\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeByte(controlChar);
        requestBuffer.writeBytes("Transfer-Encoding: chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidHeaders0(new HttpRequestDecoder(), requestBuffer);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1c() {
        testRequestHeaderNameEndsWithControlChar(0x1c);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1d() {
        testRequestHeaderNameEndsWithControlChar(0x1d);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1e() {
        testRequestHeaderNameEndsWithControlChar(0x1e);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1f() {
        testRequestHeaderNameEndsWithControlChar(0x1f);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar0c() {
        testRequestHeaderNameEndsWithControlChar(0x0c);
    }

    private static void testRequestHeaderNameEndsWithControlChar(int controlChar) {
        ChannelBuffer requestBuffer = ChannelBuffers.dynamicBuffer();
        requestBuffer.writeBytes("GET /some/path HTTP/1.1\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeBytes("Transfer-Encoding".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeByte(controlChar);
        requestBuffer.writeBytes(": chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidHeaders0(new HttpRequestDecoder(), requestBuffer);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1c() {
        testResponseHeaderNameStartsWithControlChar(0x1c);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1d() {
        testResponseHeaderNameStartsWithControlChar(0x1d);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1e() {
        testResponseHeaderNameStartsWithControlChar(0x1e);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1f() {
        testResponseHeaderNameStartsWithControlChar(0x1f);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar0c() {
        testResponseHeaderNameStartsWithControlChar(0x0c);
    }

    private static void testResponseHeaderNameStartsWithControlChar(int controlChar) {
        ChannelBuffer responseBuffer = ChannelBuffers.dynamicBuffer();
        responseBuffer.writeBytes("HTTP/1.1 200 OK\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeByte(controlChar);
        responseBuffer.writeBytes("Transfer-Encoding: chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidHeaders0(new HttpResponseDecoder(), responseBuffer);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1c() {
        testResponseHeaderNameEndsWithControlChar(0x1c);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1d() {
        testResponseHeaderNameEndsWithControlChar(0x1d);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1e() {
        testResponseHeaderNameEndsWithControlChar(0x1e);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1f() {
        testResponseHeaderNameEndsWithControlChar(0x1f);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar0c() {
        testResponseHeaderNameEndsWithControlChar(0x0c);
    }

    private static void testResponseHeaderNameEndsWithControlChar(int controlChar) {
        ChannelBuffer responseBuffer = ChannelBuffers.dynamicBuffer();
        responseBuffer.writeBytes("HTTP/1.1 200 OK\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeBytes("Transfer-Encoding".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeByte(controlChar);
        responseBuffer.writeBytes(": chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidHeaders0(new HttpResponseDecoder(), responseBuffer);
    }

    // ---------------------------------------------------------------------
    // CVE-2019-16869: no whitespace is allowed between a header field-name and
    // the colon (RFC 7230 section 3.2.4). For requests this must be rejected
    // (400 Bad Request); for responses the whitespace is silently skipped so
    // that interop with non-compliant servers is preserved.
    // ---------------------------------------------------------------------

    @Test
    public void testWhitespaceInRequestHeaderName() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Transfer-Encoding : chunked\r\n" +
                "Host: netty.io\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testTabInRequestHeaderName() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Transfer-Encoding\t: chunked\r\n" +
                "Host: netty.io\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testWhitespaceInRequestTrailerHeaderName() {
        String requestStr = "POST /some/path HTTP/1.1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Host: netty.io\r\n\r\n" +
                "1\r\n" +
                "a\r\n" +
                "0\r\n" +
                "X-Trailer : value\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testWhitespaceInResponseHeaderName() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding : chunked\r\n" +
                "Host: netty.io\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpResponseDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(responseStr, CharsetUtil.US_ASCII));
        HttpResponse response = (HttpResponse) embedder.poll();
        assertNotNull(response);
        assertEquals(HttpHeaders.Values.CHUNKED,
                response.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        assertEquals("netty.io", response.getHeader(HttpHeaders.Names.HOST));
    }

    private static void testInvalidHeaders0(String requestStr) {
        testInvalidHeaders0(new HttpRequestDecoder(), requestStr);
    }

    private static void testInvalidHeaders0(HttpMessageDecoder decoder, String requestStr) {
        testInvalidHeaders0(decoder, ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
    }

    private static void testInvalidHeaders0(HttpMessageDecoder decoder, ChannelBuffer buffer) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(decoder);
        try {
            embedder.offer(buffer);
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }
}
