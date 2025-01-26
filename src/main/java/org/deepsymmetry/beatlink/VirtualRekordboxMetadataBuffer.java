package org.deepsymmetry.beatlink;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VirtualRekordboxMetadataBuffer {
    private int rekordboxId;
    private int totalLength;

    private AtomicInteger matchedUsb = new AtomicInteger(0);

    // Uses a map with int keys and ByteBuffer value instead of an array of ByteBuffer to avoid having
    // to loop to check if all elements are filled for each chunk received.
    private ConcurrentHashMap<Integer, ByteBuffer> chunks;

    public VirtualRekordboxMetadataBuffer(int rekordboxId, int totalLength, ConcurrentHashMap<Integer, ByteBuffer> chunks) {
        this.rekordboxId = rekordboxId;
        this.totalLength = totalLength;
        this.chunks = chunks;
    }

    public int getRekordboxId() {
        return rekordboxId;
    }

    public int getTotalLength() {
        return totalLength;
    }

    public ConcurrentHashMap<Integer, ByteBuffer> getChunks() {
        return chunks;
    }

    public Integer getMatchedUsb() {
        return matchedUsb.get();
    }

    public void setMatchedUsb(Integer matchedUsb) {
        this.matchedUsb.set(matchedUsb);
    }

    @Override
    public String toString() {
        return "PssiBuffer{" +
                "rekordboxId=" + rekordboxId +
                ", totalLength=" + totalLength +
                ", chunks=" + chunks +
                '}';
    }

    /**
     * Helper function to combine all current chunks into a single ByteBuffer
     * @return ByteBuffer that contains all of the buffer chunks in order.
     */
    public ByteBuffer concatChunks() {
        ByteBuffer[] chunksArray = new ByteBuffer[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            chunksArray[i] = chunks.get(i);
        }
        final ByteBuffer combined = ByteBuffer.allocate(Arrays.stream(chunksArray).mapToInt(Buffer::remaining).sum());
        Arrays.stream(chunksArray).forEach(b -> combined.put(b.duplicate()));
        return combined;
    }
}
