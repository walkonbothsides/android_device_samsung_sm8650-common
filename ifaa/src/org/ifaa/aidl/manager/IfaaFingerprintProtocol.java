/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ifaa.aidl.manager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class IfaaFingerprintProtocol {
    private static final int ID_SIZE = 40;
    private static final int MAX_ID_BYTES = 400;

    private IfaaFingerprintProtocol() {}

    static byte[] createFidoRequest() {
        ByteBuffer request = littleEndian(16);
        request.putShort((short) 0x5301);
        request.putShort((short) 12);
        request.putShort((short) 0x5302);
        request.putShort((short) 4);
        request.put("ifaa".getBytes(StandardCharsets.UTF_8));
        request.putShort((short) 10);
        request.putShort((short) 0);
        return request.array();
    }

    static byte[] parseFidoResponse(byte[] response) {
        if (response == null || response.length < 8) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getShort() != 0 || Short.toUnsignedInt(buffer.getShort()) != buffer.remaining()) {
            return null;
        }
        if (buffer.getInt() != 1 || buffer.remaining() > MAX_ID_BYTES
                || buffer.remaining() % ID_SIZE != 0) {
            return null;
        }
        return Arrays.copyOfRange(response, buffer.position(), response.length);
    }

    static byte[] createSetIdListCommand(byte[] ids) {
        if (ids == null || ids.length > MAX_ID_BYTES || ids.length % ID_SIZE != 0) {
            throw new IllegalArgumentException("Invalid fingerprint ID list");
        }
        ByteBuffer command = littleEndian(36 + ids.length);
        command.putInt(0).putInt(0).putInt(0);
        command.putInt(0x20b).putInt(16 + ids.length);
        command.putInt(0x41464649);
        command.putInt(ids.length / ID_SIZE).putInt(ID_SIZE).putInt(16);
        command.put(ids);
        return command.array();
    }

    private static ByteBuffer littleEndian(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
