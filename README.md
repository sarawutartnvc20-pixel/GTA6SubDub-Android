# GTA6SubDub Android (ถาวร ไม่มีหมดอายุ 7 วัน)

แอป Android Native (Kotlin) สำหรับแปลซับไตเติล GTA 6 แบบเรียลไทม์ + พากย์เสียงภาษาไทยขณะเล่น PS Remote Play

## วิธี Build ไฟล์ `.apk` ด้วย GitHub Actions

1. สร้าง GitHub Repository ใหม่ แล้วนำไฟล์ทั้งหมดในโฟลเดอร์ `GTA6SubDub-Android/` (รวมโฟลเดอร์ `.github/`) ขึ้นไปที่ root ของ Repo
2. เปิดแท็บ **Actions** → กดรัน **Build Android APK**
3. รอประมาณ 2–3 นาที แล้วดาวน์โหลด Artifact ชื่อ **`GTA6SubDub-apk`** (แตก zip จะได้ไฟล์ `GTA6SubDub.apk`)

## วิธีติดตั้งลงมือถือ Android

1. ส่งไฟล์ `GTA6SubDub.apk` เข้ามือถือ Android
2. กดเปิดไฟล์ `.apk` แล้วเลือก **ติดตั้ง (Install)** *(หากระบบถามเรื่องติดตั้งแอปจากแหล่งที่ไม่รู้จัก ให้กดอนุญาตสำหรับตัวจัดการไฟล์/เบราว์เซอร์นั้น)*
3. เปิดแอป **GTA6 SubDub**:
   - กรอก **Gemini API Key**, **ElevenLabs API Key**, **ElevenLabs Voice ID** แล้วกด **บันทึกการตั้งค่า**
   - กด **1. อนุญาตแสดงหน้าต่างลอย (Overlay)** → เปิดสิทธิ์ให้แอป
   - กด **2. เริ่มแปลซับไตเติล + พากย์เสียง** → กด **Start now**
   - สลับไปเปิดแอป **PS Remote Play** ได้เลย (กล่องซับภาษาไทยลากย้ายตำแหน่งบนจอได้)
