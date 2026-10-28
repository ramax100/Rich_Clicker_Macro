<div align="center">
  <img src="./1000641516.png" alt="Rich Clicker Macro Logo" width="180" />
  <h1>Rich Clicker Macro</h1>
  <p>Auto clicker dan editor macro multi-aksi untuk Android.</p>

  [![Source version](https://img.shields.io/badge/source-0.6.4-4f46e5)](./app/build.gradle.kts)
  [![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android)](#persyaratan)
  [![License](https://img.shields.io/badge/license-MIT-blue)](./LICENSE)
  [![Free](https://img.shields.io/badge/price-100%25%20gratis-brightgreen)](#gratis-dan-bebas-dimodifikasi)
</div>

## Tentang aplikasi

**Rich Clicker Macro** adalah aplikasi Android untuk membuat rangkaian tap, long press, dan swipe yang berjalan berurutan di atas aplikasi lain. Setiap macro dapat disimpan sebagai profil, diulang, diberi countdown, serta dikontrol dari overlay atau Quick Settings.

Aplikasi bekerja melalui Android Accessibility Service. Layanan hanya menjalankan gesture setelah pengguna mengaktifkannya secara manual dan menekan tombol mulai. Aplikasi tidak membaca isi layar (`canRetrieveWindowContent=false`), tidak berjalan otomatis saat perangkat dinyalakan, dan tidak memasukkan profil macro ke Android Auto Backup.

## Gratis dan bebas dimodifikasi

Rich Clicker Macro sepenuhnya gratis:

- Tanpa biaya pembelian.
- Tanpa langganan atau fitur premium.
- Tanpa iklan.
- Source code tersedia dan bebas dipelajari, diubah, serta didistribusikan kembali berdasarkan [MIT License](./LICENSE).

Anda boleh membuat fork atau versi modifikasi. Pertahankan pemberitahuan hak cipta dan lisensi saat mendistribusikan ulang source.

## Download

Unduh APK stabil terbaru melalui halaman [GitHub Releases](https://github.com/ramax100/Rich_Clicker_Macro/releases/latest). Halaman tersebut selalu menunjuk release yang sudah dibangun, ditandatangani, dan diverifikasi; versi source dapat lebih baru sebelum release berikutnya diterbitkan.

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
- Jeda setiap titik dijalankan setelah aksi; setelah titik terakhir, jeda antar-loop ditambahkan sebelum kembali ke titik pertama.
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
- Tombol berhenti `■` selalu tersedia pada header toolbar, termasuk saat menu diciutkan.

### Profil dan data

- Buat, pilih, ubah nama, dan hapus profil.
- Posisi, urutan, delay, jenis aksi, durasi, dan repeat tersimpan otomatis.
- Import/export semua profil menggunakan JSON schema v2.
- Profil schema v1 tetap kompatibel.
- Nama hasil import yang sama diberi suffix agar profil lama tidak tertimpa.
- File import dan export dibatasi 2 MB agar hasil export yang berhasil selalu dapat diimpor kembali.
- Data profil rusak dicadangkan sebelum profil Recovery digunakan.

### Log diagnostik

- Log lokal mencatat lifecycle service, aplikasi target, System UI, lockscreen, start/stop macro, hasil gesture, Quick Settings, dan kegagalan overlay.
- Event disimpan secara internal sebagai JSON Lines dengan rotasi maksimal sekitar 512 KB dan tidak ikut Android Auto Backup. Pencatatan bersifat best-effort; penghentian proses mendadak dapat menghilangkan event terakhir.
- Isi layar, teks accessibility, nama profil, dan koordinat marker tidak dicatat. Nama package aplikasi dicatat agar perpindahan target dapat dikenali.
- Log dapat dilihat atau diekspor dari kartu **Log Diagnostik**. File export dapat dibaca oleh document provider/lokasi yang dipilih pengguna.
- Log hanya menjelaskan observasi Rich Clicker. Force close internal aplikasi lain tetap memerlukan `adb logcat` atau bug report perangkat.

### Kontrol dan stabilitas

- Tombol mulai dan berhenti tersedia di toolbar.
- Quick Settings Tile untuk mulai/berhenti macro yang sudah dimuat.
- Keep Screen On opsional selama macro berjalan.
- Callback gesture mencegah urutan bertumpuk.
- Macro berhenti jika aplikasi target berubah, layar terkunci, atau System UI dibuka. Quick Settings mengenali penghentian ini agar ketukan STOP tidak memulai ulang macro.
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
| `■` | Menghentikan macro; selalu terlihat pada header toolbar |
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
5. Ketuk tile untuk meminta mulai. Macro dimulai setelah panel ditutup dan aplikasi target aktif kembali.
6. Jika macro sedang berjalan, membuka panel Quick Settings menghentikannya lebih dulu. Tile menampilkan “Sudah dihentikan”; ketuk untuk mengonfirmasi tanpa memulai ulang.
7. Saat status “Menunggu aplikasi”, ketuk lagi untuk membatalkan permintaan mulai.

## Build dari source

Persyaratan pengembangan:

- JDK 17.
- Android SDK 35.
- Gradle wrapper yang tersedia di repository.

```bash
git clone https://github.com/ramax100/Rich_Clicker_Macro.git
cd Rich_Clicker_Macro
./gradlew assembleDebug
```

APK release resmi ditandatangani di luar repository menggunakan update key yang dipertahankan pengelola. Build lokal tanpa key tersebut tidak dapat dipasang sebagai pembaruan atas APK resmi. Fingerprint SHA-256 sertifikat APK v0.6.3 adalah `C4:EF:BF:87:53:B3:72:C6:2B:65:D5:02:7C:1A:1A:ED:6B:3C:F9:E9:A3:6C:81:C9:01:3A:5F:A6:77:05:89:9F`.

Jangan commit keystore, password signing, atau APK privat. `.gitignore` melindungi path signing dan APK root yang tercantum; periksa kembali file sensitif di lokasi lain sebelum commit.

### Menerbitkan APK resmi

Workflow `.github/workflows/release.yml` membangun, menandatangani, memverifikasi, dan menerbitkan APK v0.6.4 secara manual. Buat environment **release** melalui **Settings → Environments**, aktifkan required reviewer, lalu tambahkan environment secrets berikut:

- `ANDROID_KEYSTORE_BASE64`: isi keystore v0.6.3 yang telah diubah menjadi Base64 satu baris.
- `ANDROID_KEYSTORE_PASSWORD`: password keystore.
- `ANDROID_KEY_ALIAS`: alias update key.
- `ANDROID_KEY_PASSWORD`: password update key.

Setelah workflow digabungkan ke default branch, buka **Actions → Release signed APK → Run workflow**. Workflow hanya menerima default branch, memeriksa versionCode/versionName, dan membandingkan fingerprint sertifikat dengan APK v0.6.3 sebelum membuat tag serta GitHub Release v0.6.4. Jangan kirim nilai secrets melalui issue, pull request, commit, atau chat.

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
