1. เตรียมเครื่องก่อนเริ่ม
    1.1 ติดตั้ง Java JDK
        ดาวน์โหลด JDK 17 หรือใหม่กว่า:
            Adoptium Temurin
            Oracle JDK

        ตรวจสอบเวอร์ชัน:
        java -version

    ควรได้ผลลัพธ์ 17.x ขึ้นไป

    1.2 ติดตั้ง Docker
        Windows/Mac: ดาวน์โหลดจาก Docker Desktop
        Linux (Ubuntu/Debian):
            sudo apt update
            sudo apt install docker.io -y
            sudo systemctl enable --now docker


        ตรวจสอบว่า Docker พร้อมใช้งาน:
            docker --version

    1.3 ตั้งค่า Firewall (เฉพาะถ้ารันข้ามเครื่อง)
        หาก Node หลายเครื่องต้องเชื่อม Redis เครื่องเดียว ต้องเปิดพอร์ต 6379

        Windows (PowerShell as Admin):
            New-NetFirewallRule -DisplayName "Allow Redis" -Direction Inbound -Protocol TCP -LocalPort 6379 -Action Allow


        Linux (UFW):
            sudo ufw allow 6379/tcp
            sudo ufw reload

        MacOS: ไปที่ System Settings > Network > Firewall > Add Rule > เปิด TCP port 6379

2. รัน Redis ด้วย Docker
    บนเครื่องที่ต้องการให้เป็น Redis Server รันคำสั่งนี้:
        docker run -d --name redis -p 6379:6379 -v redis-data:/data redis:7 redis-server --appendonly yes --requirepass "12345" --bind 0.0.0.0


    อธิบาย:
    -p 6379:6379 = เปิดพอร์ต Redis ออกนอกเครื่อง
    -v redis-data:/data = เก็บข้อมูล redis ถาวร
    --requirepass "12345" = ตั้งรหัสผ่าน (เปลี่ยนได้ตามต้องการ)
    --bind 0.0.0.0 = อนุญาตการเชื่อมต่อจากภายนอก

    ตรวจสอบว่า Redis ทำงานแล้ว:
    docker ps


    ควรเห็น container ชื่อ redis รันอยู่

3. Build โปรเจกต์
    3.1 แตกไฟล์ ZIP
        unzip IPC.zip -d IPC
        cd IPC

    3.2 Build ด้วย Gradle
        ./gradlew clean shadowJar


    ผลลัพธ์จะอยู่ที่:
    build/libs/redis-pubsub-demo-all.jar

4. การรันโปรแกรม
    4.1 รัน Node (ในเครื่องใดๆ ที่มี Java + เชื่อม Redis ได้)
        java -jar build/libs/redis-pubsub-demo-all.jar --host <IP ของเครื่องที่รัน Docker Redis> --port 6379 --name node-A --pass 12345


    ตัวอย่าง (เชื่อม Redis ที่รันบน 192.168.1.50):
        java -jar build/libs/redis-pubsub-demo-all.jar --host 192.168.1.50 --port 6379 --name node-A --pass 12345
        java -jar build/libs/redis-pubsub-demo-all.jar --host 192.168.1.50 --port 6379 --name node-B --pass 12345


Tip: เปิดหลาย terminal หรือหลายเครื่องเพื่อทดสอบ Node หลายตัวพร้อมกัน

5.  docker run -it --rm --network host redis:7 redis-cli -h 192.168.1.98 -a 12345
    PUBLISH control "control:kill <PID>"

