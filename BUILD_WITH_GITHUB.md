# Ling Offline Automation V2 - GitHub Build Ready

Project ini sudah ditambahkan workflow GitHub Actions agar APK bisa dibuild tanpa Android Studio.

## Cara build APK

### 1. Buat repository baru di GitHub
Repository kosong saja. Public atau private sama-sama bisa untuk proses build sesuai akun GitHub kamu.

### 2. Upload ISI folder project
Upload semua isi folder `LingOfflineAutomationV2`, termasuk folder tersembunyi:

`.github/workflows/build-apk.yml`

Pastikan file `settings.gradle.kts` berada di root repository, bukan satu folder terlalu dalam.

Struktur root yang benar:

.github/
app/
build.gradle.kts
settings.gradle.kts
README.md

### 3. Commit ke branch main
Workflow disetel berjalan otomatis saat ada push ke `main` atau `master`.

### 4. Buka tab Actions
Pilih workflow:

Build Android APK

Kalau belum otomatis jalan, tekan:

Run workflow

### 5. Tunggu build selesai
Jika berhasil, job akan berwarna hijau.

### 6. Download APK
Buka hasil run, lalu pada bagian Artifacts download:

LingOfflineAutomationV2-debug

ZIP artifact dari GitHub berisi:

app-debug.apk

Itulah APK yang dapat dipasang di Android.

## Jika build error

Buka:
Actions > Build Android APK > build > langkah yang merah

Salin teks error tersebut. Error build jauh lebih berguna daripada screenshot halaman merah tanpa log.

## Konfigurasi build

- Java 17
- Gradle 8.10.2
- Android Gradle Plugin 8.7.3
- compileSdk 35
- targetSdk 35
- minSdk 26

## Catatan keamanan penggunaan

Project ditujukan untuk game offline yang memang ingin kamu otomatisasi.
Auto execution tetap OFF saat aplikasi pertama kali dibuka dan harus diaktifkan manual.


V2.6: gunakan `BUILD_V25_FAST_ADAPTIVE.md`.
