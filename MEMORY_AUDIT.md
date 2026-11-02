# Audit RAM — Rich Clicker Macro 0.7.4

Tanggal: 2026-10-30

## Pertanyaan

Apakah clicker memakai RAM besar sehingga game force close setelah sekitar satu jam atau beberapa jam?

**Jawaban singkat:** belum ada pengukuran pada HP pengguna, jadi angka RAM aktual dan penyebab crash belum dapat dipastikan. Dari audit kode, clicker tidak menyimpan riwayat gesture atau menumbuhkan daftar titik pada setiap klik. Namun, banyaknya marker menambah window grafis, dan game tetap dapat mengalami pertumbuhan memori, crash native, atau masalah lain yang tidak terlihat dari proses clicker. Crash tertunda bukan bukti bahwa clicker penyebabnya.

Ukuran APK sekitar 869 KiB adalah ukuran berkas terinstal, **bukan** ukuran RAM saat berjalan. Batas heap Java juga bukan jumlah RAM yang sedang digunakan.

## Temuan kode

| Area | Temuan | Penanganan 0.7.4 |
|---|---|---|
| Gesture berulang | Path baru dibuat untuk tiap segmen; objek gesture/callback tetap diperlukan oleh framework | Satu Path digunakan ulang. Konstruktor `StrokeDescription` menyalin Path; uji regresi memastikan perubahan Path berikutnya tidak mengubah stroke sebelumnya |
| Marker | Satu window per titik, satu tambahan per endpoint swipe,ditambah satu toolbar. Menyembunyikan marker tidak menjamin buffer grafis dilepas | Jumlah window dicatat; peringatan pada 20 titik; teks tidak lagi menjanjikan penghematan RAM hanya dengan menyembunyikan marker |
| Redraw | Setter status marker memanggil invalidate meskipun nilainya tidak berubah | Invalidate hanya ketika nilai berubah, mengurangi redraw berulang pada sesi panjang |
| Log | Penulisan sinkron dapat membebani thread utama; rotasi lama dapat gagal membatasi file jika rename gagal | Satu worker latar, antrean maksimal 64 kejadian, dua file sekitar 256 KiB masing-masing, fallback truncate bila rotasi gagal. Kejadian yang dibuang dicatat bila penulisan berikutnya berhasil |
| Pengukuran | Tidak ada angka memori proses yang bisa dibandingkan dari waktu ke waktu | Tombol **Cek RAM Clicker**; PSS, Java terpakai/batas heap, native heap, jumlah window. Sampel otomatis setiap 60 detik hanya selama macro, termasuk saat hitung mundur |
| Tekanan RAM | Tidak ada respons terhadap sinyal memori Android lawas | `RUNNING_CRITICAL`/`onLowMemory` menghentikan macro sebagai pencegahan. Level background/LRU biasa tidak menghentikan macro. Bukan jaminan game tidak crash |
| Crash clicker | Log async bisa belum tersimpan saat proses mati | Jalur pencatatan fatal sinkron sebelum handler crash default Android |
| Profil JSON | Serialisasi/parse ulang terjadi saat edit/impor/ekspor, bukan pada setiap gesture | Tidak diklaim sebagai kebocoran RAM per klik. Cache lintas komponen tidak ditambahkan karena berisiko menghasilkan data basi; pola ini tetap perlu dipantau bila profil sangat besar |

Tidak ada bitmap besar, WebView, atau library native milik aplikasi dalam kode ini. Ini bukan bukti bahwa RAM aktual kecil: framework Android, grafis, dan proses sistem tetap memakai memori.

## Batas pengukuran

- PSS adalah estimasi proporsional memori proses, bukan seluruh RAM HP dan bukan RAM game. Sebagian memori GPU/kompositor dapat tidak tercakup.
- Java terpakai dan native heap adalah bagian dari gambaran memori, bukan angka yang boleh dijumlahkan begitu saja dengan PSS.
- Angka dapat naik/turun karena garbage collection dan aktivitas UI. Satu kenaikan belum membuktikan kebocoran; cari pertumbuhan berkelanjutan pada kondisi sebanding.
- Android dapat mengakhiri proses tanpa exception Java. Log crash clicker tidak merekam crash proses game.
- Sejak Android 14/API 34, level tekanan memori lama tidak lagi dikirim ke aplikasi. Respons tekanan memori bersifat best-effort untuk Android lawas, bukan detektor universal.
- Tidak ada ambang “RAM aman” universal. Aplikasi tidak melabeli 80/150 MiB sebagai batas aman perangkat.

## Cara menguji tanpa komputer

1. Pasang 0.7.4 di atas 0.7.2; package dan signing key sama.
2. Buka clicker, tekan **Cek RAM Clicker** sebelum macro. Catat PSS dan jumlah window.
3. Gunakan profil pembanding: 1 titik Tap, jeda 1–2 detik. Jangan ubah jumlah titik selama pengujian.
4. Jalankan macro. Aplikasi mencatat `macro_memory_start`, lalu `memory_sample` setiap 60 detik, lalu `macro_memory_stop`.
5. Setelah 15–30 menit, buka clicker dan tekan **Cek RAM Clicker** lagi. Jangan menutup editor jika ingin membandingkan jumlah window yang sama.
6. Ulangi sampai durasi yang biasanya memicu crash. Segera setelah kejadian, buka clicker dan **Export Log Diagnostik**.
7. Bandingkan PSS pada jumlah titik dan kondisi layar yang sama. Jika memungkinkan, uji game tanpa macro pada durasi setara sebagai kontrol.

Jika game crash tetapi PSS clicker relatif stabil, itu tidak membebaskan clicker dari semua kemungkinan masalah, tetapi tidak mendukung tuduhan kebocoran RAM clicker. Penyebab game masih perlu logcat. Jika PSS clicker terus naik jauh, kirim log beserta jumlah titik dan durasi untuk audit lanjutan.

## Pengukuran lebih kuat dengan ADB

Ganti `PACKAGE_GAME` dengan package game. Jalankan sebelum crash, lalu ambil sampel pada awal, 15 menit, 30 menit, dan seterusnya.

```bash
adb shell dumpsys meminfo com.richclickermacro
adb shell dumpsys meminfo PACKAGE_GAME
adb shell dumpsys activity exit-info PACKAGE_GAME
```

`exit-info` tersedia pada Android 11+ dan hanya menyediakan riwayat yang masih disimpan sistem. Reason `LOW_MEMORY` menunjukkan keterkaitan dengan tekanan memori, tetapi tetap perlu konteks proses mana yang tumbuh.

Rekam log seluruh sistem, bukan hanya PID game, agar alasan kill dari sistem ikut tertangkap:

```bash
adb logcat -c
adb logcat -b all -v threadtime > logcat-game-crash.txt
# Jalankan pengujian sampai crash, lalu hentikan dengan Ctrl+C.
```

Jangan memfilter log hanya berdasarkan PID lama jika game restart: PID berubah. Jangan menghapus log sebelum mengekspor diagnostik. Log dapat berisi informasi perangkat/aplikasi lain; periksa sebelum membagikan.

## Hasil verifikasi lokal

- 88 test debug lulus; 88 test release lulus; 0 gagal/error/skip.
- `lintDebug` dan `lintRelease`: No issues found.
- `assembleRelease` berhasil.
- APK: `com.richclickermacro`, versionCode 15, versionName 0.7.4.
- zipalign OK; tanda tangan APK v2 valid; sertifikat sama dengan 0.7.0–0.7.2.
- SHA-256 APK: `e4ed3f1e03f7a6a371fd9bab29d7751d777f06e09953499a3dbffc517fd22a7d`.
- **Belum diuji pada HP/emulator Android nyata dan belum ada uji ketahanan game selama berjam-jam di lingkungan ini.**

## Rujukan Android

- [Manajemen memori dan PSS](https://developer.android.com/topic/performance/memory-overview)
- [ComponentCallbacks2 dan batas callback Android 14+](https://developer.android.com/reference/android/content/ComponentCallbacks2)
- [Debug: pembacaan memori proses](https://developer.android.com/reference/android/os/Debug#getPss())
