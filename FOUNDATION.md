# xCam — Fondasi Hardening Plan (v1)

Status project: **build hijau, test hijau, lint merah**. Fondasi kode jalan; fondasi
*hygiene* belum. Dokumen ini daftar semua temuan lint + rencana perbaikan berurutan.
Prinsip: **tidak menambah fitur sampai lint 0 error.**

---

## 0. Progress (8 Okt 2026, sesi hardening #1)

- ✅ **E2 NewApi (YuvPlaneWriter)** — FIXED: bulk `ByteBuffer.put(int,byte[],int,int)`
  (API 35+) diganti absolute single-byte put. Crash di Android < 15 hilang.
- ✅ **E1 UnsafeOptInUsageError (170)** — FIXED: `@UnstableApi` class-level di 8 hook Java
  (CaptureHook, Camera2Hook, CameraHook, CameraxHook, ImageReaderHook, IntentHook, UIHook,
  WebRtcHook) + `@UnstableApi` di `XCamInjectors.kt`. Anotasi dipropagasi dari `XCamModule`
  yang sudah `@UnstableApi`.
- ✅ **E3 PrivateApi (2)** — FIXED: `@SuppressLint("PrivateApi")` + komentar alasan di
  `Camera2Hook.hookCleanup` (hook internal Camera2 impl; by design untuk Xposed).
- **Verifikasi:** `lintDebug` EXIT 0, **errors 171 → 0**. `clean assembleDebug
  testDebugUnitTest` EXIT 0, APK 188 MB.

## 0b. Progress (8 Okt 2026, sesi hardening #2 — fondasi SELESAI 100%)

Refactor arsitektur capture + semua warning dibereskan. Satu commit per langkah.

**Refactor (TDD, tracer bullet per langkah):**
- ✅ HookGuard (`8315376`) — ThreadLocal depth counter, `withGuard{}` struktural menggantikan
  pasangan lambda `isIgnoringHooks`/`setIgnoringHooks`.
- ✅ StreamPacer (`5523a3a`) — state machine pacing murni, 4 field keluar dari CaptureManager.
- ✅ CaptureCache (`ac4dcba`) — window capture + JPEG cache, expiry di wall-clock sama.
- ✅ XCamCapture review + `XCamCaptureSeekTest` 7 test (`17a4859`).
- ✅ LivenessShaders (`2a1b3d2`) — GLSL keluar dari DeepLivenessShader; `LivenessUniforms`
  konstanta compile-time (typo uniform = compile error, bukan efek diam-diam mati).
  Verifikasi ketat: 18/18 vertex + 96/96 fragment baris identik.
- ✅ LivenessConfig (`ea072ff`) — 7 argumen posisional `convertImageToMp4` → satu data class.
- ✅ CaptureHook (`4664c4a`) — 3 lokasi `setIgnoringHooks` + try/finally → `runIgnoringHooks {}`.

**Lint warnings — bersih total:**
- ✅ ExifInterface framework → `androidx.exifinterface` (`ee2e509`)
  (+ `unitTests.isReturnDefaultValues=true`, wajib karena static init androidx menyentuh `Log`).
- ✅ HardcodedText 2 → strings.xml.
- ✅ UnusedResources 17 → 0: hapus 7 warna, 7 string, 2 XML rule kosong, semua `ic_launcher_round`.
- ✅ GradleDependency/NewerVersionAvailable/AGP/UseTomlInstead 12 → 0: Gradle 9.8.1
  (+ wrapper sha256 resmi), AGP 9.4.1, core-ktx 1.19.1, appcompat 1.8.0, exifinterface 1.4.2,
  media3 1.11.1, libxposed 102.0.0, mlkit ke version catalog.
- ✅ UseKtx 5 → 0 + TrimLambda 1 → 0 (`Bitmap.scale`/`createBitmap` KTX, `.trim()`).
- ✅ Icon set 10 → 0: adaptive icon (anydpi, `minSdk 28`), `<monochrome>` ditambah, PNG legacy
  per-density dihapus, foreground ke `drawable-nodpi`.
- ✅ OldTargetApi → targetSdk 36 → 37.

**GATE AKHIR TERCAPAI (8 Okt 2026):**
```
./gradlew assembleDebug testDebugUnitTest lintDebug
BUILD SUCCESSFUL · 79/79 test lulus · lint TOTAL FINDINGS: 0 (0 error, 0 warning)
```

**Catatan:** bump targetSdk 37 + Gradle/AGP masih perlu uji device pertama user.
Catatan lama tentang password keystore plaintext perlu diverifikasi: pada pemeriksaan 15 Okt
2026, root `gradle.properties` tidak memuat password. Rahasia signing sebaiknya tetap disimpan
di environment variables atau konfigurasi Gradle lokal di luar repository. Jika pernah masuk
riwayat repository, rotasi kredensialnya.

---

## 1. Baseline saat audit (8 Okt 2026)

- Gradle 9.5.0 + AGP 9.3.1, compileSdk 37, targetSdk 36, minSdk 28
- Kotlin: **0 warning** (compileDebugKotlin + unit tests)
- Java: 1 catatan deprecation (CameraHook.java → `android.hardware.Camera`, **disengaja**:
  hook legacy API; sudah di-`@SuppressWarnings("deprecation")`)
- `assembleDebug` ✅ (188 MB debug APK), `testDebugUnitTest` ✅ (EXIT 0)
- **lintDebug: 171 errors, 60 warnings, 1 hint**

## 2. Rincian lint (berdasarkan dampak, bukan jumlah)

### 🔴 WAJIB DIPERBAIKI (error nyata / bug potensial)

| # | Issue | Jumlah | Lokasi | Penyebab | Fix |
|---|-------|--------|--------|----------|-----|
| E1 | `UnsafeOptInUsageError` | 170 | semua hooks (CaptureHook 42, Camera2Hook 35, ImageReaderHook 28, CameraHook 22, UIHook 13, WebRtcHook 11, IntentHook 9, CameraxHook 8, XCamInjectors 2) | Media3/AndroidX API `@UnstableApi` dipakai tanpa opt-in | Tambah `@OptIn(markerClass = UnstableApi.class)` di level class (Java) / `@androidx.annotation.OptIn(UnstableApi::class)` (Kotlin) per file. 1 baris per file, bukan 170. |
| E2 | `NewApi` | 1 | `YuvPlaneWriter.kt:37` — `ByteBuffer.put(int, byte[], int, int)` butuh API 35, minSdk 28 | bulk put overload baru | Ganti ke loop per-byte yang sudah ada di bawahnya (fallback), atau guard `if (Build.VERSION.SDK_INT >= 35)` dengan fallback loop. **Ini crash nyata di Android < 15.** |
| E3 | `PrivateApi` | 2 | `Camera2Hook.java:141,149` — hook `CameraDeviceImpl`/`CameraCaptureSessionImpl` via reflection | hook internal impl class | Disengaja untuk Xposed (hook butuh class impl). Supress dengan komentar `// LINT.(PrivateApi: Xposed hook targets internal impl; runtime guarded by try/catch)` di level method, JANGAN matikan check global. |

### 🟡 SEHAT DIBERESKAN (warning berikutnya)

| # | Issue | Jumlah | Catatan |
|---|-------|--------|---------|
| W1 | `UnusedResources` | 17 | string/drawable tak terpakai — hapus atau `tools:keep` yang disengaja |
| W2 | `IconLauncherShape/IconDipSize/IconDuplicates/IconLocation/MonochromeLauncherIcon` | 15 | rapikan asset launcher + tambah monochrome (Android 13 themed icon) |
| W3 | `GradleDependency/NewerVersionAvailable/AndroidGradlePluginVersion` | 10 | bump dependency ke versi stabil terkini (jangan auto-upgrade libxposed) |
| W4 | `UseKtx` | 5 | ganti Java-idiom ke KTX di file Kotlin |
| W5 | `ExifInterface` | 2 | `ExifForensicEngine.kt` → ganti ke `androidx.exifinterface.media.ExifInterface` (drop-in, sama API) |
| W6 | `HardcodedText` | 2 | pindah ke `strings.xml` |
| W7 | `OldTargetApi` | 1 | targetSdk 36 → 37 saat compileSdk sudah 37 (uji dulu behavior change) |
| W8 | sisanya (Overdraw, UselessParent, ContentDescription, ObsoleteSdkInt, UseTomlInstead, TrimLambda) | ~8 | bersihkan sambil jalan |

### ✅ DIANGGEP OK (tidak diperbaiki)

- `CameraHook.java` deprecated API — legacy hook by design
- 170 `UnsafeOptInUsageError` = opt-in annotation, bukan bug — tapi tetap dibereskan
  supaya compiler yang jaga kontrak, bukan memory manusia.

## 3. Urutan eksekusi (satu PR/satu commit per step)

1. **Fix E2 (NewApi YuvPlaneWriter)** — crash risk di Android < 15. Prioritas #1.
2. **Fix E1 (UnstableApi opt-in)** — tambah anotasi class-level di 9 file. Verifikasi: lint errors 170 → 0.
3. **Fix E3 (PrivateApi suppress per-method)** — errors total harus 0.
4. **W5 ExifInterface → androidx** — drop-in, low risk.
5. **W1 UnusedResources + W6 HardcodedText** — bersih-bersih string.
6. **W3 dependency bumps** — satu commit per major bump biar mudah di-revert.
7. **W2 icons** (termasuk monochrome) — perlu desain, bisa terakhir.
8. **W7 targetSdk 37** — uji di device dulu sebelum bump.
9. Target akhir: `./gradlew lintDebug` → **0 errors, 0 warnings**.
   Kalau ada warning yang memang by-design → pakai `lint.xml` per-check suppress DENGAN KOMMENTAR alasan, bukan `abortOnError false`.

## 4. Aturan anti-regresi (biar agent nggak merusak lagi)

- Semua perubahan kode **wajib lulus**: `./gradlew lintDebug` 0 error sebelum commit.
- Commit per step (bukan raksasa), message format: `lint: fix <IssueId> (<n> sites)`.
- `gradle.properties`: jangan pernah commit password asli. Pemeriksaan terkini menemukan
  root file tanpa signing secret; tetap periksa konfigurasi lokal/global dan CI secara terpisah.
- Keystore release disimpan terpisah dari repository; build release tanpa konfigurasi
  signing tidak menghasilkan APK bertanda tangan.

## 5. Yang TIDAK dalam scope (keputusan user)

- Audio virtual / mic spoof — **dicoret** oleh owner.
- Fitur baru apapun — dilarang sampai fondasi 100%.

## 6. Command referensi (VPS)

```bash
export ANDROID_HOME=/usr/lib/android-sdk
cd ~/camcam
./gradlew clean assembleDebug testDebugUnitTest --no-daemon   # build + test
./gradlew lintDebug --no-daemon                               # lint gate
```
