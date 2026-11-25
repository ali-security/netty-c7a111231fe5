/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.jboss.netty.handler.codec.http.websocketx;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.handler.codec.frame.CorruptedFrameException;
import org.jboss.netty.util.CharsetUtil;
import org.junit.Test;

import static org.junit.Assert.*;

public class WebSocket08FrameDecoderTest {

    private static final byte[] MASK = { 0x11, 0x22, 0x33, 0x44 };

    private static ChannelBuffer maskedFrame(boolean fin, int opcode, byte[] payload) {
        ChannelBuffer buf = ChannelBuffers.dynamicBuffer(payload.length + 14);
        buf.writeByte((fin ? 0x80 : 0) | opcode);
        if (payload.length < 126) {
            buf.writeByte(0x80 | payload.length);
        } else {
            buf.writeByte(0x80 | 126);
            buf.writeShort(payload.length);
        }
        buf.writeBytes(MASK);
        for (int i = 0; i < payload.length; i++) {
            buf.writeByte(payload[i] ^ MASK[i % 4]);
        }
        return buf;
    }

    @Test
    public void testFragmentedTextIsAggregated() {
        DecoderEmbedder<Object> embedder =
                new DecoderEmbedder<Object>(new WebSocket08FrameDecoder(true, false));
        embedder.offer(maskedFrame(false, 0x1, "Hello ".getBytes(CharsetUtil.UTF_8)));
        embedder.offer(maskedFrame(true, 0x0, "World".getBytes(CharsetUtil.UTF_8)));

        TextWebSocketFrame first = (TextWebSocketFrame) embedder.poll();
        assertEquals("Hello ", first.getText());
        ContinuationWebSocketFrame last = (ContinuationWebSocketFrame) embedder.poll();
        assertEquals("World", last.getText());
        assertEquals("Hello World", last.getAggregatedText());
    }

    @Test
    public void testFragmentedTextExceedingAggregationLimitIsRejected() {
        DecoderEmbedder<Object> embedder =
                new DecoderEmbedder<Object>(new WebSocket08FrameDecoder(true, false));
        byte[] chunk = new byte[60000];
        java.util.Arrays.fill(chunk, (byte) 'a');
        int frames = UTF8Output.MAX_AGGREGATED_TEXT_BYTES / chunk.length + 2;
        try {
            for (int i = 0; i < frames; i++) {
                embedder.offer(maskedFrame(false, i == 0 ? 0x1 : 0x0, chunk));
                embedder.poll();
            }
            fail("Expected the aggregated text to be rejected");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof CorruptedFrameException);
        }
    }

    @Test(expected = IllegalStateException.class)
    public void testUTF8OutputRejectsOversizedAggregation() {
        UTF8Output output = new UTF8Output(new byte[UTF8Output.MAX_AGGREGATED_TEXT_BYTES]);
        output.write(new byte[1]);
    }
}
