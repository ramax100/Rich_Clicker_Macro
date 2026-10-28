# Bug Audit — Klik Stabil v0.6.2

## Diperbaiki

- Data profil JSON korup dicadangkan sebelum profil Recovery digunakan.
- Migrasi interval lama kini juga mengisi jeda antar-loop.
- Long press/swipe dibagi menjadi segmen maksimal 500 ms; tombol `■` membatalkan kelanjutan segmen.
- Dialog delay/durasi/repeat ditutup sebelum macro dimulai, termasuk dari Quick Settings Tile.
- State toolbar collapse dan hide marker direset saat editor ditutup.
- Pusat tap/swipe dapat mencapai edge layar; marker boleh setengah berada di luar layar.
- Foldable/windowed memakai current WindowMetrics, bukan maximum metrics.
- Import JSON dibatasi 2 MB.
- Draft form bertahan saat Activity dibuat ulang dan disimpan sebelum berpindah profil.
- Cleanup overlay mengenali view yang sudah detached dan menggunakan removeViewImmediate.
- Delay/durasi dikuantisasi ke presisi 100 ms agar tampilan dan nilai eksekusi konsisten.

## Diverifikasi

- Seluruh source Kotlin dikompilasi terhadap Android API 35.
- Resource dan manifest berhasil di-link.
- DEX berhasil dibuat.
- APK zip-aligned dan signature v2/v3 valid.
- Integritas ZIP APK valid.

## Batasan tersisa

- Android tidak menyediakan API publik untuk membatalkan stroke Accessibility yang sedang aktif secara langsung. Segmentasi membatasi waktu tunggu penghentian hingga sekitar 500 ms.
- Perilaku overlay tetap perlu diuji pada perangkat/OEM nyata, terutama foldable, desktop mode, dan cutout khusus.
- Jumlah titik tidak dibatasi aplikasi tetapi tetap dibatasi memori dan WindowManager perangkat.
- Backup data korup dipertahankan secara internal; belum ada layar pemulihan manual untuk mengedit JSON backup.
- Belum ada pengujian instrumentasi perangkat karena sandbox tidak memiliki emulator/perangkat Android.
