---
inclusion: always
---

# APK Artifact Management

- Letakkan APK final langsung di root workspace: `/projects/sandbox/`.
- Pertahankan tepat satu APK final agar pengguna tidak salah memilih file.
- Sebelum menghasilkan APK baru, hapus APK versi sebelumnya dari root dan semua salinan lama dari direktori build.
- Gunakan nama yang jelas dan berversi, misalnya `Klik-Stabil-v0.2.0-release.apk`.
- Setelah menyalin APK ke root, verifikasi checksum, integritas arsip, zip alignment, dan signature sebelum menghapus hasil sementara.
