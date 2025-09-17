import redis.clients.jedis.Jedis; //ใช้สำหรับการเชื่อมต่อและโต้ตอบกับ Redis server
import redis.clients.jedis.JedisPubSub; //ใช้สำหรับการสมัครรับข้อความ (subscribe) และจัดการกับข้อความที่ได้รับจาก Redis Pub/Sub //คลาสจากไลบรารี Jedis เพื่อคุยกับ Redis

import java.io.BufferedReader; //ใช้สำหรับ อ่านข้อความจากคีย์บอร์ด (stdin) แบบทีละบรรทัด
import java.io.InputStreamReader; //ใช้สำหรับ อ่านข้อความจากคีย์บอร์ด (stdin) แบบทีละบรรทัด
// import java.lang.management.ManagementFactory; //ใช้สำหรับดึงข้อมูลเกี่ยวกับ process ปัจจุบัน เช่น PID
import java.time.Instant; //ใช้สำหรับการจัดการกับเวลาและวันที่ (iso)
import java.util.*; //ใช้สำหรับการจัดการกับข้อมูลต่าง ๆ เช่น List, Map, Random
import java.util.concurrent.*; //ใช้สำหรับการจัดการกับเธรดและการทำงานแบบขนาน (concurrent)
import java.util.stream.Collectors; //ใช้สำหรับการจัดการกับข้อมูลในรูปแบบของสตรีม (stream) และการแปลงข้อมูล

public class NodeApp{ 

    // --------- CLI args ---------
    static class Args {  
        String host = ""; // กำหนดค่าเริ่มต้น: ต่อ Redis ที่ localhost
        int port = 6379; 
        String pass = null;
        String name = "node"; // กำหนดค่าเริ่มต้น: ต่อ Redis ที่ localhost:6379, ไม่มีรหัสผ่าน, และชื่อโหนด node

        static Args parse(String[] a) { //ดึงค่าจาก cmd ที่ส่งมาจาก main
            Args args = new Args(); 
            Map<String, String> map = new HashMap<>(); //เก็บค่าที่ดึงมาในmap 
            for (int i = 0; i < a.length - 1; i += 2) {  
                if (a[i].startsWith("--")) {map.put(a[i], a[i + 1]);} //key ต้องขึ้นต้นด้วย -- value คือค่าที่ตามมา 
            }
            if (map.containsKey("--host")){ args.host = map.get("--host");}  //ถ้ามีการกำหนด --host ให้เอาค่าที่กำหนดมาใช้แทนค่าเริ่มต้น
            if (map.containsKey("--port")) {args.port = Integer.parseInt(map.get("--port"));}
            if (map.containsKey("--pass")){ args.pass = map.get("--pass");}
            if (map.containsKey("--name")){ args.name = map.get("--name");}
            return args; // args = ตัวอ่านและเก็บค่าคอนฟิกง่าย ๆ จากบรรทัดคำสั่ง
        }
    }

    // --------- Redis helpers --------- 
    static Jedis newJedis(String host, int port, String pass) { 
        Jedis j = new Jedis(host, port);  // สร้าง client เชื่อมไปยัง Redis server | class Jedis ใช้สำหรับการเชื่อมต่อและโต้ตอบกับ Redis server
        if (pass != null && !pass.isEmpty()){ j.auth(pass);}// ถ้ามีการตั้งรหัสผ่าน ทำการยืนยันตัวตน
        return j; // คืนค่า client(อ็อบเจกต์ Jedis) ที่พร้อมใช้งาน
    } 

    // --------- Keys/Channels ---------
    static final String ZSET_MEMBERS = "cluster:nodes";     // score = pid (ยังใช้เดิม แต่เราจะเก็บ lastSeen ใน HSET) 
    // database เล็ก ๆ สำหรับเก็บข้อมูล ZSET_MEMBERS มักจะหมายถึง สมาชิก (members) ที่อยู่ใน Redis Sorted Set (ZSET)
    /*  ZSET (Sorted Set) ใน Redis คือโครงสร้างข้อมูลที่เก็บค่า (member) โดยแต่ละค่า มีคะแนน (score) กำกับ
        Redis จะเก็บข้อมูลเป็นลำดับเรียงตาม score (จากน้อยไปมาก)
        ใช้สำหรับ ranking, leaderboards, priority queues ฯลฯ   */
    
    static final String CH_BROADCAST = "broadcast"; 
    static final String CH_CONTROL   = "control"; 
    static final String CH_PRESENCE  = "presence";          // สแนปช็อต presence
    static String HB_KEY(long pid) { return "hb:" + pid; }  // สร้างชื่อคีย์สำหรับ heartbeat (มี TTL) | TTL heartbeat
    static String INFO_KEY(long pid){ return "node:info:" + pid; } // สร้างคีย์ตระกูล HSET สำหรับบันทึกข้อมูลโหนด | HSET name, startedAt, lastSeen เป็นตัวเก็บข้อมูล process

    // --------- Node State ---------
    static class State { // Process 
        final long pid; 
        final String name; 
        // ตัวแปร volatile = บอก JVM ว่าค่าตัวนี้อาจถูกแก้จากหลายเธรด ต้องอ่าน/เขียนให้เห็นผลร่วมกันทันที (เหมาะกับแฟล็กสถานะ)
        volatile boolean isLeader = false; 
        volatile long leaderPid = -1; 
        volatile boolean shuttingDown = false;

        State(long pid, String name) { this.pid = pid; this.name = name; } 
    }

    // --------- Subscriber (รับข้อความ + presence) ---------
    static class Subscriber implements Runnable { // thread รับข้อความ 
        final Args args; final State st;
        Subscriber(Args a, State s) { this.args = a; this.st = s; } //เอาตัวที่รับมาเก็บไว้ในclass

        @Override public void run() {
            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {// วนลูปไปเรื่อย ๆ จนกว่าจะปิดตัว | thread ปัจจุบันถูก interrupt แล้วหรือยัง ถ้าไม่ถูก interrupt มีค่า flase
            Jedis jedis = newJedis(args.host, args.port, args.pass);// สร้าง client เชื่อมไปยัง Redis server 
                try (jedis) {   /*try-with-resources เพื่อจัดการ resource อัตโนมัติ client close
                                ใช้ jedis เป็น resource ภายใน block
                                เมื่อออกจาก block ไม่ว่าปกติหรือ error → jedis.close() จะถูกเรียกอัตโนมัติ
                                ป้องกันปัญหา resource leak (เช่น connection ไม่ถูกปิด)*/

                    JedisPubSub jps = new JedisPubSub() {// สร้างตัวรับข้อความ
                        @Override public void onMessage(String ch, String msg) { //เมื่อมีข้อความใหม่เข้ามา | รับข้อความจากช่อง CH_BROADCAST, CH_CONTROL, CH_PRESENCE
                            if (CH_BROADCAST.equals(ch)) { //ถ้าข้อความมาจากช่อง CH_BROADCAST
                                System.out.printf("[%-10s|MSG] %s%n", st.name, msg); //แสดงข้อความที่ได้รับ
                            } else if (CH_CONTROL.equals(ch)) { 
                               if (msg.startsWith("control:kill")) { 
                                    long target = Long.parseLong(msg.split("\\s+")[1]); //msg[1]==pid | PUBLISH control "control:kill 22556"
                                    if (target == st.pid) { //เทียบกับของ
                                        System.out.printf("[%-10s|CTRL] got KILL → exit%n", st.name);
                                        shutdownNow(st); 
                                    }
                                }  else {
                                    System.out.printf("[%-10s|CTRL] %s%n", st.name, msg); //%-10s = เว้นวรรค10ช่อง | %n = ขึ้นบรรทัดใหม่
                                }
                            } else if (CH_PRESENCE.equals(ch)) { //
                                // รูปแบบข้อความ: "presence: <leaderPid>|pid1:name1:alive,pid2:name2:alive,..."
                                renderPresenceTable(st.name, msg); //เรียก renderPresenceTable เพื่อพิมพ์ตารางสมาชิก
                            }
                        }
                    };
                    //ส่งข้อความจากช่อง CH_BROADCAST, CH_CONTROL, CH_PRESENCE ไปยัง jps
                    jedis.subscribe(jps, CH_BROADCAST, CH_CONTROL, CH_PRESENCE); //ฟังช่อง CH_BROADCAST, CH_CONTROL, CH_PRESENCE | subscribe ไป 3 ช่อง: broadcast, control, presence — บล็อกค้างเพื่อรอฟังข้อความ
                } catch (Exception e) { // ถ้าการเชื่อมหลุด/เกิดปัญหา → พัก 2 วินาที แล้ววนใหม่ 
                    System.err.printf("[%-10s|SUB] error: %s (retry 2s)%n", st.name, e.getMessage());  
                    sleep(2);  
                }
            }
        }
    }

    // --------- Publisher (ส่งข้อความ chat เดิม) ---------
    static class Publisher implements Runnable {
        final Args a; final State st; 
        Publisher(Args a, State s) { this.a = a; this.st = s; }

        @Override public void run() {
            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {
                
                try (Jedis jedis = newJedis(a.host, a.port, a.pass)) { // สร้าง client เชื่อมไปยัง Redis server
                    String role = st.isLeader ? "BOSS" : "WORKER"; //ถ้าisLeader=true role=BOSS ถ้าisLeader=false role=WORKER
                    String msg  = String.format("%s | %s | pid=%d @ %s", st.name, role, st.pid, Instant.now()); //สร้างข้อความที่จะส่ง
                    jedis.publish(CH_BROADCAST, msg); //ส่งข้อความไปยังช่อง CH_BROADCAST
                    sleep(1); //รอ1วินาที 
                } catch (Exception e) { // ถ้าการเชื่อมหลุด/เกิดปัญหา → พัก 2 วินาที แล้ววนใหม่
                    System.err.printf("[%-10s|PUB] error: %s (retry 2s)%n", st.name, e.getMessage());
                    sleep(2);
                }
            }
        }
    }

    // --------- Console Commander (อ่านคำสั่ง kill) ---------
    static class Commander implements Runnable {
        final Args a; final State st;
        Commander(Args a, State s) { this.a = a; this.st = s; }

        @Override public void run() {
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in)); //สร้าง object BufferedReader ที่ใช้สำหรับ อ่านข้อความจากคีย์บอร์ด (stdin) แบบทีละบรรทัด
            //new InputStreamReader(System.in) สร้าง object InputStreamReader ที่ใช้สำหรับ อ่านข้อความจากคีย์บอร์ด (stdin) แบบทีละบรรทัด

            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {
                try {
                    String line = br.readLine();
                    if (line == null || line.isEmpty() ){ sleep(1); continue; } // ถ้าไม่มีข้อความให้รอ 1 วินาทีแล้ววนใหม่
                    line = line.trim(); //ตัดช่องว่างออกจากข้อความ
                    if (!st.isLeader) { //ถ้าไม่ใช่ leader
                        System.out.printf("[%-10s|CMD ] ignore '%s' (not leader)%n", st.name, line);
                        continue;
                    }

                    if (line.toLowerCase().startsWith("kill ")) {
                        String[] parts = line.split("\\s+"); //split("\\s+") = แยกข้อความโดยใช้ช่องว่างเป็นตัวแบ่ง  อย่างเช่น "kill 12345" จะได้ parts[0]="kill" parts[1]="12345" \\s+ = ช่องว่าง 1 ตัวขึ้นไป
                        if (parts.length >= 2) {
                            long target = Long.parseLong(parts[1]); //parses[1] คือ pid ที่ต้องการ kill
                            try (Jedis j = newJedis(a.host, a.port, a.pass)) { //try-with-resources เพื่อจัดการ resource อัตโนมัติ client close , connect to Redis
                                String tname = safeName(j, target); //ดึงชื่อจากช่อง INFO_KEY
                                j.publish(CH_CONTROL, st.pid + " kill " + target + " ("+tname+")"); //ส่งข้อความไปยังช่อง CH_CONTROL | .plublish(channel, message) ส่งข้อความไปยังช่องที่ระบุ
                            }
                        }
                    } else {
                        System.out.printf("[%-10s|CMD ] unknown: %s (use: 'kill random' | 'kill <pid>')%n", st.name, line); //ถ้าคำสั่งไม่ใช่ kill ให้แสดงข้อความว่าไม่รู้จักคำสั่ง line คือคำสั่งที่พิมพ์เข้ามา
                    }
                } catch (Exception e) {
                    System.err.printf("[%-10s|CMD ] error: %s%n", st.name, e.getMessage()); //แสดงข้อความ error 
                    sleep(1);
                }
            }
        }
    }




    // --------- Coordinator (HB + election + presence publish + delayed removal) ---------
    static class Coordinator implements Runnable { // thread Coordinator = ระบบประสานงานกลาง ดูแลสมาชิก, เลือกหัวหน้า, แจ้งสภาพแวดล้อมให้ทุกคนรู้
        final Args a; final State st;
        Coordinator(Args a, State s) { this.a = a; this.st = s; }

        static final int HB_TTL_SEC = 3; // heartbeat มีอายุ 3 วินาที
        static final long REMOVE_DELAY_MS = 20_000; // 20 วินาที ถ้าหายไปนานเกินนี้ค่อยลบชื่อออกจากรายการสมาชิก

        @Override public void run() {
            // ใส่ตัวเองและตั้งข้อมูลโหนด
           
            try (Jedis j = newJedis(a.host, a.port, a.pass)) {  // สร้าง client เชื่อมไปยัง Redis server
                j.zadd(ZSET_MEMBERS, st.pid, Long.toString(st.pid)); //เพิ่มสมาชิกใหม่ลงใน ZSET_MEMBERS database โดยใช้ pid เป็น score และ value // database เล็ก ๆ สำหรับเก็บข้อมูล
                // ZSET_MEMBERS = สมาชิก (members) ที่อยู่ใน Redis Sorted Set (ZSET)
                /*  ZSET (Sorted Set) ใน Redis คือโครงสร้างข้อมูลที่เก็บค่า (member) โดยแต่ละค่า มีคะแนน (score) กำกับ
                    Redis จะเก็บข้อมูลเป็นลำดับเรียงตาม score (จากน้อยไปมาก)
                    ใช้สำหรับ ranking, leaderboards, priority queues ฯลฯ   */ 

                long now = System.currentTimeMillis(); //ดึงเวลาปัจจุบันเป็นมิลลิวินาที 

                j.hset(INFO_KEY(st.pid), Map.of( //คำสั่ง Redis HSET เอาไว้เก็บข้อมูลเป็น Hash (คล้าย row ใน relational DB) | INFO_KEY(st.pid) = "node:info:<pid>" 
                        "name", st.name,
                        "startedAt", Long.toString(now), 
                        "lastSeen", Long.toString(now) // lastSeen = now ตอนเริ่มต้น (ยังไม่ต้องมี heartbeat เพราะเพิ่งเริ่มต้น
                ));//จัดข้อมูลเป็น record คล้าย ๆ กับ row ในตาราง database 

                /*  j.hset(...) → คำสั่ง Redis HSET เอาไว้เก็บข้อมูลเป็น Hash (คล้าย row ใน relational DB)
                    INFO_KEY(st.pid) → "node:info:<pid>"    
                    Map.of(...) → สร้างแผนที่ (map) ที่มีคีย์-ค่า (key-value pairs) ดังนี้:
                        "name" → st.name (ชื่อโหนด)
                        "startedAt" → Long.toString(now) (เวลาที่เริ่มต้นเป็นสตริง)
                        "lastSeen" → Long.toString(now) (เวลาที่เห็นล่าสุดเป็นสตริง)
                */ 

            }

            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {
                try (Jedis j = newJedis(a.host, a.port, a.pass)) {
                    long now = System.currentTimeMillis();

                    // 1) Heartbeat (TTL 3s) + อัพเดต lastSeen
                    j.setex(HB_KEY(st.pid), HB_TTL_SEC, Long.toString(now)); //ตั้งค่า heartbeat โดยมีอายุ 3 วินาที
                    /*
                        String setex(String key, int seconds, String value)
                            key → ชื่อ key ที่ต้องการเก็บค่า
                            seconds → TTL (time-to-live) อายุของ key หน่วยเป็นวินาที
                            value → ค่าที่จะเก็บใน key (เป็น String)
                    */ 
                    j.hset(INFO_KEY(st.pid), "lastSeen", Long.toString(now));//อัพเดต lastSeen

                    // 2) ลบสมาชิก "ที่ตายแล้วเกิน 20s" เท่านั้น
                    for (String m : j.zrevrange(ZSET_MEMBERS, 0, -1)) {// ดึงข้อมูลจาก ZSET_MEMBERS database เริ่ม 0 ถึง สุดท้าย
                        // zrevrange = ดึงข้อมูลจาก ZSET โดยเรียงจากมากไปน้อย // -1 = สุดท้าย 
                        long pid = Long.parseLong(m); 
                        long ttl = j.ttl(HB_KEY(pid)); // j.ttl(...) ใช้ตรวจสอบว่า key นั้น ๆ จะ หมดอายุในอีกกี่วินาที | ถ้าttlเป็น0หรือน้อยกว่า0จะลบออก
                        if (ttl <= 0) { // ไม่มี HB แล้ว 
                            String ls = j.hget(INFO_KEY(pid), "lastSeen");//เอาข้อมูลlastScreenจาก INFO_KEY 
                            long lastSeen = (ls != null) ? Long.parseLong(ls) : 0L; //ถ้าlsไม่ใช่nullจะเอาข้อมูลออกมาเป็นlong | 0L = 0 แบบ long
                            if (now - lastSeen >= REMOVE_DELAY_MS) { //ถ้าnow-lastSeen>=20sจะลบออก
                                j.zrem(ZSET_MEMBERS, m); //ลบออกจาก ZSET_MEMBERS database
                                j.del(INFO_KEY(pid)); //ลบออกจาก INFO_KEY  record
                            }
                        }
                    }

                    // 3) เลือก leader จาก "alive" เท่านั้น
                    List<Long> alive = aliveMembers(j);  
                    long newLeader = alive.isEmpty() ? -1 : Collections.max(alive); // ถ้าไม่มีใคร alive newLeader=-1 ถ้ามีคนalive newLeader=pidที่มากที่สุด
                    if (newLeader != st.leaderPid) { //ถ้าnewLeaderไม่เท่ากับleaderPid
                        st.leaderPid = newLeader; //อัพเดตleaderPid

                        if(newLeader == st.pid){ //check ว่าตัวเองคือleaderไหม
                            st.isLeader = true; //ถ้าnewLeaderเท่ากับpidของตัวเอง isLeader=true 
                        }else{
                            st.isLeader = false; // ถ้าไม่เท่ากับisLeader=false
                        }
                        // st.isLeader = (newLeader == st.pid); 
                        j.publish(CH_CONTROL, "control:leader " + newLeader); //ส่งข้อความจากช่อง CH_CONTROL บอกทุกคนว่าleaderคือใคร
                        logRole(st);
                    }

                    // 4) ส่ง Presence Snapshot (รวมทั้ง ALIVE และ DEAD ที่ยังไม่ครบ 20s)
                    publishPresenceWithStatus(j, st.leaderPid); //ส่งข้อความจากช่อง Presence บอกทุกคนว่าleaderคือใคร

                    sleep(2); //รอ2วินาที
                } catch (Exception e) { 
                    System.err.printf("[%-10s|COORD] error: %s (retry 2s)%n", st.name, e.getMessage());
                    sleep(2);
                }
            }
        }
    }// thread Coordinator = ระบบประสานงานกลาง ดูแลสมาชิก, เลือกหัวหน้า, แจ้งสภาพแวดล้อมให้ทุกคนรู้


    static String safeName(Jedis j, long pid) {
        try {
            String n = j.hget(INFO_KEY(pid), "name"); //ดึงชื่อจากช่อง INFO_KEY
            return (n != null && !n.isEmpty()) ? n : ("node-" + pid); //ถ้าnไม่ใช่nullและไม่ใช่ค่าว่างจะคืนค่าn ถ้าเป็นnullหรือค่าว่างจะคืนค่า node-pid
        } catch (Exception e) {
            return "node-" + pid;
        }
    }
    // --------- Presence helpers ---------๓๓๓๓๓๓๓ 
    static void publishPresenceWithStatus(Jedis j, long leaderPid) { //ส่งข้อความจากช่อง Presence
        long now = System.currentTimeMillis();
        List<String> members = new ArrayList<>(j.zrevrange(ZSET_MEMBERS, 0, -1));//ดึงข้อมูลจาก ZSET_MEMBERS database เริ่ม 0 ถึง สุดท้าย น้อย→มาก
        String payloadMembers = members.stream().map(m -> { 
            /*
            m = สมาชิกแต่ละตัวใน members (เช่น "1234")
            map(...) → แปลง "1234" ให้กลายเป็น "1234:NodeA:1"
            ผลลัพธ์ stream หลัง .map(...) = ["1234:NodeA:1", "1235:NodeB:0", ...]
            จากนั้น .collect(Collectors.joining(",")) จะรวมให้เป็น string เดียว
            
            map = เอา element แต่ละตัวใน stream มาทำการแปลง (transformation)
            m -> {...} = lambda expression ที่บอกว่าจะเปลี่ยน m ให้กลายเป็นค่าใหม่อะไร
            ผลลัพธ์คือ Stream ใหม่ ที่มีค่าที่ถูกแปลงแล้ว
            */ 
            long pid = Long.parseLong(m);
            String name = safeName(j, pid);//ดึงชื่อจากช่อง INFO_KEY
            long ttl = j.ttl(HB_KEY(pid));// j.ttl(...) ใช้ตรวจสอบว่า key นั้น ๆ จะ หมดอายุในอีกกี่วินาที | ถ้าttlเป็น0หรือน้อยกว่า0จะลบออก
            int alive = (ttl > 0) ? 1 : 0; // ถ้า ttl > 0 แสดงว่าโหนดยังมี heartbeat อยู่ (alive=1) ถ้า ttl <= 0 แสดงว่าโหนดไม่มี heartbeat (alive=0)
            return pid + ":" + name + ":" + alive; 
        }).collect(Collectors.joining(",")); // .collect(Collectors.joining(",")) = รวมสตริงที่แปลงแล้วเป็นสตริงเดียว โดยคั่นด้วยเครื่องหมายจุลภาค (,)
        // members.stream() = เอาข้อมูลในlistมาใช้

        String payload = "presence: " + leaderPid + "|" + payloadMembers;//ส่งข้อความจากช่อง Presence
        j.publish(CH_PRESENCE, payload); //ส่งข้อความจากช่อง Presence
    } // payloadMembers = pid1:name1:alive, pid2:name2:alive,...

    

    static void renderPresenceTable(String localName, String payload) {
        // payload ตัวอย่าง: "presence: 12345|12345:A:1,12300:B:1,12200:C:0"
        try {
            String body = payload.split("presence:\\s*")[1]; // แยกเอาเฉพาะส่วนที่เป็นข้อมูลจริง | split("presence:\\s*") = แยกข้อความโดยใช้ "presence:" ตามด้วยช่องว่าง 0 ตัวขึ้นไป เป็นตัวแบ่ง
            String[] parts = body.split("\\|"); // แยกเอา leaderPid กับ รายการสมาชิก | split("\\|", 2) = แยกข้อความโดยใช้ | เป็นตัวแบ่ง โดยแยกได้สูงสุด 2 ส่วน
            long leader = Long.parseLong(parts[0].trim()); //เอาleaderPidออกมา | trim() = ตัดช่องว่างออก
            String members = parts.length > 1 ? parts[1] : ""; //เอารายการสมาชิกออกมา ถ้าไม่มีให้เป็นค่าว่าง
            // พิมพ์ตารางสมาชิก
            System.out.printf("%n[%-10s|PRESENCE] --- cluster members --- %s%n", localName, Instant.now()); // instant.now() = เวลาปัจจุบันแบบ iso
            System.out.printf("%-8s  %-20s  %-8s  %-6s%n", "PID", "NAME", "ROLE", "STATUS");
            System.out.println("-------------------------------------------------------");
            if (!members.isEmpty()) {
                for (String item : members.split(",")) {
                    if (item.isBlank()) continue;
                    String[] kv = item.split(":"); //แยก pid, name, alive | split(":") = แยกข้อความโดยใช้ : เป็นตัวแบ่ง
                    long pid = Long.parseLong(kv[0]);
                    String name = kv.length > 1 ? kv[1] : ("node-" + pid);
                    int alive = (kv.length > 2) ? Integer.parseInt(kv[2]) : 1; // ถ้าไม่มีข้อมูล alive ให้ถือว่า alive=1 (ปลอดภัยไว้ก่อน)
                    String role = (pid == leader) ? "BOSS" : "WORKER"; // ถ้า pid ตรงกับ leaderPid ให้ role = BOSS ถ้าไม่ตรงให้ role = WORKER
                    String status = (alive == 1) ? "ALIVE" : "DISAPPEAR"; // ถ้า alive=1 ให้ status=ALIVE ถ้า alive=0 ให้ status=DISAPPEAR
                    System.out.printf("%-8d  %-20s  %-8s  %-6s%n", pid, name, role, status); //%-8d = เว้นวรรค8ช่องสำหรับตัวเลข | %-20s = เว้นวรรค20ช่องสำหรับstring | %-6s = เว้นวรรค6ช่องสำหรับstring
                }
            } else {
                System.out.println("(no members)");
            }
            System.out.println("-------------------------------------------------------\n");
        } catch (Exception ignore) {
            System.out.printf("[%-10s|PRESENCE] %s%n", localName, payload);
        }
    }

    // --------- Utilities ---------
    static void logRole(State st) {
        String role = st.isLeader ? "BOSS" : "WORKER";
        System.out.printf("[%-10s|ROLE] now %s (leaderPid=%d, myPid=%d)%n",
                st.name, role, st.leaderPid, st.pid);
    }

    static void shutdownNow(State st) {
        st.shuttingDown = true;
        new Thread(() -> { sleep(1); System.exit(0); }, "exit").start(); // สร้างเธรดใหม่ชื่อ exit เพื่อรอ 1 วินาทีแล้วปิดโปรแกรม
    }

    static List<Long> aliveMembers(Jedis j) { // ดึงสมาชิกที่ยังมี heartbeat อยู่
        List<Long> pids = j.zrevrange(ZSET_MEMBERS, 0, -1).stream().map(Long::parseLong).collect(Collectors.toList()); 
        // มาก→น้อย .stream() = เอาข้อมูลในlistมาใช้ .map(Long::parseLong) = แปลงstringเป็นlong .collect(Collectors.toList()) = เก็บกลับเป็นlist
        List<Long> alive = new ArrayList<>(); 
        for (Long pid : pids) if (j.ttl(HB_KEY(pid)) > 0) alive.add(pid);
        return alive; // มาก→น้อย
    }

    static void sleep(int s) { try { TimeUnit.SECONDS.sleep(s); } catch (InterruptedException ignored) {} } 
    /*
        TimeUnit.SECONDS.sleep(s);
        ใช้ TimeUnit จาก java.util.concurrent
        TimeUnit.SECONDS.sleep(s) = หยุด Thread ปัจจุบันเป็นเวลา s วินาที
        อ่านง่ายกว่า Thread.sleep(s * 1000);
    */  

    // --------- Main ---------
    public static void main(String[] argsArr) { // String[] argsArr = ค่าที่รับมาจาก cmd
        Args args = Args.parse(argsArr);//ตัวดึงstring จากcmd

        long pid = -1;  
        // try { pid = ProcessHandle.current().pid(); }
        // catch (Throwable t) {
        //     try { pid = Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]); }
        //     catch (Exception ignore) {}
        // }
        if (pid < 0) pid = new Random().nextInt(1_000_000); 

        State st = new State(pid, args.name); // สร้าง process
        System.out.printf("Start '%s' pid=%d -> redis %s:%d (auth=%s)%n",
                args.name, pid, args.host, args.port, (args.pass != null ? "yes" : "no"));

        // shutdown hook: เอาแบบ “ไม่ลบสมาชิกทันที” เพื่อให้ DEAD ค้างในตาราง 20s
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { // Runtime.getRuntime().addShutdownHook = เพิ่ม shutdown hook เพื่อทำงานเมื่อโปรแกรมกำลังจะปิดตัว | Ctrl+C
            st.shuttingDown = true;
            try (Jedis j = newJedis(args.host, args.port, args.pass)) {
                // ลบเฉพาะ HB; คง member + info ไว้ให้ coordinator ตัดออกหลัง 20s
                j.del(HB_KEY(st.pid));
                // j.zrem(ZSET_MEMBERS, Long.toString(st.pid)); // อย่าลบทันที
                // j.del(INFO_KEY(st.pid));                     // อย่าลบทันที
                if (st.isLeader) j.publish(CH_CONTROL, "control:leader -1");
            } catch (Exception ignore) {}
            System.out.printf("[%-10s|SHUT] done%n", st.name);
        }));

        ExecutorService pool = Executors.newFixedThreadPool(4); //ExecutorService = จัดการเธรดแบบกลุ่ม
        // สร้างเธรด 4 ตัว: Subscriber, Publisher, Coordinator,
        pool.submit(new Subscriber(args, st));
        pool.submit(new Publisher(args, st));
        pool.submit(new Coordinator(args, st));
        pool.submit(new Commander(args, st)); // อ่านคำสั่ง kill

        try { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); }
        catch (InterruptedException ignored) {}
    }
}