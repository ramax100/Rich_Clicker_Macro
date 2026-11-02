# Audit menyeluruh v0.9.5 — akurasi klik dan bug sisa

Tanggal: 2026-11-02

Permintaan: periksa bug/error seluruh fitur, perkuat akurasi smart click supaya klik
sesuai titik yang ditentukan, lalu terbitkan.

**Tidak ada pengujian di perangkat nyata.** Semua bukti di bawah berasal dari unit test,
lint, dan pemeriksaan berkas APK.

## Cara audit dilakukan

Bukan dengan membaca kode lalu menebak. Saya menulis test "probe" yang mencoba merusak
kode dengan cara yang realistis, menjalankannya, lalu memeriksa mana yang benar-benar
gagal. Setiap kegagalan diperiksa satu per satu: ada yang cacat nyata, ada yang test saya
sendiri yang salah rancang.

Contoh yang **bukan** cacat: probe yang mengira titik ke-2 tidak boleh aktif selagi titik
ke-1 aktif. Itu salah paham saya — mode smart memang berurutan 1 → terakhir, jadi titik
ke-2 baru boleh setelah titik ke-1. Test itu dibuang, bukan kodenya yang diubah.

## Tiga cacat nyata, sudah diperbaiki

### 1. Dua titik bisa berbagi satu area pantau — klik jadi tidak sesuai titik

Id titik dibaca apa adanya dari berkas impor. Kalau dua titik punya id yang sama, keduanya
runtuh menjadi **satu** area pantau: titik A dinilai dari warna milik titik B, lalu diklik
di posisi A. Persis gejala "klik tidak sesuai titik".

Terbukti: impor dengan 2 titik ber-id sama menghasilkan 1 id unik.

Perbaikan: id duplikat diberi nomor baru saat impor, di **kedua** jenis profil
(smart dan makro klasik). Id yang sudah unik tidak diubah sama sekali.

### 2. Perubahan pengaturan hilang tanpa pesan

`updateProfile()` mencari profil berdasarkan nama. Kalau profil itu sudah diganti nama
atau diganti lewat impor, fungsi mengembalikan `null` dan **seluruh** perubahan dari editor
overlay dibuang tanpa pemberitahuan.

Perbaikan: kalau nama tidak ketemu, perubahan diterapkan ke profil yang sedang aktif, dan
kejadiannya dicatat di diagnostik (`smart_profile_relinked`). Profil baru tetap **tidak**
dibuat dari nama yang sudah tidak ada.

### 3. Selisih ukuran tangkapan layar ditoleransi terlalu longgar

Posisi dinormalisasi, jadi sampler membaca gambar dan gesture mengirim sentuhan. Kalau
tangkapan layar bergeser dari layar sebenarnya, keduanya menunjuk tempat berbeda. Batas
lama 2% berarti selisih hingga ±48 piksel pada layar 2400 piksel masih diterima — cukup
untuk mengenai elemen yang salah.

Perbaikan: batas diperketat menjadi **1%** (±24 piksel). Kalau terlampaui, smart click
**berhenti dengan pesan**, bukan mengklik di tempat yang tidak dipilih pengguna. Gagal
terang-terangan lebih baik daripada salah klik.

## Hasil verifikasi

| Pemeriksaan | Hasil |
|---|---|
| Debug — smart (termasuk 11 test regresi baru) | **166 test, 0 gagal** |
| Debug — data, safety, diagnostics, overlay, accessibility, home, import/export | lulus |
| Rilis — smart + data | lulus |
| Lint release | **No issues found** |
| Total test dalam sumber | 263 |
| Package / versi | `com.richclickermacro` / code 23, 0.9.5 |
| zipalign | OK |
| Sertifikat | SHA-256 `087d9bac…6aac`, sama sejak v0.7.0 |

### Test regresi yang dikunci

`SmartDefectRegressionTest` (11 test) — semuanya **gagal** pada kode v0.9.4:

- id unik setelah impor (2 titik, 3 titik, id berbeda dipertahankan)
- satu area pantau per titik
- suntingan tetap tersimpan walau profil sudah diganti nama
- suntingan tetap tersimpan walau profil lama sudah dihapus
- profil lain tidak ikut tersentuh
- tangkapan layar terpotong 2% ditolak, skala seragam diterima, selisih 1 piksel diterima

Ditambah satu test di `MacroRepositoryTest` untuk id duplikat pada makro klasik.

## Yang diperiksa dan ternyata sudah benar

Supaya jelas cakupannya, ini juga saya periksa dan tidak menemukan cacat:

- **Pemetaan koordinat** — perjalanan pulang-pergi posisi marker → normalisasi → klik
  akurat sampai di bawah 1 piksel untuk seluruh 101 posisi uji.
- **Posisi marker yang digeser** benar-benar dipakai saat mulai; `startRun` menyegarkan
  posisi dari tampilan sebelum menghitung.
- **Urutan 1 → terakhir** tidak bisa dilangkahi.
- **Batas total klik** tetap memotong di tengah rentetan klik.
- **Penyimpanan rusak** tidak membuat aplikasi berhenti; profil bawaan dipulihkan.
- **Ekspor → impor** mempertahankan jumlah titik dan id.
- **Gestur**: validasi koordinat, penanda waktu callback, dan penolakan sistem ditangani.
- **Diagnostik**: berkas berputar pada 256 KB, antrean dibatasi 64, tidak ada kebocoran.
- **Toolbar** tidak bisa tertimpa: titik di bawah toolbar ditolak sebelum dijalankan.

## Satu hal yang saya hentikan

Saya sempat berencana memeriksa piksel tengah di samping rata-rata 6×6 piksel. Setelah
dihitung, pada ukuran patch 6 piksel perbedaannya dapat diabaikan — itu menambah kode
tanpa memperbaiki apa pun. Dibatalkan.

## Yang belum terverifikasi

1. Perilaku di perangkat nyata: akurasi warna, kecepatan, dan tampilan dialog.
2. Penyebab force close Ninja Ability setelah berjalan lama — masih belum diketahui.
3. Seberapa sering batas 1% memicu penghentian pada perangkat tertentu. Kalau terlalu
   sering muncul, batasnya perlu dilonggarkan sedikit.

## Catatan proses

Sandbox hanya punya ±2 GB RAM tanpa swap. Menjalankan seluruh test sekaligus membuat
proses Gradle mati; pekerjaan dipecah menjadi beberapa kelompok, masing-masing satu proses.
Itu batas lingkungan, bukan cacat aplikasi.

## Workspace

Hanya satu APK tersisa:

```
/home/user/Rich-Clicker-Macro-v0.9.5-com.richclickermacro-release.apk
```
