# Audit toolbar dan fitur — 0.7.4

## Perubahan tampilan

- Lebar maksimum toolbar: **220 dp**, sebelumnya 260 dp (sekitar 15% lebih sempit).
- Tinggi baris utama 34 dp, utilitas 30 dp, header 28 dp; padding dan sudut panel dikurangi.
- Pengaturan titik lanjutan (urutan, jeda, jenis aksi, durasi, repeat) disembunyikan secara default di balik **⚙ Atur**.
- Saat macro dimulai, panel lanjutan ditutup otomatis dan pemeriksaan overlap menunggu layout mengecil.
- STOP tetap di dalam menu. STOP header tetap tersedia saat toolbar diciutkan dan macro berjalan.
- Tombol tutup editor tetap di header. Jumlah titik/loop tetap terlihat pada status ringkas.

## Temuan audit dan perbaikan

| Area | Temuan | Tindakan |
|---|---|---|
| Swipe vs toolbar | Pemeriksaan lama hanya mengecek titik awal/akhir; bagian tengah swipe bisa melintasi toolbar | Interseksi segmen–persegi panjang (Liang–Barsky), termasuk sentuhan tepi; ditolak sebelum start dan sebelum gesture |
| Panel lanjutan saat start | Panel bisa tetap menutupi game; pengecekan overlap dapat memakai tinggi lama | Tutup panel, tunggu layout singkat, lalu mulai dengan pemeriksaan ulang |
| Toolbar dibuat ulang | Referensi panel lanjutan bisa tertinggal saat toolbar lama dilepas | Referensi di-reset bersama toolbar |
| Profil | Buat/simpan/hapus profil belum semua dilindungi dari kegagalan penyimpanan | runCatching + dialog gagal + log diagnostik |
| Marker saat berjalan | Tombol sembunyikan marker sebelumnya dinonaktifkan pada versi awal perubahan | Tetap dapat digunakan; tidak mengubah koordinat atau menjanjikan penghematan RAM |

Fitur yang tetap dipertahankan: tap/long press/swipe, repeat per titik, jeda per titik/loop, batas loop, countdown, Quick Settings, import/export JSON dan Download, cek RAM, log diagnostik, guard aplikasi/layar, serta pemulihan toolbar.

## Verifikasi

- 88 test debug dan 88 test release lulus, 0 gagal/error/skip.
- lintDebug dan lintRelease: No issues found.
- assembleRelease berhasil; zipalign dan apksigner valid.
- Package `com.richclickermacro`, versionCode 15, versionName 0.7.4; sertifikat sama dengan 0.7.0–0.7.3.
- SHA-256: `e4ed3f1e03f7a6a371fd9bab29d7751d777f06e09953499a3dbffc517fd22a7d`.
- **Bukan uji perangkat nyata dan bukan jaminan bebas bug.** Uji visual kenyamanan toolbar dan sesi panjang game masih diperlukan di HP pengguna.
