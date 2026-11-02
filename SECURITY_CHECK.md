# Pemeriksaan kredensial terbuka

Tanggal: 2026-11-02 · Cakupan: repository `ramax100/Rich_Clicker_Macro`

Repository ini **publik**. Karena itu setiap berkas yang pernah di-commit tetap bisa dibaca
siapa pun selamanya, walaupun nanti dihapus. Pemeriksaan di bawah menelusuri bukan hanya
kondisi sekarang, tetapi **seluruh riwayat** di semua branch.

## Hasil ringkas

**Tidak ada kredensial yang terbuka.** Tidak ada keystore, kata sandi, token, atau kunci
privat yang pernah masuk ke repository.

## Yang diperiksa dan hasilnya

| Pemeriksaan | Hasil |
|---|---|
| Nama berkas sensitif yang pernah di-commit | **Tidak ada** |
| Isi seluruh objek git (155 blob, semua branch) | Tidak ada nilai kredensial |
| Branch `fix/remaining-bug-audit` (41 blob) | Bersih |
| Berkas keystore / `.p12` / `.pem` / `.key` | Tidak pernah ada |
| `signing/keystore.properties`, `signing/KEY-PASSWORD.txt` | Tidak pernah di-commit |
| `local.properties` (berisi jalur SDK) | Tidak pernah di-commit |
| Berkas `.env`, token, atau kata sandi di dokumentasi | Tidak ada |
| APK rilis v0.9.5 dan APK lama di riwayat | Tidak ada berkas kredensial di dalamnya |
| Token akses di `.git/config` atau berkas kerja | Tidak ada |
| Workflow CI (`.github/`) | Tidak ada |

### Konfigurasi signing

`app/build.gradle.kts` **tidak** menyimpan kata sandi. Nilainya dibaca dari berkas
`signing/keystore.properties` yang tidak dilacak git:

```kotlin
val keystorePropertiesFile = rootProject.file("signing/keystore.properties")
```

`.gitignore` sudah menutup ketiga berkas sensitif:

```
signing/*.jks
signing/KEY-PASSWORD.txt
signing/keystore.properties
```

Sudah diuji dengan `git check-ignore`: ketiganya benar-benar diabaikan.

### Yang ditemukan saat memindai riwayat

Pemindaian menemukan **nama** berkas dan variabel, bukan nilainya — misalnya
`signing/KEY-PASSWORD.txt` yang disebut di dokumentasi, atau
`System.getenv("ANDROID_KEYSTORE_PASSWORD")` pada versi build lama. Itu petunjuk letak,
bukan kredensial. Tidak ada satu pun nilai kata sandi, token, atau kunci privat.

### Alamat surel pada commit

Ketiganya memakai alamat `noreply` GitHub atau alamat umum, bukan surel pribadi:

```
Rama Rich <42316260+ramax100@users.noreply.github.com>
Kiro Agent <244629292+kiro-agent@users.noreply.github.com>
Arena Agent <agent@arena.ai>
```

## Yang tidak bisa saya periksa

Token yang saya pakai hanya punya izin **Contents**. GitHub menolak permintaan ke
pengaturan sensitif, jadi dua hal berikut **belum terverifikasi**:

- **Secrets pada GitHub Actions** (403). Meski begitu, repository ini tidak punya satu pun
  berkas workflow, jadi secrets Actions tidak dipakai apa pun.
- **Peringatan secret scanning** (403). Buka sendiri:
  `Settings → Code security → Secret scanning`.

Batas izin token itu sebenarnya kabar baik: token tersebut tidak bisa mengubah pengaturan
repository maupun membaca secrets.

## Yang perlu Anda lakukan

1. **Revoke token yang Anda kirim lewat chat.** Token itu masih aktif dan sudah tertulis di
   percakapan, jadi anggap sudah bocor. Buat token baru bila masih perlu push.
   → `github.com/settings/tokens?type=beta` → Delete.
2. **Aktifkan secret scanning dan push protection** (gratis untuk repository publik) di
   `Settings → Code security`. Fungsinya menolak commit yang mengandung kunci sebelum
   sempat masuk.
3. Kalau ingin benar-benar rapi: aktifkan juga **2FA** pada akun GitHub.

## Catatan pemisahan berkas

Berkas kunci **tidak** ada di GitHub, tetapi ada di workspace proyek ini:

```
signing/richclickermacro-release.jks
signing/keystore.properties
signing/KEY-PASSWORD.txt
```

Ketiganya dibutuhkan untuk membangun APK rilis dan sudah di-ignore git. Simpan salinan
cadangan di tempat aman (bukan repository publik). Kalau berkas ini hilang, APK versi
berikutnya tidak bisa dipasang sebagai pembaruan di atas versi lama, karena Android
mewajibkan kunci tanda tangan yang sama.
