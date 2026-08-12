# Ling Offline Automation V2.2 Debug

Prototype Android end-to-end untuk game offline.

## Yang baru di V2

- Detector HSV ungu/magenta ditulis langsung di Kotlin.
- Tidak memakai OpenCV.
- Downscale frame sebelum deteksi agar lebih ringan.
- Connected-components 8-neighbor.
- Filter bentuk sword vertikal.
- Formation score untuk membuang kandidat efek animasi.
- Harus stabil 3 frame berturut-turut.
- Route optimal untuk 4 sword.
- Auto execution menggunakan AccessibilityService.
- Auto execution OFF secara default setiap aplikasi dibuka.
- Cooldown 2.2 detik agar satu formasi tidak dieksekusi berkali-kali.
- MediaProjection callback sudah didaftarkan sebelum VirtualDisplay.

## Flow

MediaProjection
-> Bitmap frame
-> HSV threshold
-> connected components
-> 4 kandidat
-> formation score
-> stabil 3 frame
-> optimal route
-> Accessibility dispatchGesture

## Pengujian

1. Build dan install aplikasi.
2. Buka Accessibility Settings.
3. Aktifkan Ling Offline Automation V2.
4. Kembali ke aplikasi.
5. Tekan START SCREEN CAPTURE.
6. Setujui dialog capture Android.
7. Buka game OFFLINE target.
8. Kembali ke aplikasi bila perlu dan aktifkan AUTO EXECUTION: ON.
9. Jalankan kondisi game yang memunculkan 4 sword.

## Tuning utama

ScreenCaptureService.kt
- REQUIRED_STABLE_FRAMES = 3
- EXECUTION_COOLDOWN_MS = 2200
- processIntervalMs = 140
- stepDelay = 300

SwordDetector.kt
- HSV hue 250..350
- saturation >= .31
- value >= .47
- formation minimum dipakai di ScreenCaptureService: 0.58

Jika detector miss, threshold perlu dikalibrasi ulang berdasarkan screenshot dari game offline target.


Lihat `BUILD_V22_DEBUG.md` untuk langkah build, install, dan pengambilan Logcat.
