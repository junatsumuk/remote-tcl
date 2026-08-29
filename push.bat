@echo off
setlocal enabledelayedexpansion

:: Cek apakah version.md ada, jika belum inisialisasi dengan 0
if not exist version.md (
    (echo 0)>version.md
)

:: Baca nilai dari version.md
set /p VER_NUM=<version.md
set VER_NUM=!VER_NUM: =!

:: Increment versi (+1)
set /a NEW_VER_NUM=VER_NUM+1

:: Simpan nilai baru ke version.md
(echo !NEW_VER_NUM!)>version.md

:: Format versi menjadi 0.0.{nilai}
set FULL_VERSION=0.0.!NEW_VER_NUM!

echo ============================================================
echo [AUTO INCREMENT] Versi Baru: !FULL_VERSION!
echo ============================================================

git add .
git commit -m "update v!FULL_VERSION!"
git push -u origin main

echo.
echo ============================================================
echo Selesai push! GitHub Action akan mem-build APK !FULL_VERSION!
echo ============================================================
pause
