package com.yeho.chat;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 本地消息缓存（文件存储，每行一条 JSON）
 * 时间戳精确到秒。
 * 清理规则：默认按条数 150；支持 条数/大小/天数/无上限，10 秒循环清理。
 */
public class MsgStore {

    public static class M {
        public long ts;      // 秒
        public String from;
        public String text;
        public boolean mine;

        /**
         * 发送者的 uid：显示名要拿它去名册里查，改名后历史消息才跟着变。
         * 用 uid 而不是 uuid —— uuid 用户每 30 天能自己改一次，uid 是数据库自增主键，不可变。
         * 老缓存没有这一段，按 0（未知）处理。
         */
        public long uid = 0;

        // ---- 引用（后加字段）：老缓存文件里没有，反序列化时按空值处理 ----
        public String qFrom = "";   // 被引用者昵称（快照，只当兜底）
        public String qText = "";   // 被引用内容快照
        public long qTs = 0;        // 被引用消息的 ts；0 表示没有引用
        public long qUid = 0;       // 被引用者的 uid：同上，用来查当前昵称

        /** 系统提示（"某某 撤回了一条消息"）：居中灰字，不是真正的聊天内容，不可长按 */
        public boolean sys = false;

        public M(long ts, String from, String text, boolean mine) {
            this.ts = ts; this.from = from; this.text = text; this.mine = mine;
        }

        /**
         * 造一条撤回提示。
         *
         * text 故意留空：文案由渲染层拿 uuid 去名册里现算（"XXX 撤回了一条消息"），
         * 这样改名后连这条提示里的名字也会跟着变。from 存昵称快照只当兜底。
         */
        public static M recallTip(long ts, String name, long uid) {
            M m = new M(ts, name == null ? "" : name, "", false);
            m.uid = uid;
            m.sys = true;
            return m;
        }

        public M(long ts, String from, String text, boolean mine,
                 String qFrom, String qText, long qTs) {
            this(ts, from, text, mine);
            this.qFrom = qFrom == null ? "" : qFrom;
            this.qText = qText == null ? "" : qText;
            this.qTs = qTs;
        }

        /** 是否是一条引用消息 */
        public boolean hasQuote() {
            return qTs > 0 || (qText != null && !qText.isEmpty());
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final File file;
    /** 墓碑文件：与消息缓存分开存，删掉的消息键记在这里，防止同步/重连把它拉回来 */
    private final File tombFile;
    /** 名册：uid -> 当前昵称。改名后靠它把历史消息里的旧名字换掉 */
    private final File rosterFile;
    private final java.util.HashMap<Long, String> roster = new java.util.HashMap<>();
    private boolean rosterLoaded = false;
    /**
     * 已归档（软删除）的 uid。
     *
     * 跟 markDeleted 那种"把本地消息的 uid 改写成 -1"不同：归档期间服务端一个消息
     * 字段都没动，消息上的 uid 还是本人，所以这里只在渲染时查一下名单。
     * 好处是取消归档把 uid 移出名单后，历史消息立刻自己还原 —— 不用回滚任何数据。
     */
    private final File archivedFile;
    private final java.util.HashSet<Long> archived = new java.util.HashSet<>();
    private boolean archivedLoaded = false;
    /**
     * 「已撤回的消息」标记集合，键是 tsuid。
     *
     * 用途：消息里那个引用块存的是**发送那一刻的快照**，原消息被撤回后快照还在，
     * 引用块就会照旧显示已经被撤掉的内容。渲染时拿 (qTs, qUid) 查一下这里，
     * 命中就显示「原消息已撤回」。
     *
     * 用 uid 而不是昵称：uid 是数据库主键不可变，改过名也能对上。
     * 用 ts+uid 而不是只用 ts：同一秒可能有好几条消息。
     */
    private final File recalledFile;
    private final java.util.HashSet<String> recalled = new java.util.HashSet<>();
    private boolean recalledLoaded = false;
    private final Object lock = new Object();
    private final List<M> cache = new ArrayList<>();
    private final java.util.HashSet<String> keys = new java.util.HashSet<>();
    /** 已删除消息的指纹集合（持久化）。合并/写入前先查这里，命中就跳过 */
    private final java.util.HashSet<String> tombstones = new java.util.HashSet<>();
    private boolean loaded = false;
    private boolean tombstonesLoaded = false;
    /** 每次 clearAll() 自增：聊天页回到前台时用它判断"缓存被别的页面清过了，得重画" */
    private volatile int generation = 0;

    /** 全局唯一实例：Service 写入、Activity 读取必须共享同一份内存缓存 */
    private static MsgStore INSTANCE;

    public static synchronized MsgStore get(Context ctx, Prefs prefs) {
        if (INSTANCE == null) INSTANCE = new MsgStore(ctx.getApplicationContext(), prefs);
        return INSTANCE;
    }

    public MsgStore(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.file = new File(ctx.getFilesDir(), "msg_cache.txt");
        this.tombFile = new File(ctx.getFilesDir(), "msg_tomb.txt");
        this.rosterFile = new File(ctx.getFilesDir(), "msg_roster.txt");
        this.archivedFile = new File(ctx.getFilesDir(), "msg_archived.txt");
        this.recalledFile = new File(ctx.getFilesDir(), "msg_recalled.txt");
    }

    // ---------- 存取 ----------
    public void load() {
        loadTombstones();
        synchronized (lock) {
            if (loaded) return;   // 只加载一次：避免 Activity 重入把内存里未落盘的实时消息冲掉
            cache.clear();
            keys.clear();
            if (!file.exists()) { loaded = true; return; }
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    M m = parse(line);
                    if (m != null) {
                        cache.add(m);
                        keys.add(keyOf(m));
                    }
                }
                r.close();
            } catch (Exception ignored) { }
            loaded = true;
        }
    }

    /** 去重键：起始时间 + 发送人 + 内容（同一秒同一人可能发多条，用序号区分不可行，这里用内容） */
    private static String keyOf(M m) {
        return m.ts + "\u0002" + (m.from == null ? "" : m.from)
                + "\u0002" + (m.text == null ? "" : m.text);
    }

    /** 新增（自动去重），返回是否真的加进去了。被本地删过（墓碑命中）的直接跳过，不再复活 */
    public boolean addUnique(M m) {
        if (!loaded) load();
        synchronized (lock) {
            String k = keyOf(m);
            if (tombstones.contains(k)) return false;   // 本地删过：不再收录
            if (keys.contains(k)) return false;
            keys.add(k);
            cache.add(m);
            return true;
        }
    }

    /**
     * 强制同步（full:true）专用：服务端有的条目覆盖本地同键条目，本地独有的保持不变。
     * 同键 = 同一条消息（keyOf 含 ts/from/text），差异只可能在 mine 或引用字段上。
     *
     * 这里**故意不看墓碑**：强制同步的语义就是"服务器缓存里有的，一律覆盖本地"，
     * 哪怕那条本地单独删过，也要照原样写回来。写回时顺手把墓碑撤掉，
     * 否则下次增量同步走到 addUnique/addAndRemember 又会被墓碑挡一次。
     *
     * @return 是否发生了实际变化（含"只撤了墓碑"这种情况，好让调用方落盘）
     */
    public boolean addOrUpdate(M m) {
        if (!loaded) load();
        synchronized (lock) {
            String k = keyOf(m);
            boolean hadStone = tombstones.remove(k);   // 服务器说了算：撤销本地墓碑
            int idx = indexOfKey(k);
            if (idx < 0) {
                keys.add(k);
                cache.add(m);
                return true;
            }
            M old = cache.get(idx);
            // uid / qUid 也要比：老缓存的条目没有它们，强制同步时得能把 uid 补进来，
            // 否则那些历史消息永远解不出当前昵称，改名后还是显示旧名字。
            boolean changed = !eq(old.qFrom, m.qFrom) || !eq(old.qText, m.qText)
                    || old.qTs != m.qTs || old.mine != m.mine
                    || old.uid != m.uid || old.qUid != m.qUid;
            if (!changed) return hadStone;   // 内容没变，但墓碑被撤了，也要落盘
            cache.set(idx, m);   // 覆盖：保住原来的位置
            return true;
        }
    }

    private int indexOfKey(String k) {
        for (int i = 0; i < cache.size(); i++) {
            if (keyOf(cache.get(i)).equals(k)) return i;
        }
        return -1;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public List<M> all() {
        if (!loaded) load();
        synchronized (lock) {
            return new ArrayList<>(cache);
        }
    }

    /**
     * 实时消息专用：无条件收纳，但登记去重键。
     *
     * 与 add() 的区别是它会 keys.add()。原先实时消息走 add() 不登记指纹，
     * 导致重连后服务端补推 history 时 addUnique() 查不到该键，同一条被插入两次。
     * 这里"无条件加"保住了实时消息绝不丢的原意，"登记指纹"让 history 重放能被跳过。
     */
    public boolean addAndRemember(M m) {
        if (!loaded) load();
        synchronized (lock) {
            String k = keyOf(m);
            // 唯一一处与「无条件收纳」不同的地方：本地删过/撤回过的消息就算被重推也不要。
            // 正常的新消息永远不可能命中墓碑（墓碑里只有用户主动删掉的那几条），
            // 所以这里不影响上面那条"实时消息绝不丢"的原意。
            if (tombstones.contains(k)) return false;
            keys.add(k);
            cache.add(m);
            return true;
        }
    }

    /**
     * 本地删除 / 撤回：从 cache 移除 + 记墓碑 + 落盘。
     * 只记指纹不动别人的消息，返回是否真的删掉了。
     */
    public boolean delete(M m) {
        if (!loaded) load();
        synchronized (lock) {
            String k = keyOf(m);
            tombstones.add(k);
            keys.remove(k);
            int idx = indexOfKey(k);
            if (idx >= 0) cache.remove(idx);
            save();   // save() 内部会一并落盘墓碑
            return true;
        }
    }

    /** 按 (ts, from, text) 找并删除（撤回广播用），返回是否找到并删掉 */
    public boolean deleteByTriple(long ts, String from, String text) {
        if (!loaded) load();
        synchronized (lock) {
            String k = keyOf(new M(ts, from, text, false));
            tombstones.add(k);
            keys.remove(k);
            int idx = indexOfKey(k);
            if (idx < 0) { saveTombstones(); return false; }
            cache.remove(idx);
            saveTombstones();
            save();
            return true;
        }
    }

    /**
     * 撤回时找原消息：按"由严到宽"逐级匹配。
     *
     * 为什么不能只按 (ts, from, text) 死磕 —— 这条路以前其实是**一直匹配不上**的，
     * 只是发送方那边做了"乐观本地删除"（直接拿消息对象删，必然成功），把这个洞
     * 盖住了。真正的问题有两条：
     *   1. 广播里带的是**服务端此刻的**昵称，本地消息存的是**发送那一刻**的昵称
     *      快照。中间改过名就对不上。
     *   2. text 经服务端 clean_text 洗过（截断到 100 字、去控制字符），
     *      也可能和本地存的不完全一致。
     * 所以改成优先用**不可变的 uid** 来认人，昵称和内容只当兜底。
     */
    private int findForRecall(long ts, long uid, String from, String text) {
        if (uid > 0) {
            // ① 最准：同一秒 + 同一个 uid + 内容一致
            for (int i = 0; i < cache.size(); i++) {
                M r = cache.get(i);
                if (r.ts == ts && r.uid == uid && eq(r.text, text)) return i;
            }
            // ② 次之：同一秒 + 同一个 uid（改名 / 内容被清洗过时靠这条）
            for (int i = 0; i < cache.size(); i++) {
                M r = cache.get(i);
                if (r.ts == ts && r.uid == uid) return i;
            }
        }
        // ③ 老缓存没有 uid：退回归昵称 + 内容
        for (int i = 0; i < cache.size(); i++) {
            M r = cache.get(i);
            if (r.ts == ts && eq(r.from, from) && eq(r.text, text)) return i;
        }
        // ④ 最后兜底：这一秒**只有一条**消息，那它就是被撤回的那条。
        //    只有一条才敢用 —— 多条时乱删比不删更糟。
        int only = -1, cnt = 0;
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).ts == ts) { only = i; cnt++; }
        }
        return cnt == 1 ? only : -1;
    }

    /**
     * 撤回专用：把原消息摘掉，并把「XXX 撤回了一条消息」**插在它原来的位置上**。
     *
     * 为什么不能像原来那样"删掉 + 追加提示"：消息列表用的是**到达顺序**
     * （cache 是 ArrayList，renderAll 直接按它遍历，不按 ts 排序）。
     * 追加的话，A/B/C 三条里撤回 B，提示会跑到 C 后面去 —— 而用户期望的是
     * 提示夹在 A 和 C 之间，也就是占住 B 原来的位置。
     *
     * 原消息不在本地（从没同步过、或早被清理规则裁掉了）时退化成追加，
     * 至少保证这条提示不丢。
     *
     * @return true 表示成功插回了原位；false 表示没找到原消息、只能追加
     */
    public boolean replaceWithTip(long ts, long uid, String from, String text, M tip) {
        if (!loaded) load();
        synchronized (lock) {
            int idx = findForRecall(ts, uid, from, text);
            // 墓碑按广播给的三元组记：服务端已经删了，别让后续同步又拉回来
            String k = keyOf(new M(ts, from, text, false));
            tombstones.add(k);
            keys.remove(k);

            if (idx >= 0) {
                M old = cache.get(idx);
                keys.remove(keyOf(old));     // 顺手把原消息的键也摘掉
                cache.remove(idx);
                cache.add(idx, tip);         // 顶掉原消息的位置
            } else {
                cache.add(tip);              // 原消息不在本地，只能追加
            }
            // 把提示的键也登记上：增量同步遇到同一条会跳过，
            // 强制同步也会按这个键在原位覆盖，不会又追加一条。
            keys.add(keyOf(tip));
            return idx >= 0;
        }
    }

    /** 本地最新一条消息的 ts（秒）；没有则 0 —— 增量同步的 since */
    public long latestTs() {
        if (!loaded) load();
        synchronized (lock) {
            long max = 0;
            for (M m : cache) if (m.ts > max) max = m.ts;
            return max;
        }
    }

    public void add(M m) {
        if (!loaded) load();
        synchronized (lock) {
            cache.add(m);
        }
    }

    /** 清空本地全部缓存（含落盘）。墓碑一并清掉：用户主动清空 = 一切归零，重连后重新拉 */
    public void clearAll() {
        if (!loaded) load();
        synchronized (lock) {
            cache.clear();
            keys.clear();
            tombstones.clear();
            try {
                if (file.exists()) file.delete();
            } catch (Exception ignored) { }
            try {
                if (tombFile.exists()) tombFile.delete();
            } catch (Exception ignored) { }
            generation++;
        }
    }

    /** 缓存被清空的次数（聊天页回前台时比对，决定要不要重画） */
    public int generation() {
        return generation;
    }

    /**
     * 落盘（消息 + 墓碑）。
     *
     * 行格式（\u0001 分隔，7 段）：
     *   from \u0001 text \u0001 ts \u0001 mine \u0001 qFrom \u0001 qText \u0001 qTs
     * 老文件只有前 4 段，parse() 按段数判断，缺的后 3 段按空值处理 —— 向后兼容。
     */
    public void save() {
        synchronized (lock) {
            try {
                FileOutputStream fos = new FileOutputStream(file, false);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                for (M m : cache) {
                    w.write(escape(m.from) + "\u0001" + escape(m.text) + "\u0001" + m.ts + "\u0001"
                            + (m.mine ? "1" : "0") + "\u0001"
                            + escape(m.qFrom) + "\u0001" + escape(m.qText) + "\u0001" + m.qTs + "\u0001"
                            + (m.sys ? "1" : "0") + "\u0001"
                            + m.uid + "\u0001" + m.qUid + "\n");
                }
                w.flush();
                w.close();
            } catch (Exception ignored) { }
            saveTombstones();
        }
    }

    // ---------- 墓碑 ----------

    /** 墓碑每行一个 key（escape 过，保证没有裸换行） */
    private void saveTombstones() {
        synchronized (lock) {
            // 必须先加载再写：否则 save() 早于 load() 时会用空集合把已有墓碑文件覆盖掉
            if (!tombstonesLoaded) loadTombstones();
            try {
                FileOutputStream fos = new FileOutputStream(tombFile, false);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                for (String k : tombstones) {
                    w.write(escape(k) + "\n");
                }
                w.flush();
                w.close();
            } catch (Exception ignored) { }
        }
    }

    private void loadTombstones() {
        synchronized (lock) {
            if (tombstonesLoaded) return;
            tombstones.clear();
            if (tombFile.exists()) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            new FileInputStream(tombFile), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        tombstones.add(unescape(line));
                    }
                    r.close();
                } catch (Exception ignored) { }
            }
            tombstonesLoaded = true;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\u0001", "");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n").replace("\\\\", "\\");
    }

    /** 解析一行。老格式只有 4 段（无引用字段），缺的按空值处理，不会崩 */
    private static M parse(String line) {
        try {
            String[] p = line.split("\u0001", -1);
            if (p.length < 4) return null;
            M m = new M(Long.parseLong(p[2]), unescape(p[0]), unescape(p[1]), "1".equals(p[3]));
            if (p.length >= 7) {
                m.qFrom = unescape(p[4]);
                m.qText = unescape(p[5]);
                try { m.qTs = Long.parseLong(p[6].trim()); } catch (Exception ignored) { m.qTs = 0; }
            }
            // 第 8 段是后加的"系统提示"标记；老缓存文件没有这一段，按 false 处理
            if (p.length >= 8) m.sys = "1".equals(p[7].trim());
            // 第 9、10 段是发送者 / 被引用者的 uid（改名后查当前昵称用）；老文件没有，按 0 处理
            if (p.length >= 9) m.uid = parseLong(p[8]);
            if (p.length >= 10) m.qUid = parseLong(p[9]);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    /** 已注销的账号：服务端删号时会把消息里的 uid / q_uid 改写成这个哨兵值 */
    public static final long UID_GONE = -1;

    /**
     * 服务端广播「这些 uid 已注销」时调用：把本地缓存里还挂着它们的消息就地改成 UID_GONE。
     *
     * 为什么非要在本地改一遍：增量同步走的是 addUnique（同键直接跳过），
     * 已经缓存的老消息不会因为服务端把它标成 -1 就自己更新过来。
     *
     * @return 是否真的改动了什么（决定要不要重画 / 落盘）
     */
    public boolean markDeleted(long uid) {
        if (uid <= 0) return false;
        if (!loaded) load();
        synchronized (lock) {
            boolean changed = false;
            for (M m : cache) {
                if (m.uid == uid) { m.uid = UID_GONE; changed = true; }
                if (m.qUid == uid) { m.qUid = UID_GONE; changed = true; }
            }
            return changed;
        }
    }

    // ---------- 名册（uid -> 当前昵称） ----------

    /** 记一条 uid -> 昵称（服务端下发名册时逐条灌进来） */
    public void putRoster(long uid, String name) {
        if (uid <= 0 || name == null || name.isEmpty()) return;
        loadRoster();
        synchronized (lock) { roster.put(uid, name); }
    }

    /** 落盘名册。与消息缓存分开存 —— clearAll() 清聊天记录时不该把名册也清掉 */
    public void saveRoster() {
        loadRoster();
        synchronized (lock) {
            try {
                FileOutputStream fos = new FileOutputStream(rosterFile, false);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                for (java.util.Map.Entry<Long, String> e : roster.entrySet()) {
                    w.write(e.getKey() + "\u0001" + escape(e.getValue()) + "\n");
                }
                w.flush();
                w.close();
            } catch (Exception ignored) { }
        }
    }

    public void loadRoster() {
        synchronized (lock) {
            if (rosterLoaded) return;
            roster.clear();
            if (rosterFile.exists()) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            new FileInputStream(rosterFile), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        String[] p = line.split("\u0001", -1);
                        if (p.length >= 2) {
                            long id = parseLong(p[0]);
                            if (id > 0) roster.put(id, unescape(p[1]));
                        }
                    }
                    r.close();
                } catch (Exception ignored) { }
            }
            rosterLoaded = true;
        }
    }

    // ---------- 归档名单（uid -> 已归档） ----------

    /** 服务端下发归档名单时整份替换。返回是否跟原来不一样（决定要不要重画/落盘） */
    public boolean setArchived(java.util.Collection<Long> ids) {
        loadArchived();
        synchronized (lock) {
            java.util.HashSet<Long> next = new java.util.HashSet<>();
            if (ids != null) {
                for (Long id : ids) {
                    if (id != null && id > 0) next.add(id);
                }
            }
            if (archived.equals(next)) return false;
            archived.clear();
            archived.addAll(next);
            return true;
        }
    }

    public boolean isArchived(long uid) {
        if (uid <= 0) return false;
        loadArchived();
        synchronized (lock) { return archived.contains(uid); }
    }

    /** 单独存一个文件：不动 msg_roster.txt 的行格式，免得把名册解析带崩 */
    public void saveArchived() {
        loadArchived();
        synchronized (lock) {
            try {
                FileOutputStream fos = new FileOutputStream(archivedFile, false);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                for (Long id : archived) w.write(id + "\n");
                w.flush();
                w.close();
            } catch (Exception ignored) { }
        }
    }

    public void loadArchived() {
        synchronized (lock) {
            if (archivedLoaded) return;
            archived.clear();
            if (archivedFile.exists()) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            new FileInputStream(archivedFile), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        long id = parseLong(line);
                        if (id > 0) archived.add(id);
                    }
                    r.close();
                } catch (Exception ignored) { }
            }
            archivedLoaded = true;
        }
    }

    // ---------- 已撤回的消息（给引用块用）----------

    /** 撤回标记的键：ts + uid。uid 不可变，所以改名不影响匹配 */
    private static String recallKey(long ts, long uid) {
        return ts + "" + (uid > 0 ? String.valueOf(uid) : "*");
    }

    public void markRecalled(long ts, long uid) {
        if (ts <= 0) return;
        loadRecalled();
        synchronized (lock) { recalled.add(recallKey(ts, uid)); }
    }

    /**
     * 引用的那条原消息是不是已经被撤回了。
     * uid 未知（老数据 / 广播没带）时退回只按 ts 匹配的键，宁可多标也别漏标。
     */
    public boolean isQuoteRecalled(long qTs, long qUid) {
        if (qTs <= 0) return false;
        loadRecalled();
        synchronized (lock) {
            return recalled.contains(recallKey(qTs, qUid))
                    || recalled.contains(recallKey(qTs, 0));
        }
    }

    public void saveRecalled() {
        loadRecalled();
        synchronized (lock) {
            try {
                FileOutputStream fos = new FileOutputStream(recalledFile, false);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                for (String k : recalled) w.write(escape(k) + "\n");
                w.flush();
                w.close();
            } catch (Exception ignored) { }
        }
    }

    private void loadRecalled() {
        synchronized (lock) {
            if (recalledLoaded) return;
            recalled.clear();
            if (recalledFile.exists()) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            new FileInputStream(recalledFile), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.trim().isEmpty()) recalled.add(unescape(line));
                    }
                    r.close();
                } catch (Exception ignored) { }
            }
            recalledLoaded = true;
        }
    }

    /** 名字后面挂「（用户已注销）」。硬删(UID_GONE)和归档走同一套文案 */
    private static String goneName(String snap) {
        return (snap == null || snap.isEmpty()) ? "（用户已注销）" : (snap + "（用户已注销）");
    }

    /**
     * 显示名解析：先拿 uid 去名册里查"此刻的昵称"，查不到才退回消息里存的快照名。
     *
     * 这是"改了名，历史消息也要跟着变"的唯一入口 —— 渲染层不许直接用 m.from。
     * uid == UID_GONE 是"账号已注销"；uid == 0 是"老数据没带 uid，不知道是谁"。
     */
    public String nameOf(long uid, String fallback) {
        loadRoster();
        String snap = fallback == null ? "" : fallback;
        // 账号被删了：历史昵称留着，后面挂个括号说明，别让人以为这人还在
        if (uid == UID_GONE) {
            return goneName(snap);
        }
        if (uid > 0) {
            String n;
            synchronized (lock) { n = roster.get(uid); }
            boolean named = (n != null && !n.isEmpty());
            // 归档（软删除）：消息里的 uid 还是原样，所以只能在这儿查名单判。
            // 取消归档时 uid 从名单里移走，这里立刻就不挂了 —— 无需回滚任何数据。
            //
            // 名字优先用名册里的，不是消息里存的快照：快照是"发消息那一刻"的名字，
            // 而他完全可能发完消息之后、被归档之前改过名，用快照就会把旧名字显示出来。
            // 名册里查不到（没同步过）才退回快照。
            if (isArchived(uid)) return goneName(named ? n : snap);
            if (named) return n;
        }
        return snap;
    }

    /** 这条消息发送人的显示名 */
    public String nameOf(M m) {
        return nameOf(m.uid, m.from);
    }

    /** 这条消息所引用那个人的显示名 */
    public String quoteNameOf(M m) {
        return nameOf(m.qUid, m.qFrom);
    }

    // ---------- 清理 ----------
    /** 按当前设置清理一次，返回是否发生删除 */
    public boolean clean() {
        if (!loaded) load();
        int mode = prefs.cleanMode();
        if (mode == 3) return false;  // 无上限，不清理

        synchronized (lock) {
            int before = cache.size();
            if (mode == 0) {
                int max = prefs.maxCount();
                while (cache.size() > max) cache.remove(0);
            } else if (mode == 1) {
                long maxBytes = (long) (prefs.maxMB() * 1024 * 1024);
                long total = 0;
                for (M m : cache) total += sizeOf(m);
                while (!cache.isEmpty() && total > maxBytes) {
                    total -= sizeOf(cache.remove(0));
                }
            } else if (mode == 2) {
                int days = prefs.maxDays();
                long cutoff = (System.currentTimeMillis() / 1000) - (long) days * 86400;
                while (!cache.isEmpty() && cache.get(0).ts < cutoff) cache.remove(0);
            }
            return cache.size() != before;
        }
    }

    private static long sizeOf(M m) {
        return (m.from == null ? 0 : m.from.length() * 2)
                + (m.text == null ? 0 : m.text.length() * 2) + 32;
    }

    // ---------- 时间显示规则 ----------
    /**
     * 时间分隔条的文案。只在「与上一条消息间隔超过 5 分钟」时才被调用，
     * 所以精度按需给：今天=HH:mm / 昨天=昨天 HH:mm / 更早=MM-DD HH:mm。
     */
    public static String fmtTime(long sec) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(sec * 1000);

        int d0 = dayIndex(now);
        int d1 = dayIndex(t);
        String hm = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(sec * 1000));

        if (d0 == d1) return hm;
        if (d0 - d1 == 1) return "昨天 " + hm;
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(sec * 1000));
    }

    /** 聊天区两条消息之间超过这个间隔才插时间分隔（秒） */
    public static final long TIME_GAP_SEC = 5 * 60;

    private static int dayIndex(Calendar c) {
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }
}