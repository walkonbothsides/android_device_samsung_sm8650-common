# Soter on SM-S9280

The S9280ZCS6DZF2 stock firmware (Android 16) supplies the Soter AIDL v2 HAL,
Tiger libraries, SecSoterService, SSKDS, the DRK HAL/client libraries, and
system/etc/security/sskds.crt. Both privileged APKs use the ROM platform signer.

## Integration

- SecSoterService reads the AOSP fingerprint sensor location instead of Samsung
  framework extensions. SM-S9280 has an ultrasonic under-display sensor.
- SSKDS embeds the four stock DeviceRootKeyService client classes. Its package
  whitelist and signature permission remain enforced; SecSoterService must
  match the running ROM platform signer.
- SM-S9280 uses the stock DRK provisioning branch. The SAK whitelist is unchanged.
- System crypto dependencies use libcrypto_soter.so; the vendor DRK core uses
  the existing libcrypto-v33.so compatibility library.
- Dedicated hal_soter_default and hal_drk_default SELinux domains run as system.
  DRK reads the actual UFS unique-number node and communicates with Samsung RIL
  to validate the device certificate. Keep its original executable path:
  /vendor/bin/hw/vendor.samsung.hardware.security.drk-service.
- The init configuration grants system-group access to the UFS node and creates
  the DRK log directory. SELinux labels only the required node and log subtree.

Re-extract the Soter sections from an unpacked stock partition dump:

    ./extract-files.py --section 'Soter*' --no-cleanup /path/to/partition-dump

The extraction fixups and patches are the source of the imported APK changes.
The generated vendor modules install the libraries, HAL declarations, init
scripts and certificate; common.mk installs the privileged permission allowlist.

## Manufacturer compatibility

e3q must retain the stock lowercase PRODUCT_MANUFACTURER := samsung.
WeChat includes Build.MANUFACTURER in its device information. In the live test,
changing Samsung to samsung and restarting the framework was followed by normal
device-configuration delivery and successful fingerprint-payment activation.
The manufacturer correction and framework restart were tested together.

## Validation

Verified on uwu Android 17 with SELinux enforcing, 2026-10-15:

- DRK certificate validation, ATTK generation and Samsung server registration.
- Native Soter support, ASK/auth-key generation and export.
- Fingerprint-authorized signing through the AOSP BiometricPrompt.
- Official WeChat 8.0.78: payment entry visible and activation successful.
  WeChat auth-key generation, initSign and finishSign all returned 0.
- Both HALs ran as system in their dedicated domains.
- SELinux compilation, neverallow checks, service/file contexts, and native ELF
  dependency/symbol checks passed.
- Freshly applying the APK patches to stock APKs reproduced the imported DEX.
  Extraction syntax, XML parsing and init-script verification passed.

The test APK, temporary root deployment and host analysis caches were removed.
Persistent TEE provisioning and WeChat data were retained. Full OTA build/flash,
production ROM boot integration and an actual monetary payment remain untested.
