# BUILDING.md — Panduan Build xCam (camcam)

Panduan lengkap build project `xCam` (`com.zkincamcam.xcam`) — modul Xposed kamera virtual dengan Media3 + FFmpegKitNext + ML Kit.

---

## 1. Persyaratan Environment

| Komponen | Versi | Catatan |
|---|---|---|
| JDK | 21 (atau 17+) | `java -version` harus >= 17 |
| Android SDK | API 37 (compileSdk) | minSdk 28, targetSdk 37 |
| Android SDK Build-Tools | sesuai AGP | otomatis dari SDK manager |
| Gradle | via wrapper | `./gradlew` — jangan install manual |
| Git | any | untuk clone |

### Environment variables (Linux/macOS)
```bash
export ANDROID_HOME=/usr/lib/android-sdk   # atau ~/Android/Sdk di Windows %LOCALAPPDATA%\Android\Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
```

Windows: set `ANDROID_HOME` di Environment Variables, atau biarkan `local.properties`:
```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

### Struktur khusus: FFmpegKitNext lokal
Project ini **tidak pakai Maven remote untuk FFmpegKit** — AAR lokal sudah disertakan di repository:
```
third_party/ffmpeg-kit-next-maven/
```
Repositori Maven-nya didaftarkan di `settings.gradle.kts` (`ffmpegKitNextLocal`). AAR tidak perlu dibangun ulang untuk clone normal. Source FFmpegKitNext tetap disertakan sebagai submodule di `third_party/ffmpeg-kit-next`.

Untuk memperbarui AAR dari source submodule:
```bash
# Pastikan source submodule sudah tersedia.
git submodule update --init --recursive
cd third_party/ffmpeg-kit-next

# Dari WSL/Linux dengan Nix terpasang:
./nix-android.sh -p android-r27d --enable-gpl --enable-lib-x264

# Salin Maven bundle ke folder yang dilacak repository aplikasi.
cp -R prebuilt/bundle-android-aar-24-maven/. ../ffmpeg-kit-next-maven/
```
FFmpegKitNext tidak menyediakan binary remote untuk dependency ini. Folder `prebuilt/`
diabaikan oleh `.gitignore` upstream; AAR hasil build harus disalin ke folder Maven aplikasi
agar perubahan ikut dilacak Git. Opsi `--enable-gpl --enable-lib-x264` dipakai karena
aplikasi menjalankan encoder x264; hasil bundle ini tunduk pada GPLv3. Gunakan NDK r27d
yang dipasok profile `android-r27d`. Folder `third_party/ffmpeg-kit-next-maven/` berada di
luar submodule dan dilacak oleh Git.
Untuk clone lengkap beserta source submodule, gunakan `git clone --recurse-submodules`.

---

## 2. Clone & Build Pertama Kali

```bash
git clone --recurse-submodules https://github.com/xazkindodev-boop/camcam-v2-clean.git
cd camcam-v2-clean
./gradlew --no-daemon build 2>&1 | tail -5   # warm-up: download dependencies (~3-5 menit)
```

Output sukses: `BUILD SUCCESSFUL`

---

## 3. Perintah Build Rutin

### Debug APK (untuk test di device)
```bash
./gradlew assembleDebug --no-daemon
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

APK debug default tetap universal untuk mempertahankan perilaku build lama. Untuk membuat
APK per-ABI yang lebih kecil sekaligus tetap menghasilkan APK universal:
```powershell
.\gradlew.bat assembleDebug --no-daemon -PxcamAbiSplits=true
```
Pilih file yang berakhiran ABI device (misalnya `arm64-v8a`) di
`app/build/outputs/apk/debug/`. APK universal tetap tersedia untuk distribusi umum;
APK spesifik ABI tidak boleh dipasang pada perangkat dengan ABI yang berbeda. Rilis
tersplit dapat dibuat dengan `assembleRelease` dan properti yang sama setelah signing
release dikonfigurasi. Pada build lokal saat ini, APK `arm64-v8a` sekitar 106 MB,
dibanding APK universal sekitar 308 MB.

### Release APK (signed)
Butuh keystore. Environment variables:
```bash
export RELEASE_STORE_FILE=/path/ke/xcam_release.jks
export RELEASE_STORE_PASSWORD=***
export RELEASE_KEY_ALIAS=xcam
export RELEASE_KEY_PASSWORD=***
./gradlew assembleRelease --no-daemon
```
Output: `app/build/outputs/apk/release/app-release.apk`

Alternatif via `~/.gradle/gradle.properties` (global, jangan di-commit):
```properties
RELEASE_STORE_FILE=/path/ke/xcam_release.jks
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=xcam
RELEASE_KEY_PASSWORD=***
```

Keystore release disimpan terpisah dari repository dan tidak disertakan. Tanpa mengatur
keystore serta variabel signing di atas, `assembleRelease` tidak dapat ditandatangani.

### Generate keystore baru (kalau belum ada)
```bash
keytool -genkeypair -v \
  -keystore xcam_release.jks \
  -alias xcam \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```
⚠️ Backup keystore ini — hilang = gak bisa update app di device yang sama (signature mismatch).

### Versioning
`versionCode`/`versionName` diambil dari `-P` property atau env:
```bash
./gradlew assembleDebug --no-daemon -PAPP_VERSION_CODE=2 -PAPP_VERSION_NAME=1.1
```
Atau env: `APP_VERSION_CODE=2 APP_VERSION_NAME=1.1`.

---

## 4. Testing

### Semua unit test
```bash
./gradlew testDebugUnitTest --no-daemon
```

### Uji remux pada perangkat Android
```powershell
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```
Tes membuat fixture audio/video lokal, menjalankan remux melewati batas loop, lalu
memeriksa track, durasi, dan offset awal audio/video. Hasil tetap bergantung pada codec
perangkat.

### Satu class test spesifik
```bash
./gradlew testDebugUnitTest --no-daemon \
  --tests com.zkincamcam.xcam.ui.LiveEditorTransformTest
```

### Lint
```bash
./gradlew lintDebug --no-daemon
```
Output report: `app/build/reports/lint-results-debug.html`

### Gate wajib sebelum commit/push (full gate)
```bash
./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
```
Baseline lokal setelah perbaikan: **189/189 unit test lulus** dan `lintDebug` selesai dengan **0 temuan**. Jalankan ulang gate di atas setelah perubahan karena hasil dapat berubah.

---

## 5. Instalasi ke Device

```bash
adb start-server
adb devices -l                  # cek device muncul + authorized
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Karena ini **modul Xposed**, device harus:
1. Root + punya LSPosed (atau Xposed framework kompatibel libxposed)
2. Install APK → aktifkan modul di LSPosed → centang scope target (kamera)
3. Reboot atau force-stop target app

### Log debug
```bash
adb logcat -d -v time | grep -E "XCamModule|MediaConverter|FATAL EXCEPTION"
```
Tag penting: `MediaConverter` (hasil ekspor), `PhotoVideoEngine`, `XCamModule`.

---

## 6. Arsitektur Singkat (untuk debugging)

- **UI**: `app/src/main/java/com/zkincamcam/xcam/ui/` — MainActivity, LiveEditor*
- **Hook Xposed**: `hooks/` + `xposed/XCamModule.kt`, `xposed/XCamInjectors.kt`
- **Media pipeline**: `MainActivity` (pilih foto) → `MediaConverter` (prepare export) → `PhotoVideoEngine` (intermediate video via FFmpegKitNext) → Media3 Transformer + `DeepLivenessShader` → `virtual.mp4`
- **Kamera injection**: `CaptureHook`/`ImageReaderHook` → `XCamModule` → `CaptureManager`/`XCamCapture`; frame YUV via `YuvFrameProcessor`
- **Transform**: preview via `LiveEditorTransform`/`LiveEditorRenderer`, ekspor lewat `MediaConverter` → `DeepLivenessShader`
- Cek `isIgnoringHooks` sebelum memanggil API yang di-hook (biar gak infinite loop)

---

## 7. Troubleshooting

| Masalah | Solusi |
|---|---|
| `SDK location not found` | Bikin `local.properties` dengan `sdk.dir=/path/to/sdk`, atau set `ANDROID_HOME` |
| `Failed to resolve: com.arthenica:ffmpeg-kit-next:9.0.0` | Pastikan folder `third_party/ffmpeg-kit-next-maven` ikut ter-checkout dan memuat AAR/POM |
| `JVM target mismatch` | Pastikan JDK 17/21, bukan 11 — cek `java -version` |
| `OutOfMemoryError` saat build | Naikkan di `gradle.properties`: `org.gradle.jvmargs=-Xmx4g` |
| `signingConfig ... storeFile is null` | Env var `RELEASE_STORE_*` belum diset untuk release build |
| Gradle daemon hang | `./gradlew --stop && rm -rf ~/.gradle/caches/` (terakhir) lalu build ulang |
| Test gagal `not mocked` | Sudah diset `isReturnDefaultValues = true` — kalau masih, tambahin mock sesuai dependency yang static-init-nya butuh android.util.Log |
| Configuration cache error | `./gradlew --no-daemon --rerun-tasks` atau hapus `.gradle/` di root project |

---

## 8. CI / Cross-Platform Notes

- Script Windows: `.\gradlew.bat` (bukan `./gradlew`)
- Path Windows: `app\build\outputs\apk\debug\app-debug.apk`
- `settings.gradle.kts` punya `foojay-resolver-convention` — Gradle otomatis download JDK toolchain kalau JDK lokal gak cocok
- `org.gradle.configuration-cache=true` aktif — kalau build script berubah drastis, bisa perlu `--rerun-tasks`

---

*Panduan build xCam; perbarui jika struktur build berubah.*
