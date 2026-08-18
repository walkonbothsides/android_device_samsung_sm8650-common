/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ifaa.aidl.manager;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class IfaaManagerServiceImpl extends Service {
    private static final String TAG = "IfaaManagerService";
    private static final String SENSOR_LOCATION_KEY = "org.ifaa.ext.key.GET_SENSOR_LOCATION";
    private static final String HAL_DESCRIPTOR = "vendor.samsung.hardware.ifaa.ISehIfaa";
    private static final String HAL_SERVICE = HAL_DESCRIPTOR + "/default";
    private static final String FP_HAL_DESCRIPTOR =
            "vendor.samsung.hardware.biometrics.fingerprint.ISehFingerprint";
    private static final String FP_HAL_SERVICE = FP_HAL_DESCRIPTOR + "/default";
    private static final String CALLBACK_DESCRIPTOR =
            "vendor.samsung.hardware.ifaa.ISehIfaaDataCallBack";
    private static final String HAL_HASH = "b122cacb3077afbbf5a4e4cdaff557cbe931534d";
    private static final int MAX_COMMAND_BYTES = 512 * 1024;

    private final Object mHalLock = new Object();
    private FingerprintManager mFingerprintManager;
    private KeyguardManager mKeyguardManager;

    private final IfaaManagerService.Stub mBinder = new IfaaManagerService.Stub() {
        @Override
        public int getSupportBIOTypes() {
            long token = Binder.clearCallingIdentity();
            try {
                if (mFingerprintManager == null || !mFingerprintManager.isHardwareDetected()) {
                    return 0;
                }
                for (FingerprintSensorPropertiesInternal sensor
                        : mFingerprintManager.getSensorPropertiesInternal()) {
                    if (sensor.sensorType == FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC
                            || sensor.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL) {
                        return 17;
                    }
                }
                return 1;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int startBIOManager(int authType) {
            if (authType != 1) {
                return -1;
            }
            long token = Binder.clearCallingIdentity();
            try {
                if (mFingerprintManager == null || !mFingerprintManager.isHardwareDetected()) {
                    return -1;
                }
                Intent intent = new Intent(Settings.ACTION_FINGERPRINT_ENROLL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return 0;
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to open fingerprint enrollment", e);
                return -1;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String getDeviceModel() {
            return Build.MANUFACTURER + "-" + Build.MODEL.replace('-', '_');
        }

        @Override
        public byte[] processCmd(byte[] data) {
            if (data == null || data.length == 0 || data.length > MAX_COMMAND_BYTES) {
                return null;
            }
            int command = IfaaCommandParser.getCommandId(data);
            if (!IfaaCommandParser.isAllowed(command)) {
                Log.w(TAG, "Rejected IFAA command 0x" + Integer.toHexString(command));
                return null;
            }

            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mHalLock) {
                    if (command == 0x02 || command == 0x04) {
                        syncFingerprintIds();
                    }
                    return invokeHal(data);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getVersion() {
            return 4;
        }

        @Override
        public String getExtInfo(int authType, String key) {
            if (authType != 1 || !SENSOR_LOCATION_KEY.equals(key)) {
                return null;
            }

            Rect bounds = new Rect();
            long token = Binder.clearCallingIdentity();
            try {
                if (mFingerprintManager != null) {
                    List<FingerprintSensorPropertiesInternal> sensors =
                            mFingerprintManager.getSensorPropertiesInternal();
                    if (!sensors.isEmpty()) {
                        SensorLocationInternal location = sensors.get(0).getLocation();
                        bounds = location.getRect();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return String.format(Locale.US,
                    "{\"type\":0,\"fullView\":{\"startX\":%d,\"startY\":%d,"
                            + "\"width\":%d,\"height\":%d,\"navConflict\":false}}",
                    bounds.left, bounds.top, bounds.width(), bounds.height());
        }

        @Override
        public void setExtInfo(int authType, String key, String value) {}

        @Override
        public int getEnabled(int authType) {
            if (authType != 1) {
                return 1001;
            }
            long token = Binder.clearCallingIdentity();
            try {
                if (mFingerprintManager == null
                        || !mFingerprintManager.isHardwareDetected()
                        || !mFingerprintManager.hasEnrolledFingerprints()) {
                    return 1002;
                }
                return mKeyguardManager != null && mKeyguardManager.isKeyguardSecure() ? 1000 : 1003;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int[] getIDList(int authType) {
            if (authType != 1 || mFingerprintManager == null) {
                return new int[0];
            }
            long token = Binder.clearCallingIdentity();
            try {
                List<Fingerprint> fingerprints = mFingerprintManager.getEnrolledFingerprints();
                int[] ids = new int[fingerprints.size()];
                for (int i = 0; i < fingerprints.size(); i++) {
                    ids[i] = fingerprints.get(i).getBiometricId();
                }
                return ids;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mFingerprintManager = getSystemService(FingerprintManager.class);
        mKeyguardManager = getSystemService(KeyguardManager.class);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private byte[] invokeHal(byte[] command) {
        IBinder hal = ServiceManager.checkService(HAL_SERVICE);
        if (hal == null) {
            Log.e(TAG, "IFAA HAL is unavailable");
            return null;
        }

        HalResult result = new HalResult();
        IfaaCallback callback = new IfaaCallback(result);
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(HAL_DESCRIPTOR);
            request.writeTypedObject(new SehIfaaData(command), 0);
            request.writeStrongBinder(callback);
            if (!hal.transact(2, request, reply, 0)) {
                Log.e(TAG, "IFAA HAL does not implement invoke_cmd");
                return null;
            }
            reply.readException();
            if (result.status != 0) {
                Log.w(TAG, "IFAA HAL returned status " + result.status);
            }
            return result.data;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "IFAA HAL transaction failed", e);
            return null;
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private void syncFingerprintIds() {
        byte[] response = invokeFingerprintHal(IfaaFingerprintProtocol.createFidoRequest());
        byte[] ids = IfaaFingerprintProtocol.parseFidoResponse(response);
        if (ids == null) {
            Log.e(TAG, "Unable to read Samsung fingerprint IDs");
            return;
        }
        byte[] result = invokeHal(IfaaFingerprintProtocol.createSetIdListCommand(ids));
        if (result == null || result.length < 4
                || (result[0] | result[1] | result[2] | result[3]) != 0) {
            Log.e(TAG, "Unable to update IFAA fingerprint IDs");
        }
    }

    private byte[] invokeFingerprintHal(byte[] input) {
        IBinder hal = ServiceManager.checkService(FP_HAL_SERVICE);
        if (hal == null) {
            Log.e(TAG, "Samsung fingerprint HAL extension is unavailable");
            return null;
        }

        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(FP_HAL_DESCRIPTOR);
            request.writeInt(0);
            request.writeInt(9);
            request.writeInt(0);
            request.writeByteArray(input);
            if (!hal.transact(1, request, reply, 0)) {
                Log.e(TAG, "Samsung fingerprint HAL does not implement sehRequest");
                return null;
            }
            reply.readException();
            return reply.readInt() != 0 ? readSehFingerprintResult(reply) : null;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Samsung fingerprint HAL transaction failed", e);
            return null;
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static byte[] readSehFingerprintResult(Parcel parcel) {
        int start = parcel.dataPosition();
        int size = parcel.readInt();
        if (size < 4 || start > Integer.MAX_VALUE - size || size - 4 > parcel.dataAvail()) {
            throw new BadParcelableException("Invalid SehResult size");
        }
        int end = start + size;
        try {
            if (parcel.dataPosition() >= end) {
                return null;
            }
            int result = parcel.readInt();
            if (parcel.dataPosition() >= end) {
                return null;
            }
            byte[] data = parcel.createByteArray();
            if (result <= 0 || data == null || result > data.length) {
                return null;
            }
            return result == data.length ? data : Arrays.copyOf(data, result);
        } finally {
            parcel.setDataPosition(end);
        }
    }

    private static final class HalResult {
        int status;
        byte[] data;
    }

    private static final class IfaaCallback extends Binder implements IInterface {
        private final HalResult mResult;

        IfaaCallback(HalResult result) {
            mResult = result;
            markVintfStability();
            attachInterface(this, CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
            }
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                case 0x00ffffff:
                    reply.writeNoException();
                    reply.writeInt(1);
                    return true;
                case 0x00fffffe:
                    reply.writeNoException();
                    reply.writeString(HAL_HASH);
                    return true;
                case 1:
                    mResult.status = data.readInt();
                    mResult.data = data.readInt() != 0 ? readSehIfaaData(data) : null;
                    data.enforceNoDataAvail();
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private static byte[] readSehIfaaData(Parcel parcel) {
        int start = parcel.dataPosition();
        int size = parcel.readInt();
        if (size < 4 || start > Integer.MAX_VALUE - size
                || size - 4 > parcel.dataAvail()) {
            throw new BadParcelableException("Invalid SehIfaaData size");
        }
        int end = start + size;
        try {
            if (parcel.dataPosition() >= end) {
                return null;
            }
            byte[] data = parcel.createByteArray();
            if (parcel.dataPosition() >= end) {
                return null;
            }
            int length = parcel.readInt();
            if (data == null || length <= 0 || length > data.length) {
                return null;
            }
            return length == data.length ? data : Arrays.copyOf(data, length);
        } finally {
            parcel.setDataPosition(end);
        }
    }

    private static final class SehIfaaData implements Parcelable {
        private final byte[] mData;

        SehIfaaData(byte[] data) {
            mData = data;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int getStability() {
            return PARCELABLE_STABILITY_VINTF;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            int start = parcel.dataPosition();
            parcel.writeInt(0);
            parcel.writeByteArray(mData);
            parcel.writeInt(mData.length);
            int end = parcel.dataPosition();
            parcel.setDataPosition(start);
            parcel.writeInt(end - start);
            parcel.setDataPosition(end);
        }
    }
}
