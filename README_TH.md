<p align="center">
  <img src="assets/hero-english.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Latest%20Release&color=111111" alt="Latest Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/wimi321/lizzieyzy-next/ci.yml?branch=main&label=CI&color=1f6feb" alt="CI"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/github/license/wimi321/lizzieyzy-next?color=5b5b5b" alt="License"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-6b7280" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README_ZH_TW.md">繁體中文</a> · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a> · ภาษาไทย
</p>

<p align="center">
  <strong>LizzieYzy Next คือเดสก์ท็อป GUI สำหรับรีวิวเกมโกะด้วย KataGo ที่ยังคงได้รับการดูแลต่อ และเป็นสายพัฒนาต่อจาก <code>lizzieyzy 2.5.3</code> ที่ใช้งานจริงอยู่ในตอนนี้</strong><br/>
  โปรเจกต์นี้เน้นแก้ปัญหาที่ผู้ใช้พบจริงบ่อยที่สุด: จะเลือกแพ็กเกจดาวน์โหลดอย่างไร, เริ่มต้นครั้งแรกให้ลื่นขึ้นอย่างไร, ดึงเกมจาก Fox ให้ใช้งานได้จริง, ทำให้ซิงก์กระดานบน Windows ใช้ได้จากในแพ็กเกจ และเข้าสู่การรีวิวทั้งกระดานได้เร็วขึ้น
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>ดาวน์โหลดเวอร์ชันเสถียร</strong></a>
  ·
  <a href="docs/INSTALL_EN.md"><strong>คู่มือติดตั้ง</strong></a>
  ·
  <a href="docs/PACKAGES_EN.md"><strong>คู่มือแพ็กเกจ</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>การแก้ปัญหา</strong></a>
  ·
  <a href="https://github.com/wimi321/lizzieyzy-next/discussions"><strong>Discussions</strong></a>
</p>

| สถานะโปรเจกต์ | ค่าปัจจุบัน |
| --- | --- |
| เวอร์ชันที่ผู้ใช้เห็น | `LizzieYzy Next 1.0.0` |
| ฐานต้นทาง | `lizzieyzy 2.5.3` |
| เอนจินเริ่มต้น | `KataGo v1.16.4` |
| น้ำหนักเริ่มต้น | `kata1-zhizi-b28c512nbt-muonfd2.bin.gz` |
| ช่องทางดาวน์โหลดทางการ | GitHub Releases |

> [!IMPORTANT]
> ช่องทางดาวน์โหลดสาธารณะอย่างเป็นทางการตอนนี้เหลือเพียง GitHub Releases
> Windows release ปกติจะมาพร้อม native `readboard.exe` และจะย้อนกลับไปใช้ `readboard_java` เฉพาะเมื่อ native helper หายไปหรือเปิดไม่ขึ้นเท่านั้น

## ทำไมโปรเจกต์นี้จึงน่าติดตาม

- นี่ไม่ใช่แค่ branch แก้ชั่วคราว แต่เป็นเวอร์ชันสาธารณะที่ดูแลเวิร์กโฟลว์ใช้งานจริงของ `lizzieyzy` ต่อเนื่อง
- ไม่ได้ดูแลแค่ซอร์สโค้ด แต่ดูแลแพ็กเกจ, ประสบการณ์เปิดครั้งแรก, หน้า release, เอกสารติดตั้ง และการตรวจสอบย้อนหลังไปพร้อมกัน
- ให้ความสำคัญกับการใช้งานจริง เช่น การดึงเกม, รีวิว SGF, ดูกราฟอัตราชนะ, วิเคราะห์ทั้งกระดาน และการเริ่มต้นกับการซิงก์บน Windows

## ความสามารถหลักในตอนนี้

| สิ่งที่คุณอยากทำ | ประสบการณ์ตอนนี้ |
| --- | --- |
| เริ่มใช้ได้เร็วหลังดาวน์โหลด | Windows, macOS และ Linux มีแพ็กเกจรวมพร้อมใช้งาน ผู้ใช้ส่วนใหญ่ไม่ต้องประกอบสภาพแวดล้อมเองก่อน |
| ดึงเกม Fox สาธารณะล่าสุด | ใส่ชื่อเล่น Fox แล้วโปรแกรมจะหาบัญชีที่ตรงให้โดยอัตโนมัติ |
| ใช้ Smart Optimize | ใช้แนวทาง benchmark ของ KataGo พร้อมความคืบหน้าที่มองเห็นได้, ยกเลิกได้, และหยุด/คืนการวิเคราะห์อัตโนมัติ |
| ใช้ซิงก์กระดานบน Windows | release ปกติจะใช้ native `readboard.exe` เป็นหลัก และค่อยกลับไป Java helper เมื่อจำเป็นจริง ๆ |
| ควบคุมโปรแกรมได้เร็วระหว่างโหลดเกม | การโหลด SGF และเกมจาก Fox จะคืนการควบคุมให้หน้าต่างหลักก่อน แล้วค่อยเติมรายละเอียดกราฟชนะทีหลัง |
| ติดตั้งบน macOS | DMG ทางการผ่านขั้นตอนเซ็นและ notarize ใน release pipeline |

## ควรดาวน์โหลดแพ็กเกจไหน

ดาวน์โหลดสาธารณะทั้งหมดอยู่ที่ [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases) ถ้าต้องการเลือกให้ถูกอย่างรวดเร็ว ตารางนี้ก็พอแล้ว

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| สถานการณ์ของคุณ | คีย์เวิร์ดไฟล์ที่ควรมองหาใน Releases |
| --- | --- |
| ผู้ใช้ Windows ส่วนใหญ่, ตัวแนะนำหลัก | `*windows64.opencl.portable.zip` |
| Windows, OpenCL ไม่เสถียร, ตัวเลือก CPU | `*windows64.with-katago.portable.zip` |
| Windows, มี NVIDIA GPU, ต้องการความเร็วมากขึ้น | `*windows64.nvidia.portable.zip` |
| Windows, ต้องการตั้งค่าเอนจินเอง | `*windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `*mac-arm64.with-katago.dmg` |
| macOS Intel | `*mac-amd64.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

หมายเหตุ:

- ถ้าผู้ใช้ Windows ต้องการตัวติดตั้ง สามารถเลือกแพ็กเกจ `*.installer.exe` ที่ตรงกันได้
- หากต้องการดูทรัพย์สินสาธารณะทั้ง 11 รายการและสิ่งที่บรรจุอยู่ในแต่ละแพ็กเกจ ให้ดู [docs/PACKAGES_EN.md](docs/PACKAGES_EN.md)
- Windows release ปกติมี helper สำหรับซิงก์กระดานแบบ native รวมมาแล้ว

## จุดเด่นของเวอร์ชันสาธารณะปัจจุบัน

- `ดึงเกมด้วยชื่อเล่น Fox`
  ไม่ถือว่าผู้ใช้ทั่วไปต้องรู้เลขบัญชี Fox ก่อนอีกต่อไป
- `KataGo Auto Setup`
  แพ็กเกจหลักมี `KataGo v1.16.4` และน้ำหนักเริ่มต้นรวมมาให้ ส่วน Smart Optimize ใช้การปรับตาม benchmark และยกเลิกได้
- `เส้นทางซิงก์กระดานบน Windows ที่แข็งแรงขึ้น`
  แพ็กเกจ release บรรจุ `readboard.exe` และไฟล์พึ่งพามาให้ พร้อม fallback ไป Java เฉพาะตอนจำเป็น
- `ประสบการณ์โหลดเกมที่ตรงไปตรงมาขึ้น`
  หลังดาวน์โหลดเกมเสร็จ หน้าต่างหลักจะกลับมาควบคุมได้ก่อน แล้วรายละเอียดอัตราชนะค่อยเติมต่อ
- `การออก release แบบโปรเจกต์เดสก์ท็อปจริง`
  มีการดูแลแพ็กเกจข้ามแพลตฟอร์ม, CI, release notes และเอกสารติดตั้งเป็นส่วนหนึ่งของตัวผลิตภัณฑ์

## เริ่มต้นอย่างรวดเร็ว

1. ไปที่ [Releases](https://github.com/wimi321/lizzieyzy-next/releases) แล้วดาวน์โหลดแพ็กเกจที่เหมาะกับระบบของคุณ
2. ถ้าใช้ Windows bundle ที่มี KataGo ในตัว ให้เปิด `KataGo Auto Setup` แล้วรัน `Smart Optimize` สักครั้ง
3. เปิด SGF ในเครื่อง หรือใช้ขั้นตอนดึงเกมสาธารณะจากชื่อเล่น Fox
4. ใช้กราฟ, ปุ่ม `Down` และการนำทางด้วยคีย์บอร์ด เพื่อดูจังหวะสำคัญระหว่างที่ข้อมูลรีวิวส่วนที่เหลือค่อย ๆ เติมเข้ามา

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Fox nickname demo" width="100%" />
  </a>
</p>

## ภาพตัวอย่างหน้าตาโปรแกรม

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next interface preview" width="100%" />
</p>

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next winrate graph and quick overview" width="52%" />
</p>

อินเทอร์เฟซปัจจุบันสามารถมองได้เป็น 3 ชั้นของข้อมูล:

- พื้นที่กระดาน: ตำแหน่งปัจจุบัน, จุดแนะนำ, และการอ่านเฉพาะจุด
- กราฟอัตราชนะ: แนวโน้มทั้งกระดานและจุดเปลี่ยนสำคัญ
- ภาพรวมแบบเร็วด้านล่าง: ช่วยบอกว่าควรย้อนกลับไปดูช่วงไหนก่อนที่จะไล่ทุกหมากละเอียด

## เอกสารและชุมชน

- [คู่มือติดตั้ง](docs/INSTALL_EN.md)
- [คู่มือแพ็กเกจ](docs/PACKAGES_EN.md)
- [การแก้ปัญหา](docs/TROUBLESHOOTING_EN.md)
- [แพลตฟอร์มที่ทดสอบแล้ว](docs/TESTED_PLATFORMS.md)
- [บันทึกการเปลี่ยนแปลง](CHANGELOG.md)
- [แผนงาน](ROADMAP.md)
- [การมีส่วนร่วม](CONTRIBUTING.md)
- [การขอความช่วยเหลือ](SUPPORT.md)
- [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions)
- กลุ่ม QQ ภาษาจีน: `299419120`

## สร้างจากซอร์ส

สิ่งที่ต้องมี:

- JDK 17
- Maven 3.9+

คำสั่ง build:

```bash
mvn test
mvn -DskipTests package
java -jar target/lizzie-yzy2.5.3-shaded.jar
```

ถ้าคุณจะดูแลแพ็กเกจ, release หรือกระบวนการอัตโนมัติด้วย ให้ดูเพิ่มเติมที่:

- [docs/DEVELOPMENT_EN.md](docs/DEVELOPMENT_EN.md)
- [docs/MAINTENANCE_EN.md](docs/MAINTENANCE_EN.md)
- [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)

## เครดิต

- โปรเจกต์ต้นฉบับ: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- อ้างอิงประวัติการดึงเกมจาก Fox: [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest), [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)

## สัญญาอนุญาต

โปรเจกต์นี้เผยแพร่ภายใต้ [GPL-3.0](LICENSE.txt)
