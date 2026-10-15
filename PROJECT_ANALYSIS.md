# Analisis Mendalam Proyek xCam

**Tanggal analisis:** 15 Oktober 2026  
**Ruang lingkup:** source aplikasi xCam, konfigurasi Android/Gradle, hook Xposed, alur media, UI/editor, pengujian, serta konfigurasi submodule FFmpegKitNext.  
**Status:** analisis awal diikuti perbaikan source pada jalur recording, hook, media, frame, editor, dan provider.

## Ringkasan eksekutif

xCam adalah aplikasi Android satu modul (`:app`) yang menggabungkan UI pemilihan dan penyuntingan media, pemrosesan foto/video, engine playback virtual, pemrosesan frame, dan hook Xposed. Pemisahan komponen utama cukup jelas dan proyek memiliki banyak tes logika murni.

Risiko yang masih tersisa terutama ada pada integrasi kamera dan perilaku perangkat nyata: remux masih berjalan sinkron setelah `MediaRecorder.stop()` dan jalur kamera/YUV/shader belum divalidasi. Audio dan video kini dipetakan ke timeline keluaran bersama; remux sudah lulus tes fixture di Redmi Note 7/Android 13. AAR FFmpegKitNext disertakan di Maven lokal yang dilacak Git, sementara source upstream tetap ditautkan sebagai submodule. Panduan build mendokumentasikan lisensi GPL/x264 dan opsi APK per-ABI, dengan APK universal tetap sebagai default.

**Status validasi saat analisis:**

- Setelah perbaikan source, `:app:testDebugUnitTest` berhasil: **189 tes lulus**.
- Setelah perbaikan source, `:app:assembleDebug` berhasil.
- `:app:lintDebug` berhasil dijalankan dengan **0 temuan** setelah MediaPipe Tasks Vision dinaikkan ke 1.1.1.
- `MediaRemuxerDeviceTest` berhasil pada Redmi Note 7/Android 13, termasuk audio/video melintasi batas loop; hook kamera, GPU shader, LSPosed, dan aplikasi target tetap belum diuji.
- Source ini dipublikasikan sebagai repository Git baru dengan satu commit awal; tidak membawa riwayat commit dari repository sebelumnya.

Hasil build dan tes tersebut menunjukkan source dapat dikompilasi dan tes unit yang ada lolos; hasil itu **tidak membuktikan** semua fungsi kamera berperilaku normal di perangkat.

## Arsitektur dan alur data

### Komponen utama

| Komponen | Lokasi | Tanggung jawab |
|---|---|---|
| UI dan editor | `app/src/main/java/com/zkincamcam/xcam/ui/` | Pemilihan media, pengaturan transformasi, preview, crop, gesture, riwayat edit, ekspor |
| Xposed entry dan koordinasi | `xposed/XCamModule.kt`, `xposed/XCamInjectors.kt` | Inisialisasi per proses target, konteks, settings, engine, dan registrasi hook |
| Hook kamera | `hooks/` | Intersepsi Camera1, Camera2, CameraX, ImageReader, MediaRecorder, file/media, UI, dan WebRTC |
| Capture/media core | `core/capture/` | Ekstraksi frame, cache still capture, konversi YUV, pacing, filter, face/liveness processing |
| Engine dan surface | `core/engine/`, `core/surface/` | Playback media virtual serta pengelolaan surface preview |
| Media import/export | `utils/MediaConverter.kt`, `core/video/PhotoVideoEngine.kt` | Preprocessing, pembuatan video antara, transformasi Media3, dan pemotongan video |
| Recording remux | `utils/MediaRemuxer.kt`, `utils/VideoLoopTimeline.kt` | Penggantian hasil rekaman dengan rentang video virtual, termasuk pengulangan sumber |
| Settings/provider | `core/settings/SettingsManager.kt`, `data/SettingsProvider.kt` | Berbagi path media, rotasi, mirror, dan status antara manager app dan proses target |

### Alur media virtual

1. `MainActivity` menerima media pilihan dan menyiapkan impor.
2. Foto diproses melalui `MediaConverter`, `PhotoVideoEngine`, FFmpegKitNext dan Media3 Transformer; video dapat disalin atau dipotong/ditransformasi.
3. Media hasil disimpan di storage internal aplikasi, kemudian path dan transformasi terkait disimpan sebagai settings.
4. Proses target membaca settings melalui provider dan memulai playback virtual melalui `XCamEngine`/`MediaEngine`.
5. Hook preview mengarahkan surface kamera ke output engine. Hook still capture dan ImageReader menggunakan `CaptureManager`, `XCamCapture`, dan `YuvFrameProcessor`.
6. Untuk rekaman `MediaRecorder`, hook menangkap posisi playback dan durasi, lalu memanggil `MediaRemuxer` setelah recorder berhenti.

`HookGuard` merupakan proteksi penting untuk mencegah operasi internal modul masuk kembali ke hook yang sama. Implementasinya memakai depth counter `ThreadLocal` dan memulihkan state lewat `finally`.

## Temuan prioritas tinggi

| Prioritas | Area/file | Temuan | Dampak potensial |
|---|---|---|---|
| Ditangani dengan mitigasi | `hooks/CaptureHook.java` — penulisan Content URI di `remuxRecording()` | Sebelum mengganti isi URI, implementasi menyalin recording asli ke backup, menulis hasil remux, memverifikasi panjang, dan mencoba memulihkan backup bila write/verifikasi gagal. | Mengurangi risiko kegagalan I/O biasa. ContentResolver generik tidak memberi transaksi atomik; force-stop, kehabisan ruang, atau kegagalan provider saat pemulihan masih dapat meninggalkan target parsial. URI yang tidak dapat dibaca untuk backup tidak ditimpa. |
| Ditangani sebagian | `hooks/CaptureHook.java` — hook `openFileDescriptor()` | Penulisan JPEG dibatasi ke URI bertipe `image/*`; penulisan memakai duplikasi `ParcelFileDescriptor` agar descriptor aplikasi tidak tertutup. | Mengurangi gangguan pada output video, tetapi MIME/provider masih perlu diuji di perangkat. |
| Ditangani | `hooks/CaptureHook.java` — hook `FileOutputStream.write()` | Hook memvalidasi offset/rentang, membaca signature JPEG pada offset yang diberikan, lalu mengatur offset payload pengganti ke nol. | Mencegah pemeriksaan signature dari byte yang salah dan menghindari offset aplikasi pada buffer pengganti. |
| Ditangani dengan preflight | `hooks/ImageReaderHook.java` — penggantian JPEG buffer | Buffer writable dan kapasitas diverifikasi sebelum menulis melalui duplicate; posisi/limit buffer penerima diatur ke payload baru. | Menghindari perubahan pada buffer read-only/kecil; masih memerlukan pengujian ImageReader OEM. |
| Dipertahankan sesuai keputusan perilaku | `hooks/CaptureHook.java` — pemrosesan `MediaRecorder` | Remux tetap sinkron setelah `stop()` agar file hasil virtual siap saat `stop()` kembali. | Pengguna memilih mempertahankan jaminan kesiapan hasil; kemungkinan latency/ANR tetap perlu diukur pada perangkat target. |
| Ditangani | `settings.gradle.kts`, Maven lokal dan submodule FFmpeg | Dependensi `com.arthenica:ffmpeg-kit-next:9.0.0` diselesaikan dari `third_party/ffmpeg-kit-next-maven`, yang menyertakan AAR/POM dalam repo aplikasi; source FFmpegKitNext tetap direferensikan sebagai submodule. | Clone repo memerlukan checkout submodule untuk source lengkap, tetapi AAR sudah ikut dan tidak perlu dibangun agar Gradle dapat resolve dependency. |

## Temuan penting lainnya

### Remux video dan sinkronisasi

- Di `utils/MediaRemuxer.kt`, PTS video encoder kini dipertahankan pada timeline output yang sama dengan audio, yang dipetakan dari waktu sumber setiap segmen. Timestamp encoder negatif atau tidak meningkat ditolak agar hasil tidak valid tidak mengganti rekaman asli. Tes instrumentasi pada Redmi Note 7/Android 13 telah melewati batas loop serta memvalidasi track, durasi, dan offset awal audio/video.
- Pemeriksaan kelengkapan video kini memperhitungkan akhir durasi frame dengan toleransi maksimum sekitar dua frame, bukan toleransi minimum 500 ms. Belum ada uji codec perangkat untuk memastikan PTS encoder selalu merepresentasikan timestamp yang diminta.
- `MediaRemuxer` tetap dipanggil inline dari hook `MediaRecorder.stop()` untuk memastikan output sudah tersedia sebelum pemanggil melanjutkan; efeknya terhadap latency dan ANR perlu diukur pada perangkat target sebelum mendesain mode asinkron.
- Perulangan segmen melakukan EOS, `flush()`, `start()` decoder, lalu seek ke segmen berikutnya. Tidak ada cacat pasti yang teridentifikasi hanya dari pemeriksaan source, tetapi perilaku codec dan surface timestamp berbeda antarperangkat. Perlu uji rekaman pendek, lintas satu batas loop, dan lebih dari satu loop.
- `MediaRemuxer` menerima target `File`, bukan Content URI. Caller saat ini membuat file sementara untuk target URI, lalu menyalin hasilnya. API remux sendiri tidak mengelola transaksi atomik terhadap Content URI.
- Penggantian target berupa file biasa menggunakan output sementara serta upaya mempertahankan/memulihkan target, yang lebih aman daripada menulis langsung hasil transcode ke file target.

### Pemrosesan frame dan cache

- `core/capture/YuvFrameProcessor.kt`: semua plane YUV kini diperiksa writable dan kapasitas/layout-nya dipreflight sebelum ditulis. `YuvPlaneWriter` menolak buffer terpotong agar tidak menghasilkan write parsial.
- `YuvFrameProcessor.flush()` memakai generation counter sehingga konversi lama yang selesai setelah pergantian media tidak diterbitkan sebagai frame baru. `CaptureManager` juga mengabaikan hasil stream extraction dari generation lama.
- `core/capture/CaptureCache.kt`: cache JPEG kini dikaitkan ke media path dan di-invalidasi saat path berubah.
- Jalur resize `YuvFrameProcessor` kini mendaur ulang bitmap hasil resize tambahan.
- Masih ada celah race yang sangat pendek apabila perubahan media terjadi bersamaan dengan penulisan frame yang sudah mengambil referensi frame untuk dipakai; ini perlu validasi di perangkat.

### Impor/ekspor media

- `utils/MediaConverter.kt`: jalur salin tanpa trim kini menganggap stream null/hasil kosong sebagai gagal dan memverifikasi output. Jalur Transformer juga memverifikasi output pada callback selesai dan menghapus hasil parsial saat gagal.
- Konversi, Transformer, FFmpegKitNext, decoder, dan shader berjalan di Android runtime; tes JVM yang ada tidak mencakup keberhasilan ekspor pada semua format, resolusi, atau codec.

### UI/editor

- `ui/MainActivity.kt`: sudut rotasi dua jari kini dihitung dari koordinat kedua pointer, bukan dari span horizontal saja.
- Crop state di-reset saat memilih media baru; crop yang telah diterapkan dapat dipulihkan lewat URI sumber saat undo/redo.
- Analisis landmark memakai satu executor, task sebelumnya dibatalkan, dan hasil hanya diterapkan jika generation serta bitmap masih aktif.
- `ui/LiveEditorRenderer.kt`: texture LUT diunggah ulang saat bitmap LUT berubah dan dihapus saat LUT dilepas.
- Snapshot undo/redo kini menyimpan translasi, catchlight, HD, preset beauty, LUT URI, crop fraction, dan rentang trim selain slider sebelumnya.
- Impor media kini diekspor ke staging file, diverifikasi, lalu dipasang ke `filesDir`; media aktif yang lama dipertahankan sampai commit berhasil, dengan backup dan pemulihan pada jalur gagal.
- Tes UI saat ini lebih banyak menguji logika murni seperti geometri crop, transform, gesture math, dan operasi stack history; belum menguji integrasi perubahan state pada Activity/renderer.

### Hook kamera dan kompatibilitas aplikasi target

- `hooks/CameraxHook.java`: hook `takePicture()` saat ini hanya mencatat pemanggilan; tidak mengganti hasil foto pada jalur tersebut.
- `hooks/ImageReaderHook.java`: tracker kini dibersihkan saat reader ditutup; lifecycle dan perilaku ImageReader OEM tetap perlu diuji pada perangkat.
- `hooks/CameraHook.java`: kegagalan callback Camera1 tidak lagi menghentikan pengiriman callback berikutnya; kesesuaian callback sintetis dengan variasi aplikasi/OEM tetap perlu diuji.
- Banyak hook menangani kegagalan dengan catch/log lokal. Injector tetap mencatat “all hooks installed” setelah installer dipanggil, sehingga log tersebut tidak membuktikan semua hook berhasil dipasang.
- Hook bergantung pada metode refleksi, signature framework, identitas/jenis surface, dan implementasi OEM. Keberhasilan compile tidak menjamin kompatibilitas semua aplikasi kamera atau versi Android.

### Provider dan pengaturan

- `SettingsProvider.openFile()` kini menolak mode selain read, membatasi nama ke file `virtual.*` yang ada, memeriksa canonical parent, dan tidak lagi fallback ke file virtual lain jika nama salah.
- `SettingsManager.refreshSettings()` kini mencatat kegagalan query/parsing dengan log; nilai sebelumnya tetap dipertahankan dan diberi pesan log, bukan gagal diam-diam.
- Unit test kini mencakup resolver nama/path file virtual. Round-trip `SettingsProvider`/`SettingsManager`, otorisasi, dan kegagalan query tetap belum diuji.

### Dokumentasi dan klaim fitur

- Klaim README tentang universalitas dan tingkat keberhasilan 100% telah diganti dengan catatan kompatibilitas yang bergantung pada aplikasi/OEM; status CameraX dan MediaRecorder dijelaskan eksplisit.
- `BUILDING.md` memuat langkah membangun AAR FFmpegKitNext yang dibutuhkan clone bersih serta baseline 189 tes dan 0 temuan lint.
- `app/build.gradle.kts` mendukung APK per-ABI opsional melalui `-PxcamAbiSplits=true`; universal APK dan konfigurasi build default tidak berubah.
- Build split lokal menghasilkan APK universal sekitar 308 MB dan APK `arm64-v8a` sekitar 106 MB.
- `FOUNDATION.md` berisi catatan lama mengenai password di `gradle.properties`. File `gradle.properties` yang diperiksa saat analisis tidak berisi password tersebut, sehingga catatan itu tampaknya sudah usang dan perlu diverifikasi sebelum diperlakukan sebagai kondisi aktual.

## Temuan lint

Setelah dependency `com.google.mediapipe:tasks-vision` dinaikkan dari 1.1.0 ke 1.1.1, `lintDebug` selesai tanpa temuan.

## Kekuatan yang teridentifikasi

- Batas komponen UI, hook, engine, capture, dan media cukup terlihat pada struktur source.
- `HookGuard` memakai `ThreadLocal` depth counter dan pemulihan state dalam `finally`, sehingga operasi internal lebih terlindungi dari hook recursion.
- Banyak resource media memiliki jalur cleanup eksplisit, termasuk retriever, bitmap, codec, extractor, dan surface.
- File remux biasa memakai file sementara sebelum mengganti target.
- Ada tes unit murni untuk capture cache, pacing stream, konversi YUV, geometri/transform editor, trim, dan segmentasi loop video.
- Build debug dan tes unit berhasil pada workspace yang tersedia.

## Cakupan pengujian dan batas kepastian

Tes JVM tidak menjalankan atau mensimulasikan secara penuh:

- `MediaCodec`, decoder EOS/stop/reconfigure, dan encoder hardware vendor hanya diuji pada satu Redmi Note 7/Android 13 melalui fixture remux; dukungan perangkat lain belum diketahui.
- Audio/video muxing dan sinkronisasi timestamp hasil transcode.
- Layout aktual `Image.Plane` dan buffer camera dari beragam OEM.
- Camera1, Camera2, CameraX, MediaRecorder, dan urutan callback aplikasi target.
- Kompilasi dan output shader GPU pada perangkat.
- Interaksi LSPosed/libxposed serta kebijakan proses target.
- Ketahanan penulisan MediaStore/Content URI terhadap kegagalan I/O.

Karena itu, hasil ini adalah analisis source ditambah validasi build/unit test, bukan sertifikasi kompatibilitas perangkat.

## Rekomendasi tindak lanjut

1. **Validasi proteksi Content URI:** backup/rollback, verifikasi panjang, dan filter MIME sudah diterapkan. Uji MediaStore/provider nyata; ContentResolver generik tetap tidak menjamin atomisitas terhadap process kill.
2. **Tinjau batas hook tulis yang tersisa:** pastikan `openFileDescriptor` dan JPEG injection tidak memengaruhi urutan tulis masing-masing aplikasi target.
3. **Evaluasi dampak `stop()`:** ukur durasi remux dan dampak pada thread pemanggil di perangkat target. Remux tetap sinkron sesuai pilihan pengguna agar hasil siap saat `stop()` kembali.
4. **Jalankan pengujian hasil media di perangkat:** instrumentasi `MediaRemuxerDeviceTest` memeriksa durasi, PTS awal, stream audio/video, dan loop pada fixture buatan; masih perlu dijalankan pada codec perangkat.
5. **Uji di perangkat:** minimal satu perangkat per keluarga Android/codec yang didukung, dengan target aplikasi nyata dan log hook.
6. **Perluas regresi editor:** logika crop/LUT/undo state kini diperbaiki, tetapi integrasi Activity/renderer dan pemulihan LUT URI belum punya tes instrumentasi.
7. **Perkuat regresi frame:** preflight, generation check, dan cache-path binding sudah diterapkan; uji transisi sumber bersamaan dengan callback camera tetap dibutuhkan.
8. **Pastikan build dari clone bersih:** ikuti langkah di `BUILDING.md` untuk menghasilkan AAR lokal. Jalur ini terdokumentasi, tetapi masih membutuhkan Nix dan build native.
9. **Distribusi ABI:** gunakan APK split `arm64-v8a` yang lebih kecil untuk perangkat arm64; APK universal tetap tersedia sebagai fallback.

## Artefak yang diperiksa

- Konfigurasi: `app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, manifest, ProGuard rules.
- Dokumentasi: `README.md`, `BUILDING.md`, `FOUNDATION.md`, `AGENT.md`.
- Source: `ui/`, `hooks/`, `xposed/`, `core/capture/`, `core/engine/`, `core/surface/`, `core/settings/`, `core/video/`, `data/`, dan `utils/`.
- Submodule FFmpegKitNext: commit `5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3`; prebuilt Maven AAR berada di direktori yang diabaikan Git.
- Validasi lokal: `:app:testDebugUnitTest`, `:app:assembleDebug`, dan `:app:lintDebug`.
