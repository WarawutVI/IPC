package pubsub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class NodeApp{

    // --------- CLI args ---------
    static class Args {
        String host = "127.0.0.1";
        int port = 6379;
        String pass = null;
        String name = "node";

        static Args parse(String[] a) {
            Args args = new Args();
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < a.length - 1; i += 2) {
                if (a[i].startsWith("--")) {map.put(a[i], a[i + 1]);}
            }
            if (map.containsKey("--host")){ args.host = map.get("--host");}
            if (map.containsKey("--port")) {args.port = Integer.parseInt(map.get("--port"));}
            if (map.containsKey("--pass")){ args.pass = map.get("--pass");}
            if (map.containsKey("--name")){ args.name = map.get("--name");}
            return args;
        }
    }

    // --------- Redis helpers ---------
    static Jedis newJedis(String host, int port, String pass) {
        Jedis j = new Jedis(host, port);  // สร้าง client เชื่อมไปยัง Redis server
        if (pass != null && !pass.isEmpty()){ j.auth(pass);}// ถ้ามีการตั้งรหัสผ่าน
        return j; // คืนค่า client ที่พร้อมใช้งาน
    }

    // --------- Keys/Channels ---------
    static final String ZSET_MEMBERS = "cluster:nodes";     // score = pid (ยังใช้เดิม แต่เราจะเก็บ lastSeen ใน HSET) //งง
    static final String CH_BROADCAST = "broadcast";
    static final String CH_CONTROL   = "control";
    static final String CH_PRESENCE  = "presence";          // สแนปช็อต presence
    static String HB_KEY(long pid) { return "hb:" + pid; }  // TTL heartbeat
    static String INFO_KEY(long pid){ return "node:info:" + pid; } // HSET name, startedAt, lastSeen เป็นตัวเก็บข้อมูล process

    // --------- Node State ---------
    static class State { // Process
        final long pid;
        final String name;
        volatile boolean isLeader = false;
        volatile long leaderPid = -1;
        volatile boolean shuttingDown = false;

        State(long pid, String name) { this.pid = pid; this.name = name; }
    }

    // --------- Subscriber (รับข้อความ + presence) ---------
    static class Subscriber implements Runnable {
        final Args args; final State st;
        Subscriber(Args a, State s) { this.args = a; this.st = s; } //เอาตัวที่รับมาเก็บไว้ในclass

        @Override public void run() {
            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {//thread ปัจจุบันถูก interrupt แล้วหรือยัง ถ้าไม่ถูก interrupt มีค่า flase
            Jedis jedis = newJedis(args.host, args.port, args.pass);//เชื่อมไปยัง Redis server
                try (jedis) { 
                    JedisPubSub jps = new JedisPubSub() {//สร้างตัวจัดการข้อความ
                        @Override public void onMessage(String ch, String msg) {//รับข้อความจากช่อง CH_BROADCAST, CH_CONTROL, CH_PRESENCE
                            if (CH_BROADCAST.equals(ch)) {//ถ้าเป็นข้อความจากช่อง CH_BROADCAST
                                System.out.printf("[%-10s|MSG] %s%n", st.name, msg); //print ทำไม
                            } else if (CH_CONTROL.equals(ch)) {
                               if (msg.startsWith("control:kill")) {
                                    long target = Long.parseLong(msg.split("\\s+")[1]);//msg[1]==pid PUBLISH control "control:kill 22556"
                                    if (target == st.pid) { //เทียบกับของ
                                        System.out.printf("[%-10s|CTRL] got KILL → exit%n", st.name);
                                        shutdownNow(st);
                                    }
                                }  else {
                                    System.out.printf("[%-10s|CTRL] %s%n", st.name, msg);
                                }
                            } else if (CH_PRESENCE.equals(ch)) {
                                // รูปแบบข้อความ: "presence: <leaderPid>|pid1:name1:alive,pid2:name2:alive,..."
                                renderPresenceTable(st.name, msg);
                            }
                        }
                    };
                    //ส่งข้อความจากช่อง CH_BROADCAST, CH_CONTROL, CH_PRESENCE ไปยัง jps
                    jedis.subscribe(jps, CH_BROADCAST, CH_CONTROL, CH_PRESENCE); //ฟังช่อง CH_BROADCAST, CH_CONTROL, CH_PRESENCE
                } catch (Exception e) {
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
                
                try (Jedis jedis = newJedis(a.host, a.port, a.pass)) {
                    String role = st.isLeader ? "BOSS" : "WORKER";
                    String msg  = String.format("%s | %s | pid=%d @ %s", st.name, role, st.pid, Instant.now());
                    jedis.publish(CH_BROADCAST, msg);//ส่งไปช่อง Brodcast 
                    sleep(1); //รอ1วินาที
                } catch (Exception e) {
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
                    if (line == null || line.isEmpty() ){ sleep(1); continue; }
                    line = line.trim();
                    if (!st.isLeader) { //ถ้าไม่ใช่ leader
                        System.out.printf("[%-10s|CMD ] ignore '%s' (not leader)%n", st.name, line);
                        continue;
                    }

                    if (line.toLowerCase().startsWith("kill ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            long target = Long.parseLong(parts[1]);
                            try (Jedis j = newJedis(a.host, a.port, a.pass)) {
                                String tname = safeName(j, target);
                                j.publish(CH_CONTROL,st.pid + " kill " + target + " ("+tname+")");
                            }
                        }
                    } else {
                        System.out.printf("[%-10s|CMD ] unknown: %s (use: 'kill random' | 'kill <pid>')%n", st.name, line);
                    }
                } catch (Exception e) {
                    System.err.printf("[%-10s|CMD ] error: %s%n", st.name, e.getMessage());
                    sleep(1);
                }
            }
        }
    }




    // --------- Coordinator (HB + election + presence publish + delayed removal) ---------
    static class Coordinator implements Runnable {
        final Args a; final State st;
        Coordinator(Args a, State s) { this.a = a; this.st = s; }

        static final int HB_TTL_SEC = 1;
        static final long REMOVE_DELAY_MS = 20_000; // 20 วินาที

        @Override public void run() {
            // ใส่ตัวเองและตั้งข้อมูลโหนด
           
            try (Jedis j = newJedis(a.host, a.port, a.pass)) {
                j.zadd(ZSET_MEMBERS, st.pid, Long.toString(st.pid)); // database เล็ก ๆ สำหรับเก็บข้อมูล
                long now = System.currentTimeMillis();
                j.hset(INFO_KEY(st.pid), Map.of(
                        "name", st.name,
                        "startedAt", Long.toString(now),
                        "lastSeen", Long.toString(now)
                ));//จัดข้อมูลเป็น record คล้าย ๆ กับ row ในตาราง database
            }

            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {
                try (Jedis j = newJedis(a.host, a.port, a.pass)) {
                    long now = System.currentTimeMillis();

                    // 1) Heartbeat (TTL 1s) + อัพเดต lastSeen
                    j.setex(HB_KEY(st.pid), HB_TTL_SEC, Long.toString(now));//ตั้งค่า TTL เป็น 1sถ้า1sผ่านมาแล้วจะลบออก
                    j.hset(INFO_KEY(st.pid), "lastSeen", Long.toString(now));//อัพเดต lastSeen

                    // 2) ลบสมาชิก "ที่ตายแล้วเกิน 20s" เท่านั้น
                    for (String m : j.zrevrange(ZSET_MEMBERS, 0, -1)) {
                        long pid = Long.parseLong(m);
                        long ttl = j.ttl(HB_KEY(pid)); // ถ้าttlเป็น0หรือน้อยกว่า0จะลบออก
                        if (ttl <= 0) { // ไม่มี HB แล้ว
                            String ls = j.hget(INFO_KEY(pid), "lastSeen");//เอาข้อมูลlastScreenจาก INFO_KEY 
                            long lastSeen = (ls != null) ? Long.parseLong(ls) : 0L; //ถ้าlsไม่ใช่nullจะเอาข้อมูลออกมาเป็นlong
                            if (now - lastSeen >= REMOVE_DELAY_MS) { //ถ้าnow-lastSeen>=20sจะลบออก
                                j.zrem(ZSET_MEMBERS, m); //ลบออกจาก ZSET_MEMBERS database
                                j.del(INFO_KEY(pid)); //ลบออกจาก INFO_KEY  record
                            }
                        }
                    }

                    // 3) เลือก leader จาก "alive" เท่านั้น
                    List<Long> alive = aliveMembers(j);
                    long newLeader = alive.isEmpty() ? -1 : Collections.max(alive);
                    if (newLeader != st.leaderPid) {
                        st.leaderPid = newLeader; st.isLeader = (newLeader == st.pid);
                        j.publish(CH_CONTROL, "control:leader " + newLeader);
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
    }


    static String safeName(Jedis j, long pid) {
        try {
            String n = j.hget(INFO_KEY(pid), "name");
            return (n != null && !n.isEmpty()) ? n : ("node-" + pid);
        } catch (Exception e) {
            return "node-" + pid;
        }
    }
    // --------- Presence helpers ---------๓๓๓๓๓๓๓
    static void publishPresenceWithStatus(Jedis j, long leaderPid) {
        long now = System.currentTimeMillis();
        List<String> members = new ArrayList<>(j.zrevrange(ZSET_MEMBERS, 0, -1));//ดึงข้อมูลจาก ZSET_MEMBERS database เริ่ม 0 ถึง สุดท้าย
        String payloadMembers = members.stream().map(m -> {
            long pid = Long.parseLong(m);
            String name = safeName(j, pid);//ดึงชื่อจากช่อง INFO_KEY
            long ttl = j.ttl(HB_KEY(pid));//ตรวจสอบว่ามีการส่ง heartbeat หรือยัง
            int alive = (ttl > 0) ? 1 : 0;
            return pid + ":" + name + ":" + alive;
        }).collect(Collectors.joining(","));
        String payload = "presence: " + leaderPid + "|" + payloadMembers;//ส่งข้อความจากช่อง Presence
        j.publish(CH_PRESENCE, payload);
    }

    s

    static void renderPresenceTable(String localName, String payload) {
        // payload ตัวอย่าง: "presence: 12345|12345:A:1,12300:B:1,12200:C:0"
        try {
            String body = payload.split("presence:\\s*")[1];
            String[] parts = body.split("\\|", 2);
            long leader = Long.parseLong(parts[0].trim());
            String members = parts.length > 1 ? parts[1] : "";

            System.out.printf("%n[%-10s|PRESENCE] --- cluster members --- %s%n", localName, Instant.now());
            System.out.printf("%-8s  %-20s  %-8s  %-6s%n", "PID", "NAME", "ROLE", "STATUS");
            System.out.println("-------------------------------------------------------");
            if (!members.isEmpty()) {
                for (String item : members.split(",")) {
                    if (item.isBlank()) continue;
                    String[] kv = item.split(":");
                    long pid = Long.parseLong(kv[0]);
                    String name = kv.length > 1 ? kv[1] : ("node-" + pid);
                    int alive = (kv.length > 2) ? Integer.parseInt(kv[2]) : 1;
                    String role = (pid == leader) ? "BOSS" : "WORKER";
                    String status = (alive == 1) ? "ALIVE" : "DISAPPEAR";
                    System.out.printf("%-8d  %-20s  %-8s  %-6s%n", pid, name, role, status);
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
        new Thread(() -> { sleep(1); System.exit(0); }, "exit").start();
    }

    static List<Long> aliveMembers(Jedis j) {
        List<Long> pids = j.zrevrange(ZSET_MEMBERS, 0, -1)
                .stream().map(Long::parseLong).collect(Collectors.toList());
        List<Long> alive = new ArrayList<>();
        for (Long pid : pids) if (j.ttl(HB_KEY(pid)) > 0) alive.add(pid);
        return alive; // มาก→น้อย
    }

    static void sleep(int s) { try { TimeUnit.SECONDS.sleep(s); } catch (InterruptedException ignored) {} }

    // --------- Main ---------
    public static void main(String[] argsArr) {
        Args args = Args.parse(argsArr);//ตัวดึงstring จากcmd

        long pid = -1;
        try { pid = ProcessHandle.current().pid(); }
        catch (Throwable t) {
            try { pid = Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]); }
            catch (Exception ignore) {}
        }
        if (pid < 0) pid = new Random().nextInt(1_000_000);

        State st = new State(pid, args.name);
        System.out.printf("Start '%s' pid=%d -> redis %s:%d (auth=%s)%n",
                args.name, pid, args.host, args.port, (args.pass != null ? "yes" : "no"));

        // shutdown hook: เอาแบบ “ไม่ลบสมาชิกทันที” เพื่อให้ DEAD ค้างในตาราง 20s
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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

        ExecutorService pool = Executors.newFixedThreadPool(4);
        pool.submit(new Subscriber(args, st));
        pool.submit(new Publisher(args, st));
        pool.submit(new Coordinator(args, st));
        pool.submit(new Commander(args, st)); // อ่านคำสั่ง kill

        try { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); }
        catch (InterruptedException ignored) {}
    }
}
