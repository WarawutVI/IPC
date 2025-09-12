package pubsub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

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
                if (a[i].startsWith("--")) map.put(a[i], a[i + 1]);
            }
            if (map.containsKey("--host")) args.host = map.get("--host");
            if (map.containsKey("--port")) args.port = Integer.parseInt(map.get("--port"));
            if (map.containsKey("--pass")) args.pass = map.get("--pass");
            if (map.containsKey("--name")) args.name = map.get("--name");
            return args;
        }
    }

    // --------- Redis helpers ---------
    static Jedis newJedis(String host, int port, String pass) {
        Jedis j = new Jedis(host, port);
        if (pass != null && !pass.isEmpty()) j.auth(pass);
        return j;
    }

    // --------- Keys/Channels ---------
    static final String ZSET_MEMBERS = "cluster:nodes";     // score = pid
    static final String CH_BROADCAST = "broadcast";
    static final String CH_CONTROL   = "control";
    static final String CH_PRESENCE  = "presence";          // สแนปช็อต presence
    static String HB_KEY(long pid) { return "hb:" + pid; }  // TTL heartbeat
    static String INFO_KEY(long pid){ return "node:info:" + pid; } // HSET name, startedAt

    // --------- Node State ---------
    static class State {
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
        Subscriber(Args a, State s) { this.args = a; this.st = s; }

        @Override public void run() {
            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = newJedis(args.host, args.port, args.pass)) {
                    // System.out.printf("[%-10s|SUB] subscribe '%s','%s','%s'%n",
                    //         st.name, CH_BROADCAST, CH_CONTROL, CH_PRESENCE);

                    jedis.subscribe(new JedisPubSub() {
                        @Override public void onMessage(String ch, String msg) {
                            if (CH_BROADCAST.equals(ch)) {
                                System.out.printf("[%-10s|MSG] %s%n", st.name, msg);
                            } else if (CH_CONTROL.equals(ch)) {
                                if (msg.startsWith("control:leader")) {
                                    long leader = Long.parseLong(msg.split("\\s+")[1]);
                                    st.leaderPid = leader; st.isLeader = (leader == st.pid);
                                    logRole(st);
                                } else if (msg.startsWith("control:kill")) {
                                    long target = Long.parseLong(msg.split("\\s+")[1]);
                                    if (target == st.pid) {
                                        System.out.printf("[%-10s|CTRL] got KILL → exit%n", st.name);
                                        shutdownNow(st);
                                    }
                                } else {
                                    System.out.printf("[%-10s|CTRL] %s%n", st.name, msg);
                                }
                            } else if (CH_PRESENCE.equals(ch)) {
                                // รูปแบบข้อความ: "presence: <leaderPid>|pid1:name1,pid2:name2,..."
                                // แสดงเป็นตารางอ่านง่าย
                                renderPresenceTable(st.name, msg);
                            }
                        }
                    }, CH_BROADCAST, CH_CONTROL, CH_PRESENCE);
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
                    jedis.publish(CH_BROADCAST, msg);
                    sleep(5);
                } catch (Exception e) {
                    System.err.printf("[%-10s|PUB] error: %s (retry 2s)%n", st.name, e.getMessage());
                    sleep(2);
                }
            }
        }
    }

    // --------- Coordinator (HB + election + presence publish) ---------
    static class Coordinator implements Runnable {
        final Args a; final State st;
        Coordinator(Args a, State s) { this.a = a; this.st = s; }

        @Override public void run() {
            // ใส่ตัวเองและตั้งข้อมูลโหนด
            try (Jedis j = newJedis(a.host, a.port, a.pass)) {
                j.zadd(ZSET_MEMBERS, st.pid, Long.toString(st.pid));
                j.hset(INFO_KEY(st.pid), Map.of(
                        "name", st.name,
                        "startedAt", Long.toString(System.currentTimeMillis())
                ));
            }

            while (!st.shuttingDown && !Thread.currentThread().isInterrupted()) {
                try (Jedis j = newJedis(a.host, a.port, a.pass)) {
                    // 1) Heartbeat (TTL 5s)
                    j.setex(HB_KEY(st.pid), 5, Long.toString(System.currentTimeMillis()));

                    // 2) ล้างสมาชิกที่ตาย และลบ info
                    for (String m : j.zrevrange(ZSET_MEMBERS, 0, -1)) {
                        long pid = Long.parseLong(m);
                        if (j.ttl(HB_KEY(pid)) <= 0) {
                            j.zrem(ZSET_MEMBERS, m);
                            j.del(INFO_KEY(pid));
                        }
                    }

                    // 3) เลือก leader
                    List<Long> alive = aliveMembers(j);
                    long newLeader = alive.isEmpty() ? -1 : alive.get(0);
                    if (newLeader != st.leaderPid) {
                        st.leaderPid = newLeader; st.isLeader = (newLeader == st.pid);
                        j.publish(CH_CONTROL, "control:leader " + newLeader);
                        logRole(st);
                    }

                    // 4) ส่ง Presence Snapshot (ทุกโหนด จะ “เห็น” รายชื่ออุปกรณ์ทั้งหมด)
                    publishPresence(j, st.leaderPid, alive);

                    sleep(2);
                } catch (Exception e) {
                    System.err.printf("[%-10s|COORD] error: %s (retry 2s)%n", st.name, e.getMessage());
                    sleep(2);
                }
            }
        }
    }

    // --------- Presence helpers ---------
    static void publishPresence(Jedis j, long leaderPid, List<Long> alivePids) {
        // ทำสตริง "presence: <leaderPid>|pid1:name1,pid2:name2,..."
        String members = alivePids.stream()
                .map(pid -> pid + ":" + safeName(j, pid))
                .collect(Collectors.joining(","));
        String payload = "presence: " + leaderPid + "|" + members;
        j.publish(CH_PRESENCE, payload);
    }

    static String safeName(Jedis j, long pid) {
        try {
            String n = j.hget(INFO_KEY(pid), "name");
            return (n != null && !n.isEmpty()) ? n : ("node-" + pid);
        } catch (Exception e) {
            return "node-" + pid;
        }
    }

    static void renderPresenceTable(String localName, String payload) {
        // payload ตัวอย่าง: "presence: 12345|12345:A,12300:B,12200:C"
        try {
            String body = payload.split("presence:\\s*")[1];
            String[] parts = body.split("\\|", 2);
            long leader = Long.parseLong(parts[0].trim());
            String members = parts.length > 1 ? parts[1] : "";

            System.out.printf("%n[%-10s|PRESENCE] --- cluster members --- %s%n", localName, Instant.now());
            System.out.printf("%-8s  %-20s  %-8s%n", "PID", "NAME", "ROLE");
            System.out.println("-----------------------------------------------");
            if (!members.isEmpty()) {
                for (String item : members.split(",")) {
                    if (item.isBlank()) continue;
                    String[] kv = item.split(":");
                    long pid = Long.parseLong(kv[0]);
                    String name = kv.length > 1 ? kv[1] : ("node-" + pid);
                    String role = (pid == leader) ? "BOSS" : "WORKER";
                    System.out.printf("%-8d  %-20s  %-8s%n", pid, name, role);
                }
            } else {
                System.out.println("(no members)");
            }
            System.out.println("-----------------------------------------------\n");
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
        Args args = Args.parse(argsArr);

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

        // shutdown hook: ลบ HB + สมาชิก + info
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            st.shuttingDown = true;
            try (Jedis j = newJedis(args.host, args.port, args.pass)) {
                j.del(HB_KEY(st.pid));
                j.zrem(ZSET_MEMBERS, Long.toString(st.pid));
                j.del(INFO_KEY(st.pid));
                if (st.isLeader) j.publish(CH_CONTROL, "control:leader -1");
            } catch (Exception ignore) {}
            System.out.printf("[%-10s|SHUT] done%n", st.name);
        }));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        pool.submit(new Subscriber(args, st));
        pool.submit(new Publisher(args, st));
        pool.submit(new Coordinator(args, st));

        try { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); }
        catch (InterruptedException ignored) {}
    }
}