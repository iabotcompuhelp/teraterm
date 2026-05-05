package com.opentermx.serial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CircularByteBufferTest {

    @Test
    void writesAndReadsBackInOrder() {
        CircularByteBuffer buf = new CircularByteBuffer(8);
        buf.write(new byte[]{1, 2, 3, 4}, 0, 4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, buf.snapshot());
        assertEquals(4, buf.size());
    }

    @Test
    void overwritesOldestWhenFull() {
        CircularByteBuffer buf = new CircularByteBuffer(4);
        buf.write(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6);
        assertArrayEquals(new byte[]{3, 4, 5, 6}, buf.snapshot());
        assertEquals(4, buf.size());
    }

    @Test
    void wrapsAroundCorrectly() {
        CircularByteBuffer buf = new CircularByteBuffer(5);
        buf.write(new byte[]{1, 2, 3}, 0, 3);
        buf.write(new byte[]{4, 5, 6, 7}, 0, 4);
        assertArrayEquals(new byte[]{3, 4, 5, 6, 7}, buf.snapshot());
        assertEquals(5, buf.size());
    }

    @Test
    void singleWriteLargerThanCapacityKeepsTail() {
        CircularByteBuffer buf = new CircularByteBuffer(3);
        buf.write(new byte[]{1, 2, 3, 4, 5}, 0, 5);
        assertArrayEquals(new byte[]{3, 4, 5}, buf.snapshot());
    }

    @Test
    void honoursOffsetAndLength() {
        CircularByteBuffer buf = new CircularByteBuffer(8);
        buf.write(new byte[]{9, 9, 1, 2, 3, 9}, 2, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, buf.snapshot());
    }

    @Test
    void clearResetsState() {
        CircularByteBuffer buf = new CircularByteBuffer(4);
        buf.write(new byte[]{1, 2, 3}, 0, 3);
        buf.clear();
        assertEquals(0, buf.size());
        assertArrayEquals(new byte[0], buf.snapshot());
    }
}