# 模块五：Redis Lua 原子进度

## 一、问题是什么？

分片上传过程中，需要记录每个分片上传了没有。看起来很简单，但原方案有坑：

### 原方案怎么做的

- `markDone`：标记分片完成，用 HSET
- `doneCount`：统计已完成数，用 HGETALL 遍历所有 field

### 原方案三个问题

1. **两次 RTT 慢**：`markDone` + `doneCount` 是两次 Redis 请求，网络往返 2 次 RTT
2. **HGETALL 统计是 O(N)**：要遍历所有 field 才能数出有多少个完成了，分片越多越慢
3. **竞态条件**：`markDone` 和 `doneCount` 不是原子操作，中间可能被别人插队

> 🧠 大白话：你先标记一个分片完成，再去数数，这中间别人可能也在标记，你数出来的数就不准了。就像你一边数钱，一边有人往里塞钱，数出来的结果能对吗？

---

## 二、Lua 脚本方案

核心代码 `ProgressStore.java`：

```java
private static final RedisScript<Long> MARK_DONE_SCRIPT = new DefaultRedisScript<>(
        "local added = redis.call('HSETNX', KEYS[1], ARGV[1], '1') " +
                "local count " +
                "if added == 1 then " +
                "  count = redis.call('HINCRBY', KEYS[1], '__count__', 1) " +
                "else " +
                "  count = tonumber(redis.call('HGET', KEYS[1], '__count__')) or 0 " +
                "end " +
                "redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
                "return count",
        Long.class);

public int markDone(String fileId, int chunkIndex, Duration ttl) {
    Long v = redis.raw().execute(MARK_DONE_SCRIPT,
            List.of(RedisKeys.uploadProgress(fileId)),
            String.valueOf(chunkIndex),
            String.valueOf(Math.max(1, ttl.toSeconds())));
    return v == null ? 0 : v.intValue();
}
```

### 逐行大白话解释

| 步骤 | 代码 | 干啥的 |
|------|------|--------|
| 1️⃣ | `HSETNX KEYS[1] ARGV[1] '1'` | 标记分片完成。如果 field 已经存在，就不操作，天然幂等（不会重复计数） |
| 2️⃣ | `HINCRBY KEYS[1] '__count__' 1` | 原子计数器 +1。**只有新增时才加**，如果分片已经标记过（HSETNX 返回 0），就不加了 |
| 3️⃣ | `HGET KEYS[1] '__count__'` | 如果 HSETNX 发现已经存在了（幂等），直接读计数器，不重复加 |
| 4️⃣ | `EXPIRE KEYS[1] ARGV[2]` | 续期 TTL。每次标记都续期，避免 Redis 进度过期丢失 |
| 5️⃣ | `return count` | 返回当前已完成数，**O(1)** 直接读，不用遍历 |

> 🧠 大白话：整个脚本就像去银行办业务——"登记" + "翻牌号" + "续期" 一步到位，不用排队三次。而且如果已经登记过，不会重复登记，翻牌号也不会重复加。

---

## 三、Redis Hash 内部结构

一个文件的上传进度用一个 Hash 存，key 是 `cc:upload:progress:{fileId}`：

```
cc:upload:progress:{fileId}
  field       | value | 说明
  ------------|-------|---------------------------
  "0"         | "1"   | 分片 0 已完成
  "1"         | "1"   | 分片 1 已完成
  "__total__" | "100" | 总分片数
  "__count__" | "2"   | 已完成数（O(1) 查进度）
```

> 🧠 大白话：就像一个签到表，每个分片一个格子，签到了就写 1。最后两个特殊格子：`__total__` 是总人数，`__count__` 是已签到人数。查进度直接看 `__count__`，不用一个一个数。

---

## 四、优化对比表（面试必背）

| 优化点 | 原方案 | 现方案 |
|--------|--------|--------|
| 查进度 | HGETALL → O(N) | 读 `__count__` → O(1) |
| 标记完成 | HSET + HINCRBY → 2 RTT | Lua → 1 RTT |
| 幂等 | 外部 if 判断（有竞态） | HSETNX 原子（无竞态） |

> 🧠 记忆口诀：**O(1) 查进度，1 RTT 搞定，原子性不丢分片**

---

## 五、面试追问

| 问题 | 回答要点 |
|------|----------|
| Lua 脚本在 Redis Cluster 能跑吗？ | 可以，脚本只操作一个 KEY，天然同 slot，不跨 slot |
| 为什么用 Lua 脚本？ | 原子性 + 减少 RTT + O(1) 查进度 |
| HSETNX 是什么？ | 只在 field 不存在时设置，天然幂等，不会重复计数 |
| `__count__` 是什么？ | 原子计数器，用 HINCRBY 递增，O(1) 查进度，不用 HGETALL 遍历 |
| 为什么不用 HGETALL 统计？ | O(N) 复杂度，1000 个分片就要遍历 1000 个 field |
| Lua 脚本怎么保证原子性？ | Redis 单线程执行 Lua 脚本，脚本执行期间不中断 |
| HSETNX 返回 0 和 1 分别代表什么？ | 1 = field 不存在，设置成功（新分片）；0 = field 已存在，不操作（重复上传） |

---

## 六、面试口述模板

> "分片进度用 Redis Hash 存，每个分片一个 field。原方案是 HSET + HINCRBY 两次 RTT + HGETALL O(N) 统计完成数，还有竞态问题。改用 Lua 脚本：HSETNX 防重 + HINCRBY __count__ 原子计数 + EXPIRE 续期，一次 RTT 完成。查进度直接读 `__count__`，O(1)。Lua 在 Redis Cluster 兼容，因为脚本只操作一个 KEY，天然同 slot。"
