/*
 * Copyright © 2020 Peter Doornbosch
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
package net.luminis.quic.stream;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;


public class BaseStream {

    private SortedSet<StreamElement> frames = new TreeSet<>();
    private int processedToOffset = 0;

    /**
     * Add a stream frame to this stream. The frame can contain any number of bytes positioned anywhere in the stream;
     * the read method will take care of returning stream bytes in the right order, without gaps.
     * @param frame
     * @return true if the frame is adds bytes to this stream; false if the frame does not add bytes to the stream
     * (because the frame is a duplicate or its stream bytes where already received with previous frames).
     */
    protected boolean add(StreamElement frame) {
        if (frame.getUpToOffset() > processedToOffset) {
            frames.add(frame);
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * Returns the number of bytes that can be read from this stream.
     * @return
     */
    protected int bytesAvailable() {
        if (frames.isEmpty()) {
            return 0;
        }
        else {
            int available = 0;
            int readUpTo = processedToOffset;
            Iterator<StreamElement> iterator = frames.iterator();

            while (iterator.hasNext()) {
                StreamElement nextFrame = iterator.next();
                if (nextFrame.getOffset() <= readUpTo) {
                    if (nextFrame.getUpToOffset() > readUpTo) {
                        available += nextFrame.getUpToOffset() - readUpTo;
                        readUpTo = nextFrame.getUpToOffset();
                    }
                } else {
                    break;
                }
            }
            return available;
        }
    }

    /**
     * Read a much as possible bytes from the stream (limited by the size of the given buffer or the number of bytes
     * available on the stream).
     * @param buffer
     * @return
     */
    protected int read(ByteBuffer buffer) {
        if (frames.isEmpty()) {
            return 0;
        }
        else {
            int read = 0;
            int readUpTo = processedToOffset;
            Iterator<StreamElement> iterator = frames.iterator();

            while (iterator.hasNext() && buffer.remaining() > 0) {
                StreamElement nextFrame = iterator.next();
                if (nextFrame.getOffset() <= readUpTo) {
                    if (nextFrame.getUpToOffset() > readUpTo) {
                        int available = nextFrame.getOffset() - readUpTo + nextFrame.getLength();
                        int bytesToRead = Integer.min(buffer.limit() - buffer.position(), available);
                        buffer.put(nextFrame.getStreamData(), readUpTo - nextFrame.getOffset(), bytesToRead);
                        readUpTo += bytesToRead;
                        read += bytesToRead;
                    }
                } else {
                    break;
                }
            }
            processedToOffset += read;
            removeParsedFrames();
            return read;
        }
    }

    private void removeParsedFrames() {
        Iterator<StreamElement> iterator = frames.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getUpToOffset() <= processedToOffset) {
                iterator.remove();
            }
            else {
                break;
            }
        }
    }

    /**
     * Returns the position in the stream up to where stream bytes are read.
     * @return
     */
    protected long readOffset() {
        return processedToOffset;
    }
}
