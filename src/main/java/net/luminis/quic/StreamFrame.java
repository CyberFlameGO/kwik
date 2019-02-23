/*
 * Copyright © 2019 Peter Doornbosch
 *
 * This file is part of Kwik, a QUIC client Java library
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.quic;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Stream;

import static net.luminis.quic.StreamType.ClientInitiatedBidirectional;

public class StreamFrame extends QuicFrame {

    private StreamType streamType;
    private int streamId;
    private int offset;
    private int length;
    private byte[] streamData;
    private boolean isFinal;
    private byte[] frameData;

    public StreamFrame() {
    }

    public StreamFrame(int streamId, byte[] applicationData, boolean fin) {
        this(Version.IETF_draft_17, streamId, 0, applicationData, 0, applicationData.length, fin);
    }

    public StreamFrame(int streamId, int offset, byte[] applicationData, boolean fin) {
        this(Version.IETF_draft_17, streamId, offset, applicationData, 0, applicationData.length, fin);
    }

    public StreamFrame(Version quicVersion, int streamId, int offset, byte[] applicationData, boolean fin) {
        this(quicVersion, streamId, offset, applicationData, 0, applicationData.length, fin);
    }

    public StreamFrame(int streamId, int offset, byte[] applicationData, int dataOffset, int dataLength, boolean fin) {
        this(Version.IETF_draft_17, streamId, offset, applicationData, dataOffset, dataLength, fin);
    }

    public StreamFrame(Version quicVersion, int streamId, int streamOffset, byte[] applicationData, int dataOffset, int dataLength, boolean fin) {
        streamType = ClientInitiatedBidirectional;
        this.streamId = streamId;
        this.offset = streamOffset;
        this.length = dataLength;
        isFinal = fin;

        ByteBuffer buffer = ByteBuffer.allocate(1 + 3 * 4 + applicationData.length);
        byte baseType = (byte) (quicVersion.atLeast(Version.IETF_draft_17)? 0x08: 0x10);
        byte frameType = (byte) (baseType | 0x04 | 0x02 | 0x00);  // OFF-bit, LEN-bit, (no) FIN-bit
        if (fin) {
            frameType |= 0x01;
        }
        buffer.put(frameType);
        buffer.put(encodeVariableLengthInteger(streamId));
        buffer.put(encodeVariableLengthInteger(offset));  // offset
        buffer.put(encodeVariableLengthInteger(applicationData.length));  // length
        buffer.put(applicationData, dataOffset, dataLength);

        frameData = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(frameData);
    }

    public StreamFrame parse(ByteBuffer buffer, Logger log) {
        int frameType = buffer.get();
        boolean withOffset = ((frameType & 0x04) == 0x04);
        boolean withLength = ((frameType & 0x02) == 0x02);
        isFinal = ((frameType & 0x01) == 0x01);

        streamId = QuicPacket.parseVariableLengthInteger(buffer);
        streamType = Stream.of(StreamType.values()).filter(t -> t.value == (streamId & 0x03)).findFirst().get();

        if (withOffset) {
            offset = QuicPacket.parseVariableLengthInteger(buffer);
        }
        if (withLength) {
            length = QuicPacket.parseVariableLengthInteger(buffer);
        }

        if (length > 0) {
            length = buffer.limit() - buffer.position();
        }
        streamData = new byte[length];
        buffer.get(streamData);
        log.debug("Stream data", streamData);

        return this;
    }

    @Override
    byte[] getBytes() {
        return frameData;
    }

    @Override
    public String toString() {
        return "StreamFrame[" + streamId + "(" + streamType.abbrev + ")" + "," + offset + "," + length + "]";
    }

    public int getStreamId() {
        return streamId;
    }

    public int getOffset() {
        return offset;
    }

    public int getLength() {
        return length;
    }

    public byte[] getStreamData() {
        return streamData;
    }

    public boolean isFinal() {
        return isFinal;
    }

    static int maxOverhead() {
        return 1  // frame type
        + 4 // stream id
        + 4 // offset
        + 4 // length
        ;
    }
}