# libsec-ril shims

Two vendor RIL wrappers chained in front of Samsung's real RIL
(`libsec-ril-impl.so`). Each loads the next library and swaps in its own
`onRequest`:

```text
rild -> libsec-ril.so (SMSC + tsds2) -> libsec-ril-euicc.so (eUICC) -> libsec-ril-impl.so (stock)
```

`libsec-ril.so` keeps the stock library name, so `rild` loads the chain
without any property changes.

## Layout

| File | Library | Role |
|------|---------|------|
| `libsec_ril_smsc_shim.cpp` | `libsec-ril.so` | CS SMS NULL-SMSC, tsds2 eSIM slot switch, synthetic GetEID |
| `libsec_ril_euicc_shim.cpp` | `libsec-ril-euicc.so` | eUICC: CLA fixup, MEP-A1 port inject |
| `Android.bp` | | Builds both; the next library in the chain is set with `REAL_LIB_NAME` |

Related pieces outside this directory:

| Path | Role |
|------|------|
| `../../extract-files.py` | Renames stock blob → `libsec-ril-impl.so`; binary NOPs for UICC enablement |
| `../../init/init.esim_switch.rc` | Bridges `persist.sys.esim_switch` → `vendor.calls.esim_switch` |
| `../../packages/EsimSwitcher` | Settings UI that sets the persist prop and refreshes EID |
| `hardware/samsung/packages/SamsungEsimSwitcher` | Settings toggle that switches the tsds2 hybrid slot through `TelephonyManager.setSimSlotMapping()` |

## How the wrap works

Each shim does the same thing:

1. `RIL_Init` `dlopen`s `/vendor/lib64/<REAL_LIB_NAME>` and calls its
   `RIL_Init`.
2. The returned function table's `onRequest` pointer is swapped for the shim's
   own, which forwards to the saved one. The table is modified in place, so the
   outer shim's hook ends up in front of the inner one.
3. `RIL_SAP_Init` is forwarded unchanged.

`libsec-ril.so` additionally maintains a detached watcher thread
(`esimSwitchWatch`) that polls `vendor.calls.esim_switch` and triggers the
tsds2 slot switch.

## SMSC shim (`libsec-ril.so`)

Logcat tag `sec-ril-shim`.

### 1. CS SMS — force NULL SMSC

**Problem:** Stock RIL mishandles SMSC for circuit-switched SMS.

**Fix:** For `RIL_REQUEST_SEND_SMS` / `SEND_SMS_EXPECT_MORE` only, replace the
SMSC pointer with `nullptr` and pass the PDU through. IMS / other SMS paths are
not touched.

### 2. tsds2 eSIM slot switch (OEM hook)

Samsung stock uses `ExecuteSlotSwitch` → OEM opcode
`SEC_SIM_LOW_LEVEL_CONTROL` (`0x06001415`) on **`RIL_SOCKET_1`**.

Payload (6 bytes):

```text
15 14 00 06 01 <type>
```

| `<type>` | Meaning |
|----------|---------|
| `0x10` | Switch hybrid slot to eSIM |
| `0x11` | Switch back to physical SIM |
| `0x20` | Post-switch follow-up (stock soft path) |

**Control property:** `vendor.calls.esim_switch` = `1` / `0`
(bridged from `persist.sys.esim_switch` by `init.esim_switch.rc`).

**Ready signal:** `vendor.calls.esim_ready` = `1` when `ril.simslottype2=1`.

**Enable path (`runEsimEnable`):**

1. OEM `0x10` on socket 1.
2. Wait until `ril.simslottype2=1`.
3. OEM `0x20` follow-up.
4. Nudge **phone1 only** (`RIL_SOCKET_2`): `RADIO_POWER` / optional
   `SET_SIM_CARD_POWER`. Do **not** thrash primary-socket radio power — that
   knocks the pSIM offline and races slot status.
5. Latch `vendor.calls.esim_ready=1`.

**Disable path:** OEM `0x11`, clear ready.

**Watcher extras:**

- Re-applies on boot if the persist prop is set.
- Recover path if persist wants eSIM but `simslottype2` flipped back (throttled).
- Breadcrumbs in `vendor.calls.esim_dbg` and logcat tag `sec-ril-shim`.

### 3. Synthetic GetEID (`BF3E`) from EFS

**Problem:** Telephony needs `EuiccCard.mCardId` (EID) for LPA. ES10 GetEID
over the modem can return empty even when the eUICC is present.

**Fix:** When a channel APDU looks like GetEID (`BF3E` + tag `5A`):

1. Read `/efs/FactoryApp/eID` (32 ASCII hex chars).
2. Complete the request locally with:

   ```text
   BF3E125A10<32-hex-EID>   SW=9000
   ```

3. Do not forward that APDU to the modem.

Logged as `geteid-synth` / `APDU-RSP … SYNTH sw=9000`.

### 4. APDU diagnostics

`OPEN_CHANNEL` / `CLOSE_CHANNEL` / `TRANSMIT_APDU_*` are logged with socket id,
channel, CLA/INS, ES10 tag class (`BF22`, `BF31`, …), and response SW. Short
status also goes to `vendor.calls.esim_dbg` for live debugging without full
logcat.

## eUICC shim (`libsec-ril-euicc.so`)

Logcat tag `sec-ril-euicc-shim`. Only `RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL`
is touched.

### 1. Samsung session-id CLA fixup

**Problem:** AOSP does `cla | channel` for logical-channel APDUs. Samsung
`OPEN_CHANNEL` returns session ids like **101** (not ISO 1..19). That yields
CLA `0xE5` for STORE DATA (`0x80 | 101`) and the card returns **`6986`**.

Stock's own LPA never hits this: it passes the raw CLA through
`iccTransmitApduLogicalChannelByPort`.

**Fix:** For `TRANSMIT_APDU_CHANNEL` with `sessionid > 3` whose CLA carries
**every** bit of the session id (the mark AOSP's OR leaves):

```text
cla = cla & ~sessionid
```

Example: `E5` → `80`. Modem `+CGLA` already keys off `sessionid`. Callers
that pass a correct raw CLA (ARA-M, OMAPI, other LPAs) rarely contain all
session bits and are left alone. Session ids with bit 7 set could not be
undone, but the RIL has only been seen handing out `101`.

Logged once as `APDU-CLA fix active` — it fires on every ES10 APDU.

### 2. MEP-A1 EnableProfile port inject (`BF31` only, gated)

**Problem:** The RIL reports `MEP_A1` in slot status but `NONE` in card
status. AOSP turns `NONE` on a two-port MEP-capable ATR into `MEP_B`, and
whichever report lands last wins: after boot the framework usually has
`MEP_A1`, after a live slot switch `MEP_B`. With `MEP_B`, AOSP omits
`targetPortNumber` (tag `82`) and EnableProfile returns **`6A80`**. Injecting
`82` on cards that are not MEP-A1 also yields **`6A80`**.

**Fix:** Rewrite EnableProfile only when `ril.esim.mep_mode` is `1` / `0,1` /
`,1`:

- Skip when AOSP already appended `8201xx` (framework had `MEP_A1`).
- Append `820101` (Android port 0 → eUICC port 1). The switcher only ever
  maps eUICC port 0; a profile on port 1 (two active eSIMs) would need
  `820102`.
- Bump short-form content length and `RIL_SIM_APDU.p3`.
- **Heap-allocate** the rewritten hex (`malloc`); `libril_sem` frees
  `apdu->data` after transmit — pointing at static storage aborts under MTE.

**Do not** inject on **DisableProfile (`BF32`)**.

Logged as `APDU-MEP inject port1`.

The eUICC shim deliberately carries **no per-APDU tracing**: a profile
download is thousands of APDUs. If a future ES10 failure needs APDU-level
detail, add the logging temporarily rather than keeping it in the tree.

## Properties

| Property | Side | Meaning |
|----------|------|---------|
| `persist.sys.esim_switch` | system | User/app intent (`0`/`1`); written by EsimSwitcher |
| `vendor.calls.esim_switch` | vendor | Shim input (from init bridge) |
| `vendor.calls.esim_ready` | vendor | Shim output: hybrid slot is eUICC |
| `vendor.calls.esim_dbg` | vendor | Last breadcrumb string |
| `ril.simslottype2` | RIL | `1` = slot 2 is eSIM |

## Debugging

Useful SW codes seen during bring-up:

| SW | Meaning in this work |
|----|----------------------|
| `9000` | Success |
| `6986` | Bad CLA (fixed by session CLA strip) |
| `6A80` | Incorrect parameters (missing/extra MEP port TLV) |
| MTE abort in `rild` | Rewritten `BF31` pointed at static storage; `libril_sem` then `free()`d it — use heap (`malloc`) instead |