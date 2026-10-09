# Rich Clicker Macro

Macro auto clicker Android multi-aksi dengan profil persisten. Editor menggunakan Accessibility Overlay sehingga tetap tampil dan macro tetap berjalan ketika pengguna berpindah aplikasi.

## Fitur versi 0.5 / 0.6.3

### Branding v0.6.3
- Nama aplikasi berubah menjadi **Rich Clicker Macro** tanpa mengganti package/signing key, sehingga tetap dapat memperbarui versi lama.
- Tombol STOP darurat diperkecil menjadi 48×36 dp.
- Foto karakter pengguna digunakan sebagai launcher icon dan logo halaman utama.

### Perbaikan audit v0.6.2
- Data profil JSON yang rusak dicadangkan sebelum profil Recovery digunakan, sehingga data mentah tidak langsung hilang.
- Interval lama ikut menjadi jeda antar-loop saat migrasi profil satu titik.
- Long press/swipe panjang dibagi menjadi segmen maksimal 0,5 detik sehingga STOP dapat menghentikan kelanjutan aksi dengan latensi terbatas.
- Dialog editor ditutup sebelum macro dimulai dari toolbar atau Quick Settings Tile.
- State collapse/hide toolbar direset saat editor ditutup.
- Pusat gesture dapat mencapai tepi layar dengan membiarkan setengah marker tetap terlihat.
- Foldable/windowed menggunakan current WindowMetrics, bukan gabungan area maksimum yang mungkin tidak dapat dijangkau.
- Import JSON dibatasi 2 MB untuk mencegah freeze/OOM.
- Draft pengaturan dipertahankan saat Activity dibuat ulang dan disimpan sebelum berpindah profil.
- Cleanup overlay membedakan view yang sudah terlepas agar tidak tersangkut permanen.

### Perbaikan kompatibilitas v0.6.1
- Batas drag memakai WindowMetrics penuh pada Android 11+ dan real display metrics pada Android 8–10.
- Marker dan endpoint dapat ditarik sampai tepi kanan/bawah pada perangkat dengan resource metrics terbatas.
- Toolbar dibatasi maksimal 260 dp dan enam kontrol utama dibagi menjadi dua baris agar tidak lebih lebar dari layar.
- Toolbar dijepit ke batas layar selama digeser, bukan baru saat dilepas, sehingga tidak memantul ke kiri.

### Eksekusi
- Tap, long press, dan swipe per titik.
- Durasi long press/swipe custom 0,1–60 detik; long press minimal 0,6 detik.
- Jumlah aksi per titik 1–1.000 kali.
- Delay per titik dan jeda antar-loop custom.
- Batas loop atau loop tanpa batas.
- Countdown dan tombol STOP darurat.
- Keep Screen On opsional per profil tanpa izin wake lock.

### Editor overlay
- Titik tanpa batas jumlah buatan dengan pusat gesture presisi.
- Endpoint swipe berwarna biru dan berlabel `E`, dapat digeser terpisah.
- Reorder titik, input delay langsung, toolbar movable/collapsible.
- Tombol `◉/○` untuk menyembunyikan atau menampilkan seluruh marker dan endpoint.
- Posisi awal/akhir tersimpan sebagai koordinat relatif untuk rotasi layar.
- Marker menjadi tidak dapat disentuh saat macro berjalan agar gesture diteruskan ke aplikasi di bawahnya.

### Profil dan akses cepat
- Buat, rename, pilih, hapus, serta autosave profil.
- Import/export JSON schema v2; profil schema v1 tetap dapat diimpor.
- Quick Settings Tile **Rich Clicker Macro** untuk mulai/berhenti macro yang sudah dimuat.
- Jika belum ada profil dimuat, tile membuka aplikasi.
- Panel utama dibagi menjadi Layanan, Profil, Eksekusi, Editor Macro, dan Data Profil.

## Cara menggunakan

1. Pasang APK dan aktifkan **Rich Clicker Macro** di Pengaturan Aksesibilitas.
2. Tambahkan tile **Rich Clicker Macro** melalui editor Quick Settings Android jika ingin akses cepat.
3. Pilih profil dan atur eksekusi, termasuk Keep Screen On.
4. Tekan **Tampilkan Editor di Atas Aplikasi**, lalu buka aplikasi tujuan.
5. Pilih titik dan ketuk tombol jenis aksi untuk berpindah `Tap → Tahan → Geser`.
6. Untuk swipe, geser marker awal dan marker biru `E` sebagai tujuan.
7. Ketuk durasi dan `×N` untuk memasukkan nilai langsung.
8. Gunakan `◉/○` untuk hide/show marker, `▶` untuk mulai, serta `■` atau STOP untuk berhenti.

## Semantik waktu

- Pengulangan aksi pada titik yang sama memiliki jeda internal singkat 0,1 detik.
- Setelah seluruh pengulangan titik selesai, delay titik diterapkan sebelum titik berikutnya.
- Setelah titik terakhir selesai, jeda antar-loop menggantikan delay titik terakhir.
- Durasi long press/swipe adalah waktu gesture, lalu delay baru dihitung setelah callback selesai.

## Keamanan dan stabilitas

- Tile tidak mencoba mengaktifkan Accessibility Service sendiri.
- Keep Screen On dilepas ketika berhenti, dibatalkan, selesai, atau layanan ditutup.
- Callback generasi mencegah gesture lama melanjutkan macro setelah stop.
- Dialog editor ditutup saat profil diganti atau layanan dihentikan.
- Tidak membaca isi layar (`canRetrieveWindowContent=false`) dan tidak auto-start.
- APK sideload dengan Accessibility Service dapat diperiksa Play Protect; jangan menonaktifkan proteksi perangkat.
