# Catatan rilis 0.9.4 — com.richclickermacro

Rilis lokal ini menindaklanjuti laporan **toolbar clicker tiba-tiba hilang** dan **game target force close setelah macro dimulai di Android 12**. Ini **bukan fitur anti-deteksi, anti-ban, atau bypass proteksi game**.

## Tambahan 0.9.4 — dua bug kecepatan diperbaiki

**Bug "pilih kecepatan cuma kedip".** `persist()` memakai nilai kembali dari
`updatePoints()`, yang hanya menyimpan daftar titik dan mengembalikan profil lama. Semua
perubahan tingkat profil langsung tertimpa. Ini juga membuat **jumlah putaran**, **batas diam**,
dan **angka Custom** tidak pernah tersimpan — penjelasan kenapa dulu selalu berhenti setelah
2 menit walau sudah diatur. Diperbaiki dengan `updateProfile()` yang menyimpan seluruh profil.

**Bug kecepatan per titik.** Angka kecepatan tersimpan global tapi jedanya dijalankan per titik,
jadi 3 titik mengklik ±3× lebih cepat dari yang dipilih. Sekarang ada satu gerbang jeda bersama:
jeda antar klik sama dengan angka yang dipilih, berapa pun jumlah titiknya.

Pemilihan kecepatan disederhanakan jadi satu baris yang membuka daftar pilihan biasa.
Rincian: [AUDIT_0.9.4.md](./AUDIT_0.9.4.md).

## Tambahan 0.9.3 — fitur smart sesuai daftar pengguna

Pengguna menetapkan sendiri daftar fiturnya; rilis ini menerapkannya apa adanya.

- **Loop selalu berurutan** titik 1 → terakhir, **tanpa batas** sebagai bawaan, atau isi jumlah
  putaran. Pilihan "Bebas" dihapus karena penghitung putaran hanya berarti kalau urutannya jelas.
- **Kecepatan jadi 3 pilihan dengan keterangan**: Rekomendasi (bawaan, periksa 2×),
  Cepat (langsung klik), Custom (isi angka sendiri). Pilihan disimpan di profil, jadi labelnya
  tidak pernah bohong.
- **Bawaan berubah** ke Rekomendasi. Profil lama tidak diubah angkanya; labelnya dibaca dari
  angka yang tersimpan.
- **Tahan tekan lama** jadi ON/OFF, plus kolom lama menahan saat ON.
- **Berhenti kalau diam** naik ke menu utama; isi 0 untuk mematikan, menjawab keluhan
  "berhenti sendiri padahal loop tanpa batas".
- Schema 3; file schema 1–2 tetap terimpor.

Dua bug nyata ditemukan dan diperbaiki: bawaan tidak cocok dengan label Rekomendasi, dan label
kecepatan bisa salah setelah angka diubah manual. Mode biasa tidak disentuh.
Rincian: [AUDIT_0.9.3.md](./AUDIT_0.9.3.md).

## Tambahan 0.9.2 — Multi Smart Click disederhanakan

Pengguna melaporkan fitur masih membingungkan walau label sudah diganti di 0.9.1. Kali ini
jumlah pilihan yang dikurangi, bukan katanya.

- **"Lokasi lain" / `Klik≠Awasi` dihapus total.** Satu titik selalu mengklik di tempat ia
  memantau. File export lama tetap bisa diimpor; kolom lama diabaikan.
- **Mode lanjutan** (bawaan MATI) menyembunyikan pengaturan teknis: kondisi warna, jenis klik,
  kepekaan, kecepatan, dan angka teknis. Disimpan per aplikasi, tidak ikut ter-export.
- Tampilan bawaan cukup **2 langkah**: `＋ Tambah Titik` → ketuk layar → `▶ MULAI`.
- Hapus titik kini pakai konfirmasi. Panduan ditulis ulang jadi 2 langkah.
- `Ulang N×`, urutan 1→terakhir, dan jumlah putaran **tetap ada** di tampilan sederhana.

Mode biasa tidak disentuh. Perbaikan koordinat 0.9.1 tetap berlaku.
Rincian: [AUDIT_0.9.3.md](./AUDIT_0.9.3.md).

## Tambahan 0.9.1 — akurasi klik & pengaturan berbahasa jelas

**Tiga bug koordinat yang membuat klik meleset sudah diperbaiki:**
1. Frame layar memakai `currentWindowMetrics` yang bisa mengecualikan insets, sedangkan screenshot selalu seluruh layar → diganti `maximumWindowMetrics`.
2. Marker diposisikan tanpa memperhitungkan origin layar, sementara picker dan gesture memperhitungkannya → semua memakai satu pemetaan kanonik di `SmartGeometry`.
3. Sampler menghitung piksel dengan `size - 1` sedangkan gesture dengan `size`, selisih sampai 2 piksel di tepi → disamakan.

**Pengaman baru:** bila bentuk screenshot tidak cocok dengan layar (split-screen, zoom layar, mode satu tangan), run dihentikan dengan pesan alih-alih mengklik sembarangan.

**Tombol membingungkan diganti.** Panel 13 tombol singkat dihapus; ⚙ kini membuka **Atur Titik Ini**, **Kecepatan & Urutan**, dan **Tes Klik Titik Ini**. Setiap baris punya judul jelas, nilai saat ini, dan satu kalimat penjelasan. `Klik=Awasi` menjadi "Lokasi yang diklik: Sama / Lokasi lain", `Cocok/Berubah` menjadi "Kapan titik ini diklik?", dan seterusnya.

**Tes Klik Titik Ini** mengirim satu klik tanpa syarat warna supaya posisi bisa diperiksa sebelum sesi panjang.

Mode biasa tidak diubah. Rincian: [AUDIT_0.9.3.md](./AUDIT_0.9.3.md).

## Tambahan 0.9.0 — home rapi, smart click lebih cepat, audit ulang

**Tampilan home dirapikan:**
- Dua tab mode (**MACRO BIASA** / **MULTI SMART CLICK**). Hanya kartu mode terpilih yang tampil, bukan sembilan kartu menumpuk.
- Header diringkas: logo 96 dp → 44 dp berdampingan dengan judul, sehingga tombol layanan langsung terlihat.
- Alat diagnostik, cek RAM, dan panduan dipindah ke bagian lipat **Alat & Panduan**.
- Nomor kartu (1–7) dihapus karena urutannya sudah tidak berlaku setelah dipisah per tab.
- Tab terpilih dan status bagian lipat bertahan setelah rotasi layar.

**Smart click dipercepat:**
- Konfirmasi bawaan 2 pembacaan → **1 pembacaan**. Klik pertama datang sekitar 2× lebih cepat.
- Pendinginan bawaan 1200 ms → **300 ms**, jadi tidak lagi menambah tunggu di atas batas sistem.
- Setelah klik, layar langsung dibaca lagi; sebelumnya menunggu satu interval penuh (~450 ms) tanpa alasan.
- Preset **⚡ Cepat / 🛡 Stabil** di toolbar ⚙, tanpa kolom milidetik.
- **Profil lama tidak diubah otomatis.** Buka ⚙ lalu pilih ⚡ Cepat bila ingin profil lama ikut cepat.
- Batas platform tetap: Android membatasi screenshot ~333 ms sekali (≈3 pemeriksaan/detik). Smart click tidak bisa lebih cepat dari itu untuk klik berbasis kondisi; Macro Biasa tetap lebih cepat untuk klik mentah.

**Audit ulang seluruh fitur:** semua kelompok test dijalankan ulang (smart, home, UI/import-export, data/safety/diagnostics, overlay/accessibility) pada varian debug, plus smart dan home pada varian rilis. Lint release: **No issues found**. Tidak ditemukan bug baru pada mode biasa.

## Tambahan 0.8.1 — smart click dipermudah + ulang klik & loop

Menindaklanjuti laporan bahwa Multi Smart Click terlalu ribet dan belum punya ulang-per-titik serta loop berurutan.

**Alur dipermudah:**
- Tombol **＋ Ketuk Titik di Layar** menggantikan rangkaian "tambah titik → geser marker → cari tombol ambil warna". Satu ketukan menempatkan titik **sekaligus** membaca warnanya.
- Tombol utama memakai kata, bukan simbol: **▶ MULAI**, **■ STOP**.
- Baris petunjuk selalu memberi tahu langkah berikutnya.
- Pengaturan lanjutan dipindah ke **⚙**, dengan panduan **? Cara Pakai**.
- Posisi klik terpisah kini juga dipilih dengan mengetuk layar, bukan offset otomatis.

**Fitur baru yang menyamai macro biasa:**
- **Ulang N×** per titik (1–1000). Kondisi diperiksa sekali, lalu klik dikirim berurutan dengan jeda teknis 60 ms. Tidak ada kolom jeda per klik.
- **Urutan 1→akhir**: titik dijalankan berurutan, bukan sekadar round-robin. Setiap langkah tetap menunggu kondisi layarnya sendiri.
- **Putaran**: batas berapa kali urutan penuh diulang (0 = tanpa batas). Hanya berlaku pada mode berurutan.
- Klik yang ditolak sistem membatalkan sisa ulangan dan mengulang titik yang sama, tidak melompat diam-diam.

**Perbaikan bug yang ditemukan lewat test:**
- `repeatTotal` terbaca 0 karena state burst dibersihkan sebelum nilainya dikirim ke UI.
- Indeks ulangan meleset satu (ulangan kedua dilaporkan sebagai pertama).
- Lapisan penanda titik belum mengimplementasikan `performClick`; sudah diperbaiki agar layanan aksesibilitas bisa memicunya.

**Penyimpanan:** schema naik ke 2. File ekspor v0.8.0 (schema 1) **tetap bisa diimpor**; field baru memakai nilai bawaan sehingga perilaku profil lama tidak berubah.

## Tambahan 0.8.0 — Multi Smart Click (mode kedua)

Mode baru yang klik berdasarkan **kondisi warna layar**, tanpa jeda per klik. Fitur lama tidak diubah perilakunya.

- **Terpisah penuh.** Model, penyimpanan (`smart_profiles`), overlay, mesin keputusan, dan format export sendiri. File profil klasik ditolak bila diimpor ke mode smart, dan sebaliknya.
- **Hanya satu mode berjalan.** `requestExclusiveRun()` menghentikan mode lain sebelum mulai, sehingga tidak ada dua penyuntik gesture bersamaan.
- **Butuh Android 11+** (`takeScreenshot` aksesibilitas). Di Android 8–10 fitur menolak berjalan dengan pesan jelas; Macro Biasa tetap berfungsi.
- **Izin baru.** `canTakeScreenshot=true` ditambahkan. Setelah update, layanan aksesibilitas **harus dimatikan lalu dinyalakan lagi** agar Android menerapkan kapabilitas baru. `canRetrieveWindowContent` tetap `false`.
- **Privasi.** Hanya warna di titik yang dipilih pengguna yang dibaca. Gambar tidak disimpan, tidak dikirim, dan tidak masuk log.
- **Batas platform.** Android membatasi screenshot aksesibilitas ~333 ms sekali; interval minimum aplikasi 400 ms. Titik maksimum 10, cooldown minimum 300 ms, konfirmasi 1–5 sampel.
- **Pengaman.** Berhenti bila layar/aplikasi berganti, toolbar hilang, gesture ditolak, memori kritis, atau patch tidak terbaca 6 kali berturut-turut. Titik di bawah toolbar ditolak sebelum mulai.
- **Marker disembunyikan saat membaca layar** agar warna toolbar/marker tidak ikut terbaca sebagai warna game.
- **Biaya memori.** Satu screenshot 1080p sekitar 9–10 MiB sementara; `HardwareBuffer` ditutup dan `Bitmap` di-recycle di blok `finally`. Angka nyata pada perangkat belum diukur.
- **Window `FLAG_SECURE` tidak bisa dibaca** dan ditolak oleh sistem sesuai desain; tidak ada upaya melewati proteksi.
- Belum diuji pada HP nyata. Akurasi warna, biaya RAM sesi panjang, dan perilaku ROM Samsung perlu dicek pengguna. Baca [SMART_CLICK.md](./SMART_CLICK.md) dan [AUDIT_0.9.0.md](./AUDIT_0.9.0.md).

## Tambahan 0.7.4 — toolbar ringkas dan audit ulang

- Lebar toolbar maksimum 220 dp, sebelumnya 260 dp; padding, tinggi baris, dan ukuran tombol dikurangi. Pengaturan lanjutan dibuka lewat **⚙ Atur** dan ditutup otomatis saat macro mulai.
- STOP tetap di dalam menu; STOP header tetap terlihat saat toolbar diciutkan dan macro berjalan.
- Perbaikan audit: swipe yang melintasi area toolbar ditolak meskipun kedua endpoint di luar toolbar; operasi buat/simpan/hapus profil menampilkan dialog gagal alih-alih crash jika penyimpanan bermasalah.
- Uji regresi toolbar: lebar ringkas, buka/tutup pengaturan, STOP saat collapsed, penutupan panel saat start, dan penutupan editor.
- Belum diuji pada HP nyata; kenyamanan ukuran akhir tetap perlu dicek pengguna.

## Tambahan 0.7.3 — audit RAM

- Tombol **Cek RAM Clicker** dan sampel memori setiap 60 detik selama macro berjalan. Angka mengukur proses clicker, bukan game; tidak ada klaim ambang RAM aman universal.
- Log diagnostik: satu worker latar, antrean maksimal 64 kejadian, dua file sekitar 256 KiB, dan pencatatan crash sinkron. Export menunggu antrean sebentar agar kejadian terbaru ikut tersimpan.
- Path gesture digunakan ulang setelah verifikasi bahwa StrokeDescription menyalin Path; marker hanya invalidate saat status berubah.
- Peringatan 20 titik. Menyembunyikan marker tidak diklaim membebaskan buffer grafis karena window tetap dikelola.
- Android lawas: tekanan kritis menghentikan macro sebagai pencegahan. Level background/LRU biasa tidak menghentikan macro. Android 14+ tidak mengirim level tekanan lama, sehingga fitur ini bukan detektor universal.
- **Belum ada pengukuran RAM pada HP pengguna atau uji game berjam-jam.** Crash tertunda dapat berasal dari game, tekanan memori, atau masalah lain; logcat dan sampel memori diperlukan. Baca [MEMORY_AUDIT.md](./MEMORY_AUDIT.md).

## Perbaikan utama 0.7.2

- **Toolbar dipantau saat hilang.** Jika window toolbar disembunyikan sistem/game atau dilepas dari WindowManager saat macro berjalan, macro dihentikan aman dan kejadian dicatat ke log diagnostik (`toolbar_visibility_changed`, `toolbar_detached`).
- **Toolbar yang hilang tidak membuat status palsu.** Tile Quick Settings tidak lagi menganggap profil siap jika toolbar tidak terpasang; tombol utama dapat menampilkan ulang editor.
- **Titik tertutup toolbar ditolak.** Sebelum start dan sebelum setiap gesture, aplikasi memeriksa apakah titik aksi berada di bawah toolbar. Jika tertutup, macro berhenti/menolak start agar klik tidak mengenai menu sendiri.
- **Gesture lebih tahan error Android 12.** Koordinat divalidasi dan di-clamp ke pixel layar yang valid; `dispatchGesture`/pembuatan stroke dilindungi dari exception framework; watchdog callback mencegah macro menggantung jika sistem tidak memanggil callback.
- **Kompatibilitas sentuhan Android 12.** Saat macro berjalan, marker menjadi pass-through dan opacity window marker diturunkan ke 0,7 agar tidak melewati ambang batas sentuhan tidak terpercaya Android 12. Opacity visual marker saat berjalan juga diturunkan.
- **Dialog sistem bukan pergantian aplikasi.** Dialog izin, installer, chooser, credential/biometric, keyboard, dan SystemUI Android 12 tidak lagi memicu guard "aplikasi tujuan berganti" secara keliru.
- **Perubahan konfigurasi tidak selalu menghentikan macro.** Macro hanya berhenti jika frame layar benar-benar berubah atau tidak dapat dibaca; perubahan konfigurasi lain tidak langsung menghentikan macro.
- **Crash proses clicker dicatat.** `MacroApplication` memasang pencatat uncaught exception lokal sebelum meneruskan ke handler default Android. Log mencatat thread, jenis error, pesan, dan potongan stack trace. Crash tidak disembunyikan.
- Pembukaan pengaturan aksesibilitas dan tile Quick Settings dilindungi dari kegagalan intent.

## Perbaikan yang tetap ada dari 0.7.1

- Import/Export JSON tidak force close jika pemilih file tidak tersedia; ada fallback `ACTION_GET_CONTENT` dan alternatif Download Android 10+.
- Filter JSON diperluas, BOM UTF-8 dibersihkan, file kosong/rusak/terlalu besar ditangani dengan dialog kesalahan.
- **Export ke Download** dan **Import dari Download** tersedia sebagai alternatif Android 10+.
- Package tetap `com.richclickermacro`, signing key sama dengan 0.7.0/0.7.1.

## Perubahan yang tetap ada dari 0.7.0

- Tombol STOP berada di dalam menu/toolbar: tombol STOP merah di baris aksi utama dan STOP ringkas di header saat macro berjalan.
- Validasi koordinat marker terhadap area layar absolut sebelum gesture.
- Perhitungan layar API 30+ mempertahankan `left/top` dari `WindowMetrics.bounds`.
- Marker memakai `FLAG_LAYOUT_IN_SCREEN` dan mode cutout `SHORT_EDGES` pada Android 9+.
- Macro berhenti ketika ukuran, posisi, atau rotasi layar berubah.
- Opsi profil **Stop saat aplikasi tujuan berganti** aktif secara default pada profil baru/impor lama.
- Log diagnostik lokal terbatas. Tidak merekam isi layar, screenshot, atau koordinat gesture.
- Schema profil v3 dengan `stopOnAppChange`; format v1/v2 tetap dapat diimpor.

## Artefak

- APK final di luar folder proyek: `/home/user/Rich-Clicker-Macro-v0.7.4-com.richclickermacro-release.apk`
- Checksum: `/home/user/Rich-Clicker-Macro-v0.7.4-release.apk.sha256`
- Keystore: `signing/richclickermacro-release.jks`
- Kredensial signing: `signing/KEY-PASSWORD.txt` dan `signing/keystore.properties`

Simpan keystore dan password secara privat. Jika key hilang, update aplikasi dengan package yang sama tidak bisa ditandatangani ulang secara kompatibel.

## Cara menguji di perangkat Android 12

1. Pasang APK 0.7.4 di atas 0.7.3 dengan package/key yang sama.
2. Aktifkan Accessibility Service, buka profil, tampilkan editor.
3. Uji tap sederhana dulu di game. Jika stabil, uji long press, lalu swipe.
4. Pastikan tidak ada titik marker yang tertutup toolbar. Jika ditolak, pindahkan toolbar.
5. Saat macro berjalan, coba tarik notification shade atau buka dialog izin sistem; macro tidak boleh berhenti hanya karena dialog sistem.
6. Putar layar atau ubah ukuran window; macro harus berhenti dan meminta pemeriksaan marker.
7. Jika toolbar hilang, buka aplikasi clicker dan tekan **Tampilkan Editor di Atas Aplikasi** lagi, atau gunakan tile Quick Settings.
8. Export **Log Diagnostik** setelah kejadian. Cari kejadian `toolbar_visibility_changed`, `toolbar_detached`, `gesture_dispatch_exception`, `gesture_callback_timeout`, `gesture_cancelled`, `app_window_changed`, atau `uncaught_exception`.

## Batasan penting tentang force close game

- Force close **game target** tidak dapat dipastikan atau diperbaiki hanya dari kode clicker. Penyebabnya bisa bug game, engine game, proteksi/integritas game, atau interaksi input yang tidak ditangani game.
- Rilis ini memperbaiki jalur berisiko di sisi clicker dan menambahkan diagnostik, tetapi **tidak menjamin game tertentu berhenti crash**.
- Tidak ada fitur bypass anti-cheat, penyembunyian accessibility, atau manipulasi proteksi game.
- Jika game hanya crash saat macro berjalan, uji profil minimal: 1 titik tap, delay 1–2 detik, loop limit 3, tanpa long press/swipe. Jika profil minimal tetap membuat game crash, kemungkinan besar masalah ada di sisi game/proteksi game.
- Untuk diagnosis game, diperlukan log perangkat/logcat pada waktu crash, merek/tipe HP, versi Android/ROM, nama game, dan versi game.

## Verifikasi yang dijalankan

- `testDebugUnitTest`: 88 test lulus.
- `testReleaseUnitTest`: 88 test lulus.
- `lintDebug`: tidak ada masalah.
- `lintRelease`: tidak ada masalah.
- `assembleRelease`: berhasil.
- `zipalign -c -p 4`: OK.
- `apksigner verify`: valid, package `com.richclickermacro`, versionName `0.7.4`, versionCode `15`, certificate SHA-256 sama dengan 0.7.0/0.7.1.
- Belum diuji pada emulator/perangkat Android nyata di lingkungan kerja ini.
