# Ling Offline Automation V2.6 Direct Frame

Package:
`com.lingoffline.automation.v26`

Perubahan:
- baca ImageReader ByteBuffer langsung
- tidak membuat Bitmap full-size per frame
- target analisis 25 FPS / 40 ms
- hero dicari dari green HP bar setiap frame
- fallback center hanya jika HP bar tidak ditemukan
- gesture Skill 2 68 ms
- redetect 52 ms setelah gesture selesai
- arah Skill 2 memakai hero aktual

Workflow:
`.github/workflows/build-apk-v26-direct-frame.yml`

Artifact:
`LingOfflineAutomationV26-DirectFrame`

Install:
    adb install app-debug.apk

Live log:
    adb logcat -s LingAutoCapture:D LingAutoDetector:D LingAutoGesture:D *:S

Perhatikan:
- heroSource=HP
- heroConf=...
- SEQUENCE_START V2.6
- SKILL2_VECTOR hero=(...) target=(...)
- DASH#1 COMPLETED
- DASH#2 COMPLETED
- DASH#3 COMPLETED
- DASH#4 COMPLETED
