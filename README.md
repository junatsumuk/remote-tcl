# Remote TCL (Android TV & Roku TV Wi-Fi Remote)

Aplikasi Android berbasis Kotlin untuk mengontrol TV TCL (Android TV, Google TV, dan Roku TV) melalui jaringan Wi-Fi lokal.

## ✨ Fitur Utama
1. **Pencarian Otomatis (Auto Discovery)**:
   - Menggunakan mDNS/NSD (`_androidtvremote2._tcp`, `_roku:ecp._tcp`) & Subnet Scanner untuk menemukan TV TCL dalam 1 Wi-Fi.
2. **Dukungan Dual Protokol**:
   - **TCL Android / Google TV**: Protokol TLS Socket & verifikasi PIN pairing.
   - **TCL Roku TV**: Protokol Roku ECP via HTTP API (Port 8060).
3. **Input IP Manual**:
   - Menghubungkan TV secara langsung menggunakan alamat IP.
4. **Navigasi & Kontrol Lengkap**:
   - D-Pad 4 Arah + Tombol OK di tengah.
   - Tombol Power, Back, Home, Menu.
   - Slider / Tombol Volume (+/-) & Channel (+/-).
   - Tombol Mute & Play/Pause.
   - Shortcut Cepat: Netflix, YouTube, Prime Video.
5. **Haptic Feedback**:
   - Getaran halus saat tombol ditekan untuk pengalaman sentuhan layaknya remote fisik asli.
6. **Otomatisasi Build APK via GitHub Actions**:
   - Setiap push dengan `push.bat` akan memicu build otomatis di GitHub Actions dan menghasilkan file `RemoteTCL-debug-apk` siap install.

---

## 🚀 Cara Menggunakan `push.bat` (Build APK via GitHub)

1. Jalankan file `push.bat`:
   ```bat
   push.bat
   ```
2. Versi aplikasi akan otomatis bertambah (misal `0.0.1` -> `0.0.2`).
3. Perubahan akan langsung di-*commit* dan di-*push* ke repository GitHub `main`.
4. Buka tab **Actions** di repository GitHub Anda untuk mengunduh file `.apk` yang selesai dibuat.

---

## 📱 Panduan Menghubungkan ke TV TCL

1. Pastikan **HP Android dan TV TCL terhubung ke Wi-Fi yang sama**.
2. Buka aplikasi **Remote TCL**.
3. Ketuk **Scan TV** atau **Pilih TV**:
   * Jika TV ditemukan, ketuk nama TV Anda.
   * Jika TV menampilkan **Kode PIN** di layar (khusus Android TV), masukkan kode tersebut ke dalam dialog PIN di aplikasi.
4. Remote TV siap digunakan!
