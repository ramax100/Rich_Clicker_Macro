# Audit Android 12 — Rich Clicker Macro 0.8.0

Tanggal audit: 2026-10-31 (pembaruan Multi Smart Click: 0.8.0)  
Laporan pengguna: clicker masih terbuka, toolbar tiba-tiba hilang, macro berhenti, dan **game target** force close setelah macro dimulai.

## Kesimpulan singkat

Ada beberapa jalur berisiko di sisi clicker yang sudah diperbaiki pada 0.7.2. Namun, force close pada **game target** tidak dapat dipastikan hanya dari kode clicker. Penyebab game crash bisa berasal dari bug game/engine, proteksi game, atau cara game menerima input sintetis. Rilis ini tidak menambahkan bypass anti-cheat atau penyembunyian layanan accessibility.

## Pembaruan 0.8.0 — Multi Smart Click di Android 12

| Area | Temuan / risiko | Penanganan 0.8.0 |
|---|---|---|
| API screenshot | `takeScreenshot` baru ada di API 30 | Semua panggilan diisolasi di `Capture30` ber-`@RequiresApi(R)`; di bawah Android 11 fitur menolak berjalan, bukan crash |
| Kapabilitas layanan | Tanpa `canTakeScreenshot` sistem melempar `SecurityException` | Ditambahkan di XML layanan; kemampuan diperiksa sebelum mulai dengan pesan bila belum aktif |
| Aktivasi izin | Android tidak menerapkan kapabilitas baru pada layanan yang sudah aktif | Instruksi matikan/nyalakan ulang layanan; kegagalan ditampilkan sebagai pesan, bukan force close |
| Batas laju sistem | Screenshot dibatasi ~333 ms | Interval minimum 400 ms; error `INTERVAL_TIME_SHORT` ditangani sebagai tunggu, bukan kegagalan |
| Layar aman | `FLAG_SECURE` menolak screenshot | Error dipetakan ke pesan jelas dan macro berhenti; tidak ada bypass |
| Sentuhan tidak terpercaya | Overlay pekat dapat memblokir gesture Android 12 | Marker di-INVISIBLE saat baca layar dan saat berjalan; toolbar tetap pass-through sesuai aturan 0.7.2 |
| Titik tertutup toolbar | Warna toolbar bisa terbaca sebagai warna game | Ditolak sebelum mulai; berhenti setelah 6 kali patch tidak terbaca |
| Dua mode bersamaan | Dua penyuntik gesture bisa bertumpuk | `requestExclusiveRun()` menegakkan satu mode aktif |
| Memori | Bitmap layar penuh menambah tekanan memori | `HardwareBuffer.close()` + `Bitmap.recycle()` di `finally`; berhenti pada sinyal memori kritis |

Mode Macro Biasa tidak membaca layar dan tidak terpengaruh perubahan ini. Rincian uji dan batasan ada di [AUDIT_0.9.4.md](./AUDIT_0.9.4.md).

## Pembaruan 0.7.4 — RAM dan crash tertunda

Laporan terbaru menyebut game force close setelah sekitar satu jam atau beberapa jam. Audit tidak menemukan daftar gesture yang terus disimpan per klik, tetapi jumlah marker menentukan jumlah window grafis. Ukuran APK bukan ukuran RAM. Versi 0.7.4 menambahkan pengukuran PSS/heap/jumlah window, sampel 60 detik selama macro, log latar berbatas, penggunaan ulang Path, dan penghentian pencegahan pada sinyal memori kritis Android lawas. Menyembunyikan marker tidak menjamin penghematan RAM.

Belum ada angka RAM dari HP pengguna dan belum ada uji perangkat nyata. Penyebab crash game belum dapat dipastikan; lihat [MEMORY_AUDIT.md](./MEMORY_AUDIT.md). Sejak Android 14, callback level tekanan memori lama tidak dikirim, sehingga perlindungan ini tidak universal.

## Temuan dan perbaikan 0.7.2 yang tetap ada

| Area | Temuan | Perbaikan 0.7.2 |
|---|---|---|
| Toolbar hilang | Toolbar bisa disembunyikan/dilepas sistem/game tanpa menghentikan macro secara aman | `ToolbarLayout` memantau visibility/detach; macro berhenti aman dan log mencatat `toolbar_visibility_changed` / `toolbar_detached` |
| Status profil palsu | Tile menganggap profil siap walau toolbar tidak terpasang | `hasLoadedProfile()` memeriksa toolbar terpasang |
| Klik mengenai menu | Titik aksi bisa berada di bawah toolbar | Start/gesture ditolak jika titik tertutup toolbar |
| Gesture framework | `dispatchGesture`/stroke bisa melempar exception atau callback tidak kembali | Validasi koordinat, clamp ke layar, try/catch dispatch, watchdog callback |
| Android 12 touch overlay | Marker pass-through yang terlalu pekat dapat terkena pembatasan sentuhan Android 12 | Opacity window marker saat berjalan diturunkan ke 0,7; opacity visual marker diturunkan |
| Guard aplikasi | Dialog izin/installer/chooser/keyboard bisa dianggap pergantian aplikasi | Classifier mengabaikan dialog sistem transient Android 12 |
| Perubahan konfigurasi | Semua perubahan konfigurasi menghentikan macro | Macro berhenti hanya jika frame layar berubah/tidak terbaca |
| Crash clicker | Crash proses tidak tercatat lokal | `MacroApplication` mencatat uncaught exception sebelum meneruskan ke handler default |
| Intent pengaturan/tile | Pembukaan pengaturan/aktivitas bisa gagal | Dilindungi runCatching |

## Fitur Android 12 yang relevan

- Android 12 memperketat overlay dan sentuhan yang melewati window aplikasi lain. Ini relevan untuk marker/toolbar clicker.
- Aplikasi target dapat meminta sistem menyembunyikan overlay non-sistem pada window tertentu. Jika game melakukan ini, toolbar dapat hilang; clicker sekarang berhenti aman dan mencatat kejadian.
- PendingIntent harus memiliki flag mutability. Aplikasi sudah memakai `FLAG_IMMUTABLE` untuk tile pada API 34+, dan jalur lama dibatasi di bawah API 34.
- Pembatasan foreground service Android 12 tidak menjadi jalur utama di aplikasi ini karena macro berjalan melalui AccessibilityService yang diikat sistem, bukan foreground service yang dimulai dari background.

## Yang tidak diklaim

- Tidak menjamin semua game berhenti force close.
- Tidak menjamin toolbar selalu terlihat jika game/sistem secara sah menyembunyikan overlay.
- Tidak ada jaminan 100% bebas bug di semua OEM/ROM.
- Belum diuji pada perangkat Android 12 nyata di lingkungan kerja ini.

## Verifikasi lokal

- `testDebugUnitTest`: 88 test, 0 gagal.
- `testReleaseUnitTest`: 88 test, 0 gagal.
- `lintDebug`: No issues found.
- `lintRelease`: No issues found.
- `assembleRelease`: berhasil.
- `zipalign -c -p 4`: OK.
- `apksigner verify`: valid.
- Package: `com.richclickermacro`.
- Version: `0.7.4` / versionCode `15`.
- Certificate SHA-256 sama dengan 0.7.0/0.7.1: `087d9bacaf1c8269a456ba20abfe83a8ac36a850a94170b06d132ff2deab6aac`.

## Uji perangkat yang disarankan

1. Pasang 0.7.4 di atas 0.7.2.
2. Buat profil minimal: 1 titik tap, delay 1–2 detik, loop limit 3, tanpa long press/swipe.
3. Jalankan di game. Jika stabil, tambah kompleksitas perlahan.
4. Pastikan titik tidak tertutup toolbar.
5. Jika toolbar hilang, buka clicker dan tampilkan editor lagi, atau gunakan tile Quick Settings.
6. Export **Log Diagnostik** setelah kejadian.
7. Jika game tetap crash, ambil logcat perangkat saat crash.

## Contoh pengambilan logcat game

Ganti `PACKAGE_GAME` dengan package game, atau gunakan logcat penuh jika package tidak diketahui.

```bash
adb logcat -c
adb logcat -b all -v threadtime > logcat-game-crash.txt
# Jalankan game + macro sampai crash, lalu hentikan dengan Ctrl+C.
```

Jangan hanya memfilter PID game: alasan kill dari sistem dapat berada di luar PID tersebut, dan PID berubah setelah restart. Jika package diketahui, periksa juga riwayat keluar pada Android 11+:

```bash
adb logcat -c
adb shell dumpsys activity exit-info PACKAGE_GAME
```

Informasi yang dibutuhkan untuk diagnosis lanjutan:

- Merek/tipe HP dan versi Android/ROM.
- Nama game dan versi game.
- Apakah crash terjadi pada tap, long press, atau swipe.
- Apakah crash tetap terjadi dengan profil minimal 1 titik tap.
- Log diagnostik clicker dan logcat game saat crash.
