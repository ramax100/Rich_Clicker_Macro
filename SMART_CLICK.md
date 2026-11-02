# Multi Smart Click (v0.9.5)

Fitur **terpisah** dari Macro Biasa. Macro Biasa tidak diubah.

Daftar fitur di bawah adalah daftar yang ditetapkan pengguna. Tidak ada menu tersembunyi.

## Cara pakai

1. Tekan **＋ Tambah Titik**, lalu ketuk bagian layar yang mau diklik. Warnanya langsung dibaca.
2. Tekan **▶ MULAI**.

Titik dijalankan **berurutan**: titik 1 → 2 → … → terakhir, lalu ulang dari titik 1.
Bawaan **tanpa batas** sampai ditekan **■ STOP**.

## ⚙ → Atur Titik Ini

| Baris | Keterangan |
|---|---|
| **Ambil warna ulang** | Baca ulang warna di titik ini dari layar sekarang. Lakukan saat tombolnya sedang siap. |
| **Berapa kali diklik** | Jumlah klik tiap kali warnanya cocok. `1` = sekali. Bisa diisi **1–1000**. |
| **Tahan tekan lama** | **OFF** = sentuh singkat (ketuk biasa). **ON** = tekan dan tahan. |
| **Lama menahan** | Muncul hanya saat Tahan = ON. 600–10000 ms. Bawaan 700 ms. |
| **Kepekaan warna** | `±1`–`±120`, bawaan `±24`. Tidak mau klik → naikkan. Klik di waktu salah → turunkan. |
| **Hapus titik ini** | Ada konfirmasi dulu. |

Dua baris tambahan muncul **hanya** kalau profil lama memang memakainya, supaya tidak ada
setelan lama yang jadi tidak bisa diubah: **Kapan diklik** (untuk profil "warna berubah")
dan **Titik ini** (untuk titik yang pernah dimatikan).

## ⚙ → Loop & Kecepatan

### Loop berulang

| Baris | Keterangan |
|---|---|
| **Ulang dari titik 1 sampai terakhir** | **Tanpa batas** (bawaan), atau isi jumlah putaran. `0` = tanpa batas, maksimal 1.000.000. |

Satu putaran = semua titik selesai berurutan. Setiap langkah tetap menunggu warna titiknya
cocok, bukan menunggu hitungan detik — jadi kalau game loading lama, ia menunggu.

### Akurasi: titik yang dipantau = titik yang diklik

Tidak ada lagi pengaturan "pantau di sini, klik di sana". Setiap titik selalu mengklik
tempat ia memantau.

Pengaman yang aktif saat menjalankan:

- Titik yang tertutup toolbar ditolak sebelum run dimulai.
- Dua titik yang terlalu berdekatan ditolak, karena akan membaca area yang sama.
- Kalau ukuran tangkapan layar berbeda lebih dari **1%** dari layar sebenarnya, smart click
  berhenti dengan pesan — lebih baik berhenti daripada mengklik tempat yang salah.
- Kalau layar berubah (rotasi/ukuran), run berhenti dan meminta titik diperiksa ulang.
- Id titik selalu unik walau berkas impor berisi id kembar, supaya tidak ada dua titik yang
  berbagi satu area pantau.

## Kecepatan klik — berlaku untuk SEMUA titik

Satu pilihan mengatur seluruh titik sekaligus. Jeda antar klik dihitung untuk seluruh run,
bukan per titik — jadi menambah titik **tidak** membuat klik makin cepat.

| Pilihan | Baca layar | Konfirmasi | Jeda antar klik | Keterangan |
|---|---|---|---|---|
| **Rekomendasi** (bawaan) | 400 ms | 2× | 600 ms | Pakai ini dulu. Warna diperiksa dua kali sebelum klik, jadi tidak mudah salah picu. |
| **Cepat** | 400 ms | 1× | 300 ms | Langsung klik begitu warna cocok. Lebih cepat, tapi lebih mudah salah picu kalau warna mirip-mirip. |
| **Custom** | isi sendiri | isi sendiri | isi sendiri | Membuka tiga kolom: baca layar setiap, jeda antar klik, warna harus cocok. |

Kalau angka Custom diubah manual, pilihan otomatis pindah ke **Custom** supaya labelnya
tidak berbohong.

Cara memilih: **⚙ → Loop & Kecepatan → Kecepatan semua titik**, lalu ketuk salah satu pilihan.
Pilihan langsung tersimpan dan tampil di baris itu.

### Pengaman

| Baris | Keterangan |
|---|---|
| **Berhenti kalau diam** | Bawaan 120 detik. Kalau tidak ada warna yang cocok selama itu, berhenti sendiri. **Isi `0` supaya jalan terus** walau menunggu lama. |
| **Batas total klik** | `0` = tanpa batas. |

## ⚙ → Tes Klik Titik Ini

Mengirim **satu klik** ke titik terpilih tanpa memeriksa warna. Pakai ini untuk memastikan
posisinya benar sebelum sesi panjang.

## Sebelum mulai

- **Geser toolbar menjauh** dari titik yang ditandai. Kalau tertutup, aplikasi menolak membaca
  warna — kalau tidak, yang terbaca justru toolbar sendiri.
- Jangan putar layar setelah menandai titik. Kalau berputar, aplikasi berhenti dan titik
  perlu ditandai ulang.
- Satu titik **selalu mengklik di tempat ia memantau**. Fitur "pantau di sini, klik di sana"
  sudah dihapus sejak v0.9.2.

## Batasan yang jujur

1. **Tidak bisa lebih cepat dari ±3 klik/detik.** Android membatasi pengambilan screenshot
   ±333 ms sekali. Untuk klik cepat tanpa syarat, **Macro Biasa** lebih tepat. Kelebihan smart
   bukan kecepatan, tapi **ketepatan waktu**.
2. **Layar ber-`FLAG_SECURE` tidak bisa dibaca** (mis. halaman pembayaran). Ini proteksi Android.
3. Butuh **Android 11 (API 30) ke atas** dan layanan aksesibilitas dengan izin screenshot.
4. Yang dibaca adalah **kotak 6×6 piksel** lalu dirata-rata, bukan satu piksel.
5. `Berapa kali diklik` mengirim klik berurutan dengan jarak 60 ms **tanpa membaca layar lagi**
   di antaranya, karena klik pertama biasanya sudah mengubah tampilan.

## Kompatibilitas

- Format penyimpanan naik ke **schema 3** (menyimpan pilihan kecepatan).
- File **schema 1 dan 2 tetap bisa diimpor**. Angka waktu yang tersimpan **tidak diubah**.
  Label kecepatannya dibaca dari angka itu: cocok preset Cepat → "Cepat", cocok Rekomendasi
  → "Rekomendasi", selain itu → "Custom".
- Profil lama yang memakai urutan "Bebas" otomatis jadi berurutan, karena loop hanya punya
  arti kalau urutannya jelas.
- Kolom lama `separateTap`/`tapX`/`tapY` diabaikan; titik mengklik di posisi pantau.

## Hasil pemeriksaan v0.9.5

| Pemeriksaan | Hasil |
|---|---|
| Debug — smart | 166 test, 0 gagal |
| Debug — home + MainActivity | lulus |
| Debug — data, safety, diagnostics | lulus |
| Debug — overlay, accessibility, import/export | lulus |
| Rilis — smart | lulus |
| Lint release | No issues found |

**Belum diuji di perangkat nyata.** Gunakan **Tes Klik Titik Ini** sebelum sesi panjang.

Rincian audit: [AUDIT_0.9.5.md](./AUDIT_0.9.5.md)
