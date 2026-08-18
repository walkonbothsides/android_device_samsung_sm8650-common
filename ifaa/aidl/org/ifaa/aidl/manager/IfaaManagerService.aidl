/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ifaa.aidl.manager;

interface IfaaManagerService {
    int getSupportBIOTypes();
    int startBIOManager(int authType);
    String getDeviceModel();
    byte[] processCmd(inout byte[] data);
    int getVersion();
    String getExtInfo(int authType, String key);
    void setExtInfo(int authType, String key, String value);
    int getEnabled(int authType);
    int[] getIDList(int authType);
}
