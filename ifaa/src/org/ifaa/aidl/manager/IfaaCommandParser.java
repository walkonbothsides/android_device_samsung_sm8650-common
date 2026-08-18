/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ifaa.aidl.manager;

final class IfaaCommandParser {
    private IfaaCommandParser() {}

    static int getCommandId(byte[] data) {
        if (data == null || data.length < 8) {
            return -1;
        }

        long firstOffset = readUint32(data, 4) + 8;
        if (!hasUint32(data, firstOffset)) {
            return -1;
        }

        long commandOffset = firstOffset + readUint32(data, (int) firstOffset) + 4;
        if (!hasUint32(data, commandOffset)) {
            return -1;
        }

        long command = readUint32(data, (int) commandOffset);
        return command <= Integer.MAX_VALUE ? (int) command : -1;
    }

    static boolean isAllowed(int command) {
        switch (command) {
            case 0x01:
            case 0x02:
            case 0x04:
            case 0x08:
            case 0x10:
            case 0x20:
            case 0x40:
            case 0x80:
            case 0x81:
            case 0x82:
            case 0x83:
            case 0x84:
            case 0x85:
            case 0x86:
            case 0x87:
            case 0x88:
            case 0x89:
            case 0x8a:
            case 0x8b:
            case 0x8c:
            case 0x8d:
            case 0x8e:
            case 0x100:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasUint32(byte[] data, long offset) {
        return offset >= 0 && offset <= data.length - 4L;
    }

    private static long readUint32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }
}
