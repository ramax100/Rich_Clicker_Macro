# Audit v0.9.4 — kecepatan klik berlaku menyeluruh

Tanggal: 2026-11-01

Dua laporan pengguna:
1. "Bug di fitur atur kecepatan, ketika dipilih reaksinya hanya kedip."
2. "Harusnya atur kecepatan klik itu menyeluruh semua titik langsung, bukan 1 per 1."

Keduanya bug nyata. **Tidak ada pengujian di perangkat nyata.**

## Bug 1 — pilihan langsung tertimpa (terlihat seperti kedip)

### Penyebab

`SmartOverlayController.persist()` mengirim profil yang sudah diubah ke callback layanan,
lalu **memakai nilai kembaliannya** sebagai profil aktif:

```kotlin
currentProfile = onProfileChanged(updated) ?: updated
```

Callback itu terhubung ke `SmartRepository.updatePoints(name, points)`, yang hanya mengganti
**daftar titik** dan mengembalikan profil **hasil baca ulang dari penyimpanan**. Jadi setiap
perubahan tingkat profil dibuang, dan `currentProfile` kembali ke nilai lama. Dialog digambar
ulang dengan pilihan lama → terlihat seperti kedip.

### Yang ikut terdampak (bukan cuma kecepatan)

| Pengaturan | Efek bug |
|---|---|
| Kecepatan (Rekomendasi/Cepat/Custom) | Pilihan balik ke semula |
| Jumlah putaran | Kembali ke nilai lama |
| Berhenti kalau diam | **Tidak bisa dimatikan** — ini penyebab "berhenti sendiri setelah 2 menit" pada rilis sebelumnya |
| Batas total klik | Kembali ke nilai lama |
| Angka Custom (interval, jeda, konfirmasi) | Kembali ke nilai lama |

### Perbaikan

- Metode baru `SmartRepository.updateProfile(profile)` menyimpan **seluruh** profil, bukan
  hanya titiknya, sambil mempertahankan nama profil dan penunjuk profil aktif.
- Callback layanan diubah memakai metode itu.
- `updatePoints()` tetap ada dan tidak diubah, karena masih dipakai jalur lain.

### Pemilihan kecepatan disederhanakan

Tiga baris panjang diganti **satu baris** `Kecepatan semua titik` yang membuka daftar pilihan
biasa (radio): Rekomendasi / Cepat / Custom. Pilih satu → langsung tersimpan, daftar tertutup,
baris di belakangnya menampilkan nilai baru, plus toast konfirmasi.

## Bug 2 — jeda klik dihitung per titik

### Penyebab

Angka kecepatan memang tersimpan di profil (global), **tetapi** penerapannya per titik:
`state.readyAtMs = nowMs + cooldownMs` untuk masing-masing titik.

Akibatnya dengan 3 titik, run mengklik sekitar **3× lebih cepat** dari kecepatan yang dipilih,
dan menambah titik membuat semuanya makin cepat. Kecepatan jadi tidak bisa dipegang.

### Perbaikan

Satu gerbang bersama `nextActionAtMs` di `SmartEngine`:

- Semua titik tetap membaca layar dan menghitung konfirmasi seperti biasa.
- Tidak ada titik yang boleh mengklik sebelum jeda bersama habis.
- Gerbang disetel setelah klik, setelah burst selesai, dan setelah klik gagal.

Hasil: jeda antar klik = angka yang dipilih, **berapa pun jumlah titiknya**.

`Berapa kali diklik` (burst per titik) tetap memakai jarak rapat 60 ms, karena itu memang
pengaturan per titik. Gerbang bersama mulai berlaku lagi setelah burst selesai.

Label dipertegas: judul bagian **"Kecepatan klik — semua titik"**, dan keterangannya menyebut
"Berlaku untuk semua N titik, bukan per titik."

## Hasil verifikasi

| Pemeriksaan | Hasil |
|---|---|
| Debug — smart | **156 test, 0 gagal** |
| Debug — home, MainActivity, import/export Android 12 | lulus |
| Debug — data, safety, diagnostics, overlay, accessibility | lulus |
| Rilis — smart | lulus |
| Lint release | **No issues found** |
| Total test dalam sumber | 252 |
| Package / versi | `com.richclickermacro` / code 22, 0.9.4 |
| zipalign | OK |
| Sertifikat | SHA-256 `087d9bac…6aac`, sama dengan v0.7.0–v0.9.3 |

### Test penjaga baru

- `SmartOverlayPersistTest` (16 test) — setiap pengaturan tingkat profil harus bertahan sesudah
  persist. Termasuk satu test yang **mendokumentasikan** bahwa `updatePoints` memang mengabaikan
  setting profil, supaya penyebab bug tidak dipasang lagi.
- `SmartGlobalSpeedTest` (8 test) — jeda antar klik harus sama dengan angka yang dipilih;
  menambah titik dari 1 ke 5 **tidak boleh** menambah jumlah klik per detik; Rekomendasi harus
  terbukti lebih lambat dari Cepat; burst dan klik gagal tetap menghormati gerbang bersama.

Satu test lama (`roundRobinPreventsOnePointFromStarvingAnother`) mengasumsikan jeda per titik.
Diganti `everyPointGetsItsTurnUnderTheGlobalSpeedGate` yang menguji perilaku baru: titik lain
menunggu jeda bersama, lalu tetap mendapat gilirannya.

## Catatan proses

Gradle daemon dua kali mati karena RAM sandbox (±2 GB) saat kelompok test digabung atau saat
daemon sebelumnya masih hidup. Dibersihkan dan dijalankan per kelompok; semua lulus.
Ini batas lingkungan, bukan cacat aplikasi.

## Yang tidak diubah

- **Macro Biasa** — tidak disentuh; seluruh test-nya lulus.
- Perbaikan koordinat v0.9.1 dan pengaman bentuk screenshot.
- Batas platform ±333 ms per screenshot, `FLAG_SECURE`, syarat API 30+.
- Schema penyimpanan tetap 3; file schema 1–2 tetap terimpor.

## Yang belum terverifikasi

1. Rasa kecepatan di perangkat nyata, terutama saat banyak titik aktif.
2. Tampilan daftar pilihan pada layar kecil dan mode gelap.
3. Penyebab force close Ninja Ability — masih belum diketahui.

## Workspace

Hanya satu APK yang tersisa:

```
/home/user/Rich-Clicker-Macro-v0.9.4-com.richclickermacro-release.apk
```
