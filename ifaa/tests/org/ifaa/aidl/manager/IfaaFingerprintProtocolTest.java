/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ifaa.aidl.manager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public class IfaaFingerprintProtocolTest {
    @Test
    public void buildsAndParsesStockIfaaFingerprintMessages() {
        assertArrayEquals(new byte[] {
                0x01, 0x53, 0x0c, 0x00, 0x02, 0x53, 0x04, 0x00,
                0x69, 0x66, 0x61, 0x61, 0x0a, 0x00, 0x00, 0x00,
        }, IfaaFingerprintProtocol.createFidoRequest());

        byte[] id = new byte[40];
        id[0] = 7;
        ByteBuffer response = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        response.putShort((short) 0).putShort((short) 44).putInt(1).put(id);
        assertArrayEquals(id, IfaaFingerprintProtocol.parseFidoResponse(response.array()));
        assertNull(IfaaFingerprintProtocol.parseFidoResponse(new byte[7]));

        ByteBuffer command = ByteBuffer.wrap(
                IfaaFingerprintProtocol.createSetIdListCommand(id)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, command.getInt());
        assertEquals(0, command.getInt());
        assertEquals(0, command.getInt());
        assertEquals(0x20b, command.getInt());
        assertEquals(56, command.getInt());
        assertEquals(0x41464649, command.getInt());
        assertEquals(1, command.getInt());
        assertEquals(40, command.getInt());
        assertEquals(16, command.getInt());
    }
}
