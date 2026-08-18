/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ifaa.aidl.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IfaaCommandParserTest {
    @Test
    public void parsesNestedCommandAndRejectsMalformedOffsets() {
        byte[] valid = new byte[20];
        putUint32(valid, 8, 4);
        putUint32(valid, 16, 0x81);
        assertEquals(0x81, IfaaCommandParser.getCommandId(valid));
        assertTrue(IfaaCommandParser.isAllowed(IfaaCommandParser.getCommandId(valid)));

        byte[] overflowing = valid.clone();
        putUint32(overflowing, 4, -1);
        assertEquals(-1, IfaaCommandParser.getCommandId(overflowing));
        assertEquals(-1, IfaaCommandParser.getCommandId(new byte[7]));
        assertFalse(IfaaCommandParser.isAllowed(3));
    }

    private static void putUint32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}
