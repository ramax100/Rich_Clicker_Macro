<div align="center">
  <img src="./1000641516.png" alt="Rich Clicker Macro Logo" width="180" />
  <h1>Rich Clicker Macro</h1>
  <p>Auto clicker dan editor macro multi-aksi untuk Android.</p>

  [![Version](https://img.shields.io/badge/version-0.6.3-4f46e5)](https://github.com/ramax100/Rich_Clicker_Macro/releases/latest)
  [![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android)](#persyaratan)
  [![License](https://img.shields.io/badge/license-MIT-blue)](./LICENSE)
  [![Free](https://img.shields.io/badge/price-100%25%20gratis-brightgreen)](#gratis-dan-bebas-dimodifikasi)
</div>

## Tentang aplikasi

**Rich Clicker Macro** adalah aplikasi Android untuk membuat rangkaian tap, long press, dan swipe yang berjalan berurutan di atas aplikasi lain. Setiap macro dapat disimpan sebagai profil, diulang, diberi countdown, serta dikontrol dari overlay atau Quick Settings.

Aplikasi bekerja melalui Android Accessibility Service. Layanan hanya menjalankan gesture setelah pengguna mengaktifkannya secara manual dan menekan tombol mulai. Aplikasi tidak membaca isi layar (`canRetrieveWindowContent=false`) dan tidak berjalan otomatis saat perangkat dinyalakan.

## Gratis dan bebas dimodifikasi

Rich Clicker Macro sepenuhnya gratis:

- Tanpa biaya pembelian.
- Tanpa langganan atau fitur premium.
- Tanpa iklan.
- Source code tersedia dan bebas dipelajari, diubah, serta didistribusikan kembali berdasarkan [MIT License](./LICENSE).

Anda boleh membuat fork atau versi modifikasi. Pertahankan pemberitahuan hak cipta dan lisensi saat mendistribusikan ulang source.

## Download

Unduh APK terbaru melalui halaman [GitHub Releases](https://github.com/ramax100/Rich_Clicker_Macro/releases/latest).

Jika asset release tidak tampil, gunakan [download APK v0.6.3 langsung](https://github.com/ramax100/Rich_Clicker_Macro/raw/refs/heads/feature/rich-clicker-macro/release/Rich-Clicker-Macro-v0.6.3-release.apk).

> APK dipasang melalui sideload dan dapat diperiksa oleh Google Play Protect. Jangan menonaktifkan proteksi perangkat. Pastikan APK berasal dari repository ini dan cocokkan checksum pada catatan release.

## Persyaratan

- Android 8.0 (API 26) atau lebih baru.
- Accessibility Service harus diaktifkan secara manual.
- Tidak memerlukan izin overlay umum atau izin penyimpanan umum.

## Semua fitur

### Macro multi-titik

- Jumlah titik tidak dibatasi oleh aplikasi.
- Urutan titik dapat diubah dengan tombol naik/turun.
- Setelah titik terakhir, macro dapat kembali ke titik pertama.
- Batas jumlah loop dapat diatur; nilai `0` berarti tanpa batas.
- Jeda setiap titik dan jeda antar-loop dapat diatur terpisah.
- Countdown 0–10 detik sebelum macro dimulai.
- Jumlah pengulangan setiap titik: 1–1.000 kali.

### Jenis aksi

- **Tap:** satu sentuhan singkat.
- **Long press:** tahan 0,6–60 detik.
- **Swipe:** geser dari marker awal menuju marker endpoint `E` selama 0,1–60 detik.
- Gesture panjang dibagi menjadi segmen maksimal 500 ms agar penghentian lebih responsif.

### Overlay editor

- Tetap tampil ketika berpindah ke aplikasi lain.
- Marker kecil dengan pusat koordinat presisi.
- Endpoint swipe berwarna biru dan berlabel `E`.
- Marker serta endpoint dapat mencapai tepi layar.
- Tombol `◉/○` untuk menyembunyikan atau menampilkan marker.
- Toolbar dapat dipindahkan, diciutkan, dan dibuka kembali.
- Toolbar responsif untuk perangkat dengan layar sempit.
- Posisi disimpan sebagai koordinat relatif agar menyesuaikan rotasi layar.
- Tombol berhenti `■` tersedia di dalam toolbar.

### Profil dan data

- Buat, pilih, ubah nama, dan hapus profil.
- Posisi, urutan, delay, jenis aksi, durasi, dan repeat tersimpan otomatis.
- Import/export semua profil menggunakan JSON schema v2.
- Profil schema v1 tetap kompatibel.
- Nama hasil import yang sama diberi suffix agar profil lama tidak tertimpa.
- File import dibatasi 2 MB untuk menjaga stabilitas.
- Data profil rusak dicadangkan sebelum profil Recovery digunakan.

### Kontrol dan stabilitas

- Tombol mulai dan berhenti tersedia di toolbar.
- Quick Settings Tile untuk mulai/berhenti macro yang sudah dimuat.
- Keep Screen On opsional selama macro berjalan.
- Callback gesture mencegah urutan bertumpuk.
- Draft pengaturan tetap tersedia saat layar berotasi.
- Mendukung Android 8–15, foldable/windowed mode, dan berbagai ukuran layar melalui WindowMetrics/real display metrics.

## Cara instalasi

1. Buka [GitHub Releases](https://github.com/ramax100/Rich_Clicker_Macro/releases/latest).
2. Download file APK pada release terbaru.
3. Izinkan instalasi dari sumber tersebut melalui pengaturan Android jika diminta.
4. Instal lalu buka **Rich Clicker Macro**.
5. Tekan **Buka Pengaturan Aksesibilitas** dan aktifkan layanan **Rich Clicker Macro**.

Untuk memperbarui aplikasi, instal APK versi baru di atas versi lama. Package name dan signing key dipertahankan agar data profil tetap tersedia.

## Cara penggunaan

1. Pilih atau buat profil macro.
2. Atur delay default, jeda antar-loop, batas loop, countdown, dan Keep Screen On.
3. Tekan **Simpan Pengaturan**.
4. Tekan **Tampilkan Editor di Atas Aplikasi**.
5. Buka aplikasi tujuan.
6. Tambahkan dan geser marker ke posisi yang diinginkan.
7. Pilih jenis aksi, durasi, jumlah pengulangan, dan delay setiap titik.
8. Tekan `▶` untuk menjalankan macro.
9. Gunakan `■` di dalam toolbar atau Quick Settings Tile untuk berhenti.

## Kontrol overlay

| Kontrol | Fungsi |
|---|---|
| `▶` | Memulai countdown dan macro |
| `■` | Menghentikan macro |
| `+` | Menambah titik |
| `−` | Menghapus titik terpilih |
| `↑` / `↓` | Mengubah urutan titik |
| `◉` / `○` | Hide/show semua marker |
| `×` | Menutup editor overlay |
| Label delay | Membuka input delay langsung |
| `Tap/Tahan/Geser` | Mengubah jenis aksi titik |
| Label durasi | Mengatur durasi long press/swipe |
| `×N` | Mengatur jumlah aksi pada titik |
| Header `MACRO` | Geser untuk memindahkan, ketuk untuk collapse/expand |

## Quick Settings Tile

1. Buka panel Quick Settings Android.
2. Pilih menu edit tile.
3. Tambahkan tile **Rich Clicker**.
4. Muat profil melalui aplikasi terlebih dahulu.
5. Ketuk tile untuk mulai atau berhenti. Jika belum ada profil dimuat, tile membuka aplikasi.

## Build dari source

Persyaratan pengembangan:

- JDK 17.
- Android SDK 35.
- Gradle wrapper yang tersedia di repository.

```bash
git clone https://github.com/ramax100/Rich_Clicker_Macro.git
cd Rich_Clicker_Macro
git checkout feature/rich-clicker-macro
./gradlew assembleDebug
```

Jangan commit keystore, password signing, atau APK privat. Pola tersebut sudah dicantumkan dalam `.gitignore`.

## Modifikasi dan kontribusi

1. Fork repository.
2. Buat branch fitur baru.
3. Lakukan perubahan dan pengujian.
4. Kirim pull request dengan ringkasan perubahan.

Penggunaan Accessibility Service harus tetap transparan dan memerlukan tindakan pengguna. Jangan menambahkan fungsi tersembunyi, pencurian data, anti-ban, atau mekanisme untuk melewati proteksi aplikasi/perangkat.

## Komunitas

Gabung channel Telegram untuk informasi dan update:

[![Telegram](https://img.shields.io/badge/Telegram-@ChRichStore-2CA5E0?logo=telegram&logoColor=white)](https://t.me/ChRichStore)

**Channel:** [https://t.me/ChRichStore](https://t.me/ChRichStore)

## Lisensi

Source code dirilis dengan [MIT License](./LICENSE). Logo menggunakan gambar yang disediakan pemilik repository.
