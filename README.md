<div align="center">
  <img src="./1000641516.png" alt="Rich Clicker Macro Logo" width="180" />
  <h1>Rich Clicker Macro</h1>
  <p>Auto clicker dan editor macro multi-aksi untuk Android.</p>

  [![Version](https://img.shields.io/badge/version-0.9.15-4f46e5)](https://github.com/ramax100/Rich_Clicker_Macro/releases/latest)
  [![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android)](#persyaratan)
  [![License](https://img.shields.io/badge/license-MIT-blue)](./LICENSE)
  [![Free](https://img.shields.io/badge/price-100%25%20gratis-brightgreen)](#gratis-dan-bebas-dimodifikasi)
</div>

## Daftar isi

- [Tentang aplikasi](#tentang-aplikasi)
- [Dua mode, terpisah penuh](#dua-mode-terpisah-penuh)
- [Download](#download)
- [Persyaratan](#persyaratan)
- [Instalasi](#instalasi)
- [Cara penggunaan](#cara-penggunaan)
- [Jalur 2](#jalur-2)
- [Semua fitur](#semua-fitur)
- [Kontrol overlay](#kontrol-overlay)
- [Quick Settings Tile](#quick-settings-tile)
- [Privasi dan izin](#privasi-dan-izin)
- [Batasan yang perlu diketahui](#batasan-yang-perlu-diketahui)
- [Build dari source](#build-dari-source)
- [Modifikasi dan kontribusi](#modifikasi-dan-kontribusi)
- [Komunitas](#komunitas)
- [Lisensi](#lisensi)

## Tentang aplikasi

**Rich Clicker Macro** adalah aplikasi Android untuk menjalankan rangkaian tap, long press,
dan swipe di atas aplikasi lain. Setiap rangkaian disimpan sebagai profil, bisa diulang,
diberi hitungan mundur, dan dikendalikan dari overlay atau Quick Settings.

Aplikasi bekerja melalui Android Accessibility Service. Gesture hanya dikirim setelah
pengguna mengaktifkan layanan secara manual dan menekan tombol mulai. Aplikasi **tidak
membaca struktur isi layar** (`canRetrieveWindowContent=false`) dan tidak berjalan otomatis
saat perangkat dinyalakan.

Mode **Macro Biasa** sama sekali tidak melihat layar. Mode opsional **Multi Smart Click**
mengambil gambar layar untuk membaca warna di titik yang dipilih pengguna; gambar hanya
diperiksa di memori, tidak disimpan ke berkas dan tidak dikirim ke mana pun. Metadata
pergantian jendela dipakai untuk menghentikan macro ketika aplikasi tujuan berganti.

## Dua mode, terpisah penuh

Sejak v0.8.0 aplikasi punya dua mode. Keduanya punya model data, penyimpanan, overlay, dan
mesin eksekusi sendiri, sehingga kerusakan pada satu mode tidak menjalar ke mode lain.
Hanya **satu** mode boleh berjalan pada satu waktu.

| | Macro Biasa | Multi Smart Click |
|---|---|---|
| Kapan mengklik | Sesuai jeda waktu yang Anda atur | Sesuai kondisi warna di layar |
| Titik | Tanpa batas dari aplikasi | Maksimal 10 titik |
| Titik Jalur 2 | Tidak dipakai | Tanpa batas, tiap titik baca warna pixel |
| Jenis aksi | Tap, tahan, geser (swipe) | Tap, tahan |
| Android | 8.0+ (API 26) | **11+ (API 30)** |
| Baca layar | Tidak pernah | Hanya warna di titik pilihan |
| Cocok untuk | Urutan tetap yang sudah pasti | Layar yang berubah-ubah |

## Download

Unduh APK terbaru dari halaman **[GitHub Releases](https://github.com/ramax100/Rich_Clicker_Macro/releases/latest)**.

Setiap rilis menyertakan dua berkas:

- `Rich-Clicker-Macro-v0.9.15-com.richclickermacro-release.apk` — aplikasinya.
- `...apk.sha256` — sidik jari untuk memeriksa unduhan tidak rusak.

Cara memeriksa (opsional, tapi disarankan):

```bash
sha256sum Rich-Clicker-Macro-v0.9.15-com.richclickermacro-release.apk
```

Bandingkan hasilnya dengan isi berkas `.sha256`. Kalau berbeda, unduhan tidak utuh —
jangan dipasang.

> APK dipasang lewat sideload dan dapat diperiksa Google Play Protect. Jangan menonaktifkan
> proteksi perangkat. Pastikan APK berasal dari halaman Releases repository ini.

## Persyaratan

- **Android 8.0 (API 26)** atau lebih baru untuk Macro Biasa.
- **Android 11 (API 30)** atau lebih baru untuk Multi Smart Click, karena mode ini memakai
  API tangkap layar yang baru tersedia di sana.
- Accessibility Service harus diaktifkan secara manual oleh Anda.
- Tidak memerlukan izin overlay umum dan tidak memerlukan izin penyimpanan umum.

## Instalasi

1. Buka [GitHub Releases](https://github.com/ramax100/Rich_Clicker_Macro/releases/latest).
2. Unduh berkas APK pada rilis terbaru.
3. Izinkan instalasi dari sumber tersebut bila Android memintanya.
4. Pasang, lalu buka **Rich Clicker Macro**.
5. Tekan **Buka Pengaturan Aksesibilitas**, lalu aktifkan layanan **Rich Clicker Macro**.

**Kalau sebelumnya memasang versi lain:** paket aplikasi ini `com.richclickermacro` dengan
kunci penandatangan sendiri. Versi lama dengan paket berbeda (`com.example.autoclicker`)
**tidak bisa ditimpa** — hapus dulu yang lama. Profil bisa dipindahkan lewat
**Export/Import JSON**.

**Untuk Multi Smart Click:** setelah memasang atau memperbarui, matikan lalu nyalakan ulang
layanan aksesibilitas. Izin tangkap layar hanya aktif setelah layanan dinyalakan ulang.

## Cara penggunaan

### Macro Biasa

1. Pilih atau buat profil macro.
2. Atur jeda default, jeda antar-loop, batas loop, hitungan mundur, dan Keep Screen On.
3. Tekan **Simpan Pengaturan**.
4. Tekan **Tampilkan Editor di Atas Aplikasi**, lalu buka aplikasi tujuan.
5. Tambah dan geser marker ke posisi yang diinginkan.
6. Pilih jenis aksi, durasi, jumlah pengulangan, dan jeda setiap titik.
7. Opsional: atur **Jalur 2** (lihat bagian [Jalur 2](#jalur-2)) — rangkaian titik kedua yang
   otomatis dijalankan setelah macro berjalan beberapa menit.
8. Tekan `▶` untuk menjalankan.
9. Tekan **STOP** di menu/toolbar atau Quick Settings Tile untuk berhenti.

### Multi Smart Click

1. Buka tab **MULTI SMART CLICK**, lalu **Buka Editor Smart Click**.
2. Tekan **＋ Tambah Titik**, lalu ketuk bagian layar yang ingin diklik. Warna di titik itu
   langsung dibaca dan disimpan.
3. Untuk titik berikutnya, ulangi langkah 2.
4. Melalui **⚙ → Atur Titik Ini** Anda bisa mengubah kepekaan warna, berapa kali diklik,
   jenis klik (ketuk atau tahan), dan mengambil warna ulang.
5. Melalui **⚙ → Loop & Kecepatan** Anda mengatur jumlah putaran, **kecepatan untuk semua
   titik**, dan pengaman.
6. Tekan `▶`. Titik dijalankan berurutan dari titik 1 sampai terakhir, lalu diulang.

Panduan lengkap tiap menu ada di bagian [Semua fitur](#semua-fitur).

## Semua fitur

### Macro Biasa

- Jumlah titik tanpa batas dari aplikasi.
- Urutan titik dapat diubah dengan tombol naik/turun.
- Setelah titik terakhir, macro kembali ke titik pertama.
- Batas jumlah putaran dapat diatur; `0` berarti tanpa batas.
- Jeda setiap titik dan jeda antar-putaran diatur terpisah (0,1 detik – 1 jam).
- Hitungan mundur 0–10 detik sebelum macro dimulai.
- Jumlah pengulangan setiap titik: 1–1.000 kali.
- **Tap:** sentuhan singkat.
- **Long press:** tahan 0,6–60 detik.
- **Swipe:** geser dari marker awal ke marker endpoint `E` selama 0,1–60 detik.
- Gesture panjang dibagi menjadi segmen maksimal 500 ms supaya STOP lebih responsif.
- **Jalur 2:** rangkaian titik kedua dengan waktunya sendiri, dijalankan otomatis di tengah
  macro berjalan. Lihat [Jalur 2](#jalur-2).

### Jalur 2

Jalur 2 adalah **rangkaian titik kedua khusus mode Multi Smart Click**, dibuka lewat tombol `J2`
di toolbar smart. Macro Biasa tidak memakai Jalur 2 — ia sudah punya titik, jeda, dan ulangan
sendiri di toolbar-nya.

- Dibuka lewat tombol **`J2`** di toolbar Multi Smart Click. Panelnya hanya muncul saat dibuka,
  jadi toolbar tetap kecil.
- **Waktu** diatur di panel Jalur 2, dalam **menit** (1–720), lewat tombol `−`/`+` atau ketuk
  angka menitnya untuk mengetik langsung.
- **Yang dihitung adalah lama macro biasa (Jalur 1) berjalan**, bukan lama ia berhenti dan
  bukan lama Jalur 2 bekerja. Saat hitung mundur, saat STOP, dan selama Jalur 2 berjalan,
  hitungan waktu ikut berhenti.
- **Warna pixel menentukan klik.** Tiap titik Jalur 2 membaca warna di titiknya sendiri, sama
  seperti titik smart biasa. Setelah marker digeser ke posisi yang diinginkan, tekan
  **`Ambil Warna J2`**; warna di titik itu dibaca dua kali, dicocokkan, lalu disimpan. Marker
  Jalur 2 tanpa warna tampil abu-abu dan dilewati saat dijalankan.
- **`+ Titik J2`** menambah titik Jalur 2 **tanpa batas jumlah**, dan titiknya diletakkan
  dengan **mengetuk layar** — persis seperti titik smart biasa. Setelah tombol ditekan, panel
  Jalur 2 menyingkir dulu, muncul tulisan *"Ketuk bagian layar untuk titik JALUR 2"*, lalu
  ketuk posisi yang diinginkan di layar game. Titik jadi di posisi itu dan warna pixel-nya
  langsung dibaca otomatis.
  - Kenapa mengetuk layar: dulu titik diletakkan otomatis di satu koordinat tetap, sehingga
    sering jatuh di bawah toolbar sendiri — marker tidak terlihat dan warnanya gagal dibaca.
    Dengan mengetuk, titik selalu berada di tempat yang benar-benar Anda pilih.
  - Tempat yang tertutup toolbar sendiri ditolak dengan pesan jelas (warnanya akan terbaca
    sebagai warna toolbar, bukan warna game). Geser toolbar, lalu ketuk lagi.
  - Marker Jalur 2 berwarna **oranye** dengan label `J1`, `J2`, … dan titik kecil di bawahnya
    menunjukkan warna yang tersimpan. Geser marker untuk memindahkan; ketuk lalu `− Titik J2`
    untuk menghapus. Kalau marker digeser, warna lama otomatis dibuang dan perlu diambil ulang.
- Tiap titik Jalur 2 adalah satu jendela overlay. Saat jumlahnya mencapai 20, aplikasi
  mengingatkan agar RAM tidak penuh — pengingat itu tidak menghalangi penambahan titik.
- Titik Jalur 2 dijalankan berurutan **titik 1 → titik terakhir, satu kali**. Tiap titik
  **menunggu warnanya cocok** dulu sebelum diklik; kalau dalam 20 detik warnanya tidak pernah
  muncul, titik itu dilewati agar rangkaian tidak macet.
- Per titik Jalur 2 ada pengaturan sendiri: **Kepekaan** (1–120), **Ketuk/Tahan**, dan **×ulangan**
  (1–1.000 klik setiap kali warnanya cocok).
- **Saklar `J2 ON` / `J2 OFF`** adalah pengaman utama terhadap bentrok dengan macro biasa.
  Selama OFF, Jalur 2 **tidak pernah** menyela macro biasa: timernya diam, titiknya diredupkan,
  dan tidak ada klik tambahan. Tombolnya hijau saat ON dan abu-abu saat OFF, dan keadaannya
  tersimpan walau aplikasi ditutup.
  - **Bisa ditekan kapan saja, termasuk saat macro sedang berjalan.** Ini sengaja: mematikan
    Jalur 2 tidak boleh menunggu macro berhenti.
  - Kalau Jalur 2 **sedang bekerja** lalu saklar dimatikan, rangkaian Jalur 2 langsung berhenti
    dan **macro biasa lanjut** tanpa ikut berhenti.
  - Menekan tombol **`J2`** di toolbar saat macro berjalan juga langsung mematikan Jalur 2 —
    tombol OFF darurat, tanpa membuka panel.
  - Menyalakan Jalur 2 memulai hitungan waktunya dari nol, jadi tidak ada sisa waktu lama yang
    membuatnya tiba-tiba jalan.
  - Jalur 2 hanya bisa dinyalakan kalau ada minimal satu titik yang sudah punya warna.
- Mengambil warna pertama yang berhasil otomatis mengaktifkan Jalur 2 (saklar berubah jadi
  **`J2 ON`**). Saklar itu bisa mematikannya kembali tanpa menghapus titik.
- Label status di bawah panel selalu menyebut langkah berikutnya, misalnya
  *"Jalur 2 nonaktif • tekan + Titik J2 untuk mulai"*,
  *"Jalur 2 nonaktif • ambil warna dulu (0/1 siap)"*, atau
  *"Jalur 2 belum bisa baca layar • izin baca mati"* bila izin baca layar belum aktif.
  Jadi kalau ada yang belum siap, penyebabnya tertulis di situ — bukan cuma "mati".

**Alur saat waktu habis**

1. Macro biasa sedang berjalan seperti biasa.
2. Waktu Jalur 2 habis → macro biasa **berhenti seketika** (menunggu satu gesture yang sedang
   berjalan selesai, maksimal selama durasi gesture itu).
3. Jalur 2 dijalankan **titik 1 sampai titik terakhir, satu kali**.
4. Macro biasa **lanjut lagi dari titik 1**, dan hitungan waktu Jalur 2 mulai dari nol lagi.

**Di Multi Smart Click**

- Hitungan waktu memakai lama **smart click berjalan**. Saat waktu habis, smart click berhenti,
  Jalur 2 dijalankan (tiap titik menunggu warna pixel cocok), lalu smart click lanjut lagi dan
  hitungan waktunya mulai dari nol.
- Selama smart click maupun Jalur 2 berjalan, semua marker disembunyikan agar tidak ikut
  terekam tangkapan layar; kalau ada titik Jalur 2 yang tertutup toolbar, smart click menolak
  mulai dengan pesan jelas.

Pengaturan dan titik Jalur 2 disimpan terpisah dari profil macro maupun profil smart, berlaku
untuk semua profil, dan **tidak mengubah format Export/Import JSON** kedua mode.

### Overlay editor

- Tetap tampil saat Anda berpindah aplikasi.
- Marker kecil dengan pusat koordinat presisi; endpoint swipe berwarna biru berlabel `E`.
- Marker dan endpoint dapat mencapai tepi layar.
- Toolbar dapat digeser, diciutkan, dan dibuka kembali.
- **STOP selalu di dalam menu**, dan tetap terlihat di header saat macro berjalan.
- Tombol `◉/○` untuk menyembunyikan atau menampilkan marker.
- Posisi disimpan sebagai koordinat relatif sehingga menyesuaikan rotasi layar.

### Multi Smart Click

**Cara mengklik**

- Klik dipicu kondisi warna layar, bukan jeda waktu. Tidak ada pengaturan jeda per klik.
- Dua kondisi: **Cocok** (warna mendekati target) dan **Berubah** (warna menyimpang dari
  acuan).
- **Titik selalu mengklik tempat ia memantau.** Tidak ada lagi opsi "pantau di sini, klik di
  sana" — opsi itu dihapus pada v0.9.2 karena menjadi sumber utama klik meleset.
- **Berapa kali diklik:** satu titik bisa diklik beberapa kali setiap kali kondisinya cocok
  (1–1.000).
- **Urutan 1 → terakhir + Jumlah putaran:** titik dijalankan berurutan lalu diulang,
  seperti macro biasa. `0` berarti terus berulang tanpa batas.

**Kecepatan — satu pengaturan untuk semua titik**

Pilih lewat **⚙ → Loop & Kecepatan → Kecepatan semua titik**:

| Pilihan | Artinya |
|---|---|
| **Rekomendasi** (bawaan) | Paling aman: layar dibaca dua kali sebelum mengklik |
| **Cepat** | Langsung klik pada bacaan pertama |
| **Custom** | Isi sendiri tiga angka waktunya |

Jarak antar klik berlaku untuk **seluruh proses**, bukan per titik. Menambah titik tidak
membuat klik makin cepat.

**Akurasi dan pengaman**

- Titik yang tertutup toolbar ditolak sebelum dijalankan.
- Dua titik yang terlalu berdekatan ditolak, karena akan membaca area yang sama.
- Kalau ukuran tangkapan layar berbeda lebih dari **1%** dari layar sebenarnya, smart click
  **berhenti dengan pesan** — lebih baik berhenti daripada mengklik di tempat yang salah.
- Kalau layar berubah (rotasi atau ukuran), proses berhenti dan meminta titik diperiksa.
- Id titik selalu dibuat unik, termasuk saat mengimpor berkas berisi id kembar.
- Pengaman tambahan: berhenti setelah diam terlalu lama (bawaan 120 detik), batas total
  klik, berhenti saat aplikasi tujuan berganti, dan berhenti saat Android melaporkan RAM
  kritis.

**Lain-lain**

- Penandaan titik cukup dengan mengetuk layar; warna dibaca otomatis.
- **Ambil warna ulang** membaca warna suatu titik dua kali dan hanya menyimpannya bila kedua
  bacaan cocok, supaya layar yang masih bergerak tidak tersimpan sebagai warna target.
- Marker disembunyikan otomatis saat membaca layar agar tidak ikut terbaca.
- Profil, export/import, dan toolbar terpisah dari Macro Biasa.

### Profil dan data

- Buat, pilih, ubah nama, dan hapus profil.
- Posisi, urutan, jeda, jenis aksi, durasi, dan pengulangan tersimpan otomatis.
- Import/export semua profil memakai JSON schema v3; profil schema v1/v2 tetap terbaca.
- Nama hasil impor yang sama diberi akhiran supaya profil lama tidak tertimpa.
- Berkas impor dibatasi 2 juta karakter untuk menjaga stabilitas.
- Data profil rusak dicadangkan lebih dulu sebelum profil pemulihan dipakai.
- Bila pemilih file tidak tersedia, aplikasi menampilkan pesan atau alternatif lewat folder
  Download, bukan berhenti mendadak.

### Kontrol dan stabilitas

- Tombol mulai dan STOP di dalam menu/toolbar.
- Quick Settings Tile untuk memulai/menghentikan macro yang sudah dimuat.
- Keep Screen On opsional selama macro berjalan.
- Berhenti otomatis saat aplikasi tujuan berganti, atau saat ukuran/posisi/rotasi layar
  berubah.
- Validasi koordinat marker sebelum gesture; macro berhenti bila koordinat tidak cocok.
- Callback gesture dengan batas waktu, sehingga urutan tidak bertumpuk atau menggantung.
- **Export Log Diagnostik** lokal untuk menelusuri klik meleset, gesture ditolak, atau
  aplikasi tujuan berganti.
- **Cek RAM Clicker** menampilkan PSS, heap Java/native, dan jumlah jendela overlay.
- Mendukung Android 8–15, layar lipat, mode jendela, dan berbagai ukuran layar.

## Kontrol overlay

### Toolbar Macro Biasa

| Kontrol | Fungsi |
|---|---|
| `▶` | Memulai hitungan mundur dan macro |
| `STOP` | Menghentikan macro dari menu/toolbar |
| `+` | Menambah titik |
| `−` | Menghapus titik terpilih |
| `↑` / `↓` | Mengubah urutan titik |
| `◉` / `○` | Menyembunyikan atau menampilkan marker |
| `×` | Menutup editor overlay |
| Label jeda | Membuka input jeda langsung |
| `Tap/Tahan/Geser` | Mengubah jenis aksi titik |
| Label durasi | Mengatur durasi tahan atau geser |
| `×N` | Mengatur jumlah aksi pada titik |
| Header `MACRO` | Geser untuk memindahkan, ketuk untuk menciutkan |

### Toolbar Multi Smart Click

| Kontrol | Fungsi |
|---|---|
| `▶` | Memulai (didahului hitungan mundur bila diaktifkan) |
| `STOP` | Menghentikan proses |
| `＋ Tambah Titik` | Menandai titik baru dengan mengetuk layar |
| `⚙` | Membuka menu: **Atur Titik Ini**, **Loop & Kecepatan**, **Tes Klik Titik Ini**, **Cara Pakai** |

## Quick Settings Tile

1. Buka panel Quick Settings Android.
2. Pilih menu edit tile.
3. Tambahkan tile **Rich Clicker**.
4. Muat profil melalui aplikasi lebih dulu.
5. Ketuk tile untuk mulai atau berhenti. Bila belum ada profil dimuat, tile membuka aplikasi.

## Privasi dan izin

| Yang dipakai | Untuk apa | Catatan |
|---|---|---|
| Accessibility Service | Mengirim gesture dan memantau jendela aktif | Diaktifkan manual oleh Anda |
| Tangkap layar (API 30+) | Membaca warna di titik smart | Hanya di memori; tidak disimpan, tidak dikirim |
| Metadata jendela | Berhenti saat aplikasi tujuan berganti | Nama paket saja, bukan isi layar |

Aplikasi tidak meminta izin internet, tidak punya fitur tersembunyi, dan tidak menyertakan
anti-deteksi, anti-ban, maupun cara melewati proteksi aplikasi lain.

Log diagnostik ditulis lokal di penyimpanan aplikasi, berputar pada 256 KB, dan hanya
dikirim keluar bila Anda sendiri menekan **Export Log Diagnostik**.

## Batasan yang perlu diketahui

Ini bagian yang paling sering menimbulkan salah paham, jadi ditulis apa adanya.

- **Perilaku aplikasi lain tidak bisa dijamin.** Auto clicker mengirim sentuhan; apakah
  sentuhan itu diterima, dan apa akibatnya, sepenuhnya ditentukan aplikasi tujuan.
- **Multi Smart Click bergantung pada warna.** Kalau tampilan berubah (animasi, tema,
  kecerahan, atau posisi tombol bergeser) di antara pembacaan dan klik, hasilnya bisa
  meleset. Pilih titik pada bagian yang relatif diam.
- **Aplikasi yang memakai `FLAG_SECURE`** (misalnya aplikasi perbankan, atau sebagian
  aplikasi streaming) tidak bisa ditangkap layarnya, sehingga mode smart tidak bisa dipakai.
- **Laju tangkap layar dibatasi Android**, sekitar 333 ms per pengambilan. Mode smart tidak
  bisa lebih cepat dari itu, sekeras apa pun pengaturan kecepatannya.
- **Penyebab aplikasi lain tertutup sendiri tidak bisa disimpulkan dari sini.**
  Pengukuran memori clicker pada sesi panjang tidak menunjukkan kebocoran, tetapi itu bukan
  bukti bahwa fitur ini bebas dari pengaruh terhadap aplikasi lain.
- **Verifikasi rilis** dilakukan lewat unit test, lint, dan pemeriksaan berkas APK — bukan
  pengujian di perangkat fisik. Karena itu tidak ada jaminan bebas cacat di semua perangkat.

## Build dari source

Persyaratan pengembangan:

- JDK 17
- Android SDK 35
- Gradle wrapper yang tersedia di repository

```bash
git clone https://github.com/ramax100/Rich_Clicker_Macro.git
cd Rich_Clicker_Macro
git checkout feature/rich-clicker-macro
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Untuk APK rilis:

```bash
./gradlew assembleRelease
```

Jangan pernah meng-commit keystore, kata sandi penandatanganan, atau APK pribadi. Pola
tersebut sudah tercantum di `.gitignore`.

## Catatan perubahan

Riwayat perubahan tiap versi ada pada halaman
[GitHub Releases](https://github.com/ramax100/Rich_Clicker_Macro/releases), termasuk
ringkasan perbaikan pada setiap rilis.

## Modifikasi dan kontribusi

1. Fork repository.
2. Buat branch fitur baru.
3. Lakukan perubahan dan pengujian.
4. Kirim pull request dengan ringkasan perubahan.

Penggunaan Accessibility Service harus tetap transparan dan memerlukan tindakan pengguna.
Jangan menambahkan fungsi tersembunyi, pencurian data, anti-ban, atau mekanisme untuk
melewati proteksi aplikasi maupun perangkat.

## Gratis dan bebas dimodifikasi

Rich Clicker Macro sepenuhnya gratis:

- Tanpa biaya pembelian.
- Tanpa langganan atau fitur premium.
- Tanpa iklan.
- Source code tersedia dan bebas dipelajari, diubah, serta didistribusikan kembali
  berdasarkan [MIT License](./LICENSE).

Anda boleh membuat fork atau versi modifikasi. Pertahankan pemberitahuan hak cipta dan
lisensi saat mendistribusikan ulang source.

## Komunitas

Gabung channel Telegram untuk informasi dan update:

[![Telegram](https://img.shields.io/badge/Telegram-@ChRichStore-2CA5E0?logo=telegram&logoColor=white)](https://t.me/ChRichStore)

**Channel:** [https://t.me/ChRichStore](https://t.me/ChRichStore)

## Lisensi

Source code dirilis dengan [MIT License](./LICENSE). Logo menggunakan gambar yang disediakan pemilik repository.
