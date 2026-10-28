# Bug Audit — Rich Clicker Macro source v0.6.4

Audit ini berlaku untuk source v0.6.4 pada branch perbaikan. APK stabil v0.6.3 dibuat dari commit yang lebih lama dan belum memuat seluruh perubahan berikut.

## Diperbaiki

- Data profil JSON korup dicadangkan sebelum profil Recovery persisten digunakan.
- Tipe data SharedPreferences yang salah tidak lagi membuat pemulihan profil gagal.
- Nama profil duplikat setelah sanitasi dianggap korup agar profil tidak menjadi tidak terjangkau.
- Migrasi interval lama dan import schema v1 mengisi jeda antar-loop dengan benar.
- Rename saat berpindah profil memperbarui daftar profil tanpa meninggalkan pilihan stale.
- Validasi gagal saat berpindah profil tidak lagi menghapus draft pengguna.
- Import dan export dibatasi berdasarkan byte; import juga menolak UTF-8 yang rusak.
- Nama Unicode dipotong berdasarkan code point tanpa membelah surrogate pair.
- Long press/swipe dibagi menjadi segmen maksimal 500 ms; tombol `■` membatalkan kelanjutan segmen.
- Macro berhenti ketika aplikasi target berubah, layar terkunci, System UI dibuka, atau konfigurasi layar berubah. Tile mengenali penghentian oleh System UI agar tindakan STOP tidak memulai ulang macro.
- Quick Settings Tile dapat menghentikan macro segera walaupun perangkat terkunci.
- Dialog delay/durasi/repeat ditutup sebelum macro dimulai atau konfigurasi berubah.
- State toolbar collapse dan hide marker direset setelah editor benar-benar berhasil ditutup.
- Tombol `■` selalu tersedia pada header toolbar, termasuk ketika isi toolbar diciutkan.
- Pusat tap/swipe memakai posisi marker fisik dan dapat mencapai pixel pertama/terakhir layar.
- Foldable/windowed memakai current WindowMetrics beserta origin aktual.
- Toolbar dan marker menyesuaikan ulang ukuran saat konfigurasi layar berubah.
- Jeda titik terakhir tidak lagi diabaikan; jeda titik dan jeda antar-loop dijalankan berurutan.
- Perubahan pengaturan profil menghentikan run aktif agar satu run tidak mencampur konfigurasi lama dan baru.
- Cleanup overlay mencoba removeViewImmediate untuk view yang sudah maupun belum attached.
- Kegagalan drag tidak lagi menyimpan posisi yang tidak pernah berhasil ditampilkan.
- Delay/durasi dikuantisasi ke presisi 100 ms agar tampilan dan nilai eksekusi konsisten.

## Verifikasi source

- Audit statis dilakukan terhadap Activity, repository, model, preferences, accessibility service, overlay, Quick Settings Tile, manifest, resource, build configuration, dan dokumentasi.
- `git diff --check` digunakan untuk memeriksa whitespace patch.
- Konsistensi state, callback generation, koordinat, profil, import/export, dan lifecycle diperiksa melalui penelusuran seluruh jalur kode.
- Gradle wrapper dipasangi checksum distribusi resmi Gradle 8.11.1.

## Belum dapat diverifikasi di sandbox

- Build source v0.6.4 belum dapat dijalankan karena Android Gradle Plugin 8.9.2 tidak tersedia di cache dan sandbox memblokir repository dependensi eksternal.
- APK v0.6.4 belum dibuat, ditandatangani, atau diterbitkan. APK v0.6.3 yang ada tidak mewakili source terbaru.
- Belum ada pengujian perangkat untuk OEM, foldable, desktop mode, cutout, lockscreen, dan transisi immersive mode.

## Batasan platform

- Android tidak menyediakan API publik untuk membatalkan stroke Accessibility yang sedang aktif secara langsung. Segmentasi membatasi kelanjutan setelah STOP hingga maksimal sekitar 500 ms.
- Macro berbasis koordinat. Jika tampilan aplikasi target berubah tetapi package tetap sama, elemen berbeda yang berpindah ke koordinat marker dapat menerima gesture.
- Jumlah titik dibatasi oleh memori, ukuran SharedPreferences, dan kemampuan WindowManager perangkat.
- Backup data korup pertama dan terbaru dipertahankan secara internal; belum ada layar pemulihan manual untuk mengedit backup.
