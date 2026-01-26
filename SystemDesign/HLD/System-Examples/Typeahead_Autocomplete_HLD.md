# Typeahead / Autocomplete - High Level Design

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Capacity Estimation](#capacity-estimation)
3. [High-Level Design](#high-level-design)
4. [Detailed Component Design](#detailed-component-design)
5. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
6. [Failure Scenarios](#failure-scenarios)
7. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **Suggestion Display**
   - Show top suggestions as user types
   - Update suggestions with each keystroke
   - Support fuzzy matching / typo tolerance

2. **Ranking**
   - Rank by popularity / relevance
   - Personalize suggestions per user
   - Boost trending queries

3. **Data Sources**
   - Historical search queries
   - Product names, categories
   - User-specific history

4. **Multi-language Support**
   - Support international characters
   - Language-specific suggestions

### Non-Functional Requirements

1. **Latency**: < 100ms (ideally < 50ms)
2. **Availability**: 99.99% uptime
3. **Scale**: Handle millions of queries/second
4. **Freshness**: New popular terms within minutes
5. **Consistency**: Eventual consistency acceptable

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- DAU: 500 Million
- Searches per user: 5
- Average characters per search: 20

Suggestions queries:
- Each keystroke = 1 query
- 500M * 5 * 20 = 50 Billion suggestions/day
- Peak QPS: 50B / 86400 * 3 = 1.7 Million QPS

With debouncing (200ms):
- Effective: ~10 Billion/day
- Peak: ~350K QPS
```

### Storage Estimates

```
Suggestion corpus:
- Unique queries: 1 Billion
- Average query length: 30 characters
- Raw storage: 30 GB

With metadata (count, timestamp):
- Per entry: 50 bytes
- Total: 50 GB

Trie representation:
- Nodes: ~5 Billion (5x unique queries)
- Per node: 20 bytes
- Total: 100 GB
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                               CLIENT                                                 │
│                                                                                     │
│  [Search Box]                                                                       │
│       │                                                                             │
│       │ User types "how t"                                                          │
│       │                                                                             │
│       ▼                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  CLIENT-SIDE LOGIC                                                           │   │
│  │  - Debounce (200ms)                                                          │   │
│  │  - Local cache (recent suggestions)                                          │   │
│  │  - Show cached while fetching                                                │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                    HTTPS GET
                           /suggest?q=how+t&limit=10
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                               CDN / EDGE                                             │
│                                                                                     │
│  - Cache popular prefixes (TTL: 5 min)                                              │
│  - 70%+ requests served from edge                                                   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                    Cache MISS
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           LOAD BALANCER                                              │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                       SUGGESTION SERVERS                                             │
│                                                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│  │  Server 1   │  │  Server 2   │  │  Server 3   │  │  Server N   │               │
│  │             │  │             │  │             │  │             │               │
│  │ In-memory   │  │ In-memory   │  │ In-memory   │  │ In-memory   │               │
│  │ Trie        │  │ Trie        │  │ Trie        │  │ Trie        │               │
│  │             │  │             │  │             │  │             │               │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘               │
│                                                                                     │
│  All servers have same data (replicated)                                           │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                    Data sync
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                        DATA AGGREGATION LAYER                                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         KAFKA                                                │   │
│  │              (Search query stream)                                           │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                              │                                                      │
│                              ▼                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                    AGGREGATION SERVICE                                       │   │
│  │                                                                              │   │
│  │  - Count queries (rolling window)                                           │   │
│  │  - Detect trending                                                           │   │
│  │  - Filter spam/offensive                                                    │   │
│  │  - Build updated trie                                                        │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                              │                                                      │
│                              ▼                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                      TRIE DATABASE                                           │   │
│  │                   (Cassandra / Redis)                                        │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **Client**: Debouncing, local cache
2. **CDN**: Edge caching for popular prefixes
3. **Suggestion Servers**: In-memory trie lookup
4. **Data Aggregation**: Process search logs, update corpus
5. **Trie Database**: Persistent storage of trie data

---

## Detailed Component Design

### 1. Trie Data Structure

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              TRIE STRUCTURE                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Example: Storing "how", "how to", "how to cook", "home"                           │
│                                                                                     │
│                          (root)                                                     │
│                            │                                                        │
│                            h                                                        │
│                            │                                                        │
│                            o                                                        │
│                          /   \                                                      │
│                         w     m                                                     │
│                        /│      │                                                    │
│                       ' e      e                                                    │
│                       │        │                                                    │
│                       t        $[home: 50K]                                         │
│                       │                                                             │
│                       o                                                             │
│                       │                                                             │
│                       $[how to: 100K]                                              │
│                       │                                                             │
│                       ' '                                                           │
│                       │                                                             │
│                       c                                                             │
│                       │                                                             │
│                       o                                                             │
│                       │                                                             │
│                       o                                                             │
│                       │                                                             │
│                       k                                                             │
│                       │                                                             │
│                       $[how to cook: 30K]                                          │
│                                                                                     │
│  Each node stores:                                                                  │
│  - Character                                                                        │
│  - Children (map or array)                                                          │
│  - isEndOfWord flag                                                                 │
│  - Top suggestions for this prefix (pre-computed)                                  │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Optimized Trie Implementation:**

```python
class TrieNode:
    def __init__(self):
        self.children = {}  # char -> TrieNode
        self.is_end = False
        self.count = 0
        # Pre-computed top suggestions for this prefix
        self.top_suggestions = []  # [(query, count), ...]

class Trie:
    def __init__(self, top_k=10):
        self.root = TrieNode()
        self.top_k = top_k
    
    def insert(self, query, count):
        node = self.root
        for char in query.lower():
            if char not in node.children:
                node.children[char] = TrieNode()
            node = node.children[char]
            # Update top suggestions at each prefix
            self._update_suggestions(node, query, count)
        
        node.is_end = True
        node.count = count
    
    def _update_suggestions(self, node, query, count):
        # Maintain sorted list of top K
        suggestions = node.top_suggestions
        
        # Check if query already exists
        for i, (q, c) in enumerate(suggestions):
            if q == query:
                suggestions[i] = (query, count)
                suggestions.sort(key=lambda x: -x[1])
                return
        
        # Add new suggestion
        suggestions.append((query, count))
        suggestions.sort(key=lambda x: -x[1])
        
        # Keep only top K
        if len(suggestions) > self.top_k:
            suggestions.pop()
    
    def get_suggestions(self, prefix, limit=10):
        node = self.root
        for char in prefix.lower():
            if char not in node.children:
                return []  # No suggestions
            node = node.children[char]
        
        # Return pre-computed suggestions
        return node.top_suggestions[:limit]
```

### 2. Pre-computed Suggestions

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    PRE-COMPUTED SUGGESTIONS                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Why pre-compute:                                                                   │
│  - Lookup is O(L) where L = prefix length                                          │
│  - Without pre-compute: O(L + N) where N = all suggestions                        │
│  - With pre-compute: O(L) + O(K) where K = top suggestions (small)                │
│                                                                                     │
│  Trade-off:                                                                         │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Pre-compute at each node       │  Compute on-the-fly                      │   │
│  │  - More memory                  │  - Less memory                           │   │
│  │  - Faster queries               │  - Slower queries                        │   │
│  │  - Good for hot prefixes        │  - Good for long tail                   │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Hybrid approach:                                                                   │
│  - Pre-compute for prefixes up to 3 characters                                     │
│  - Compute on-the-fly for longer prefixes                                          │
│                                                                                     │
│  Example memory usage:                                                              │
│  - 26^3 = 17,576 possible 3-char prefixes                                         │
│  - Top 10 suggestions per prefix                                                   │
│  - 10 * 50 bytes = 500 bytes per prefix                                           │
│  - Total: ~9 MB for 3-char prefix cache                                           │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Data Collection Pipeline

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                       DATA COLLECTION PIPELINE                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  User Search ──► Kafka ──► Flink/Spark Streaming ──► Aggregation ──► Trie Update  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  1. Collect search queries                                                   │   │
│  │                                                                              │   │
│  │  {                                                                           │   │
│  │    "query": "how to cook pasta",                                            │   │
│  │    "user_id": "u123",                                                       │   │
│  │    "timestamp": 1705312800,                                                 │   │
│  │    "region": "US",                                                          │   │
│  │    "device": "mobile"                                                       │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  2. Aggregate (tumbling window: 1 minute)                                   │   │
│  │                                                                              │   │
│  │  class QueryAggregator:                                                      │   │
│  │      def process(self, stream):                                              │   │
│  │          return stream                                                       │   │
│  │              .window(TumblingWindow.of(Duration.minutes(1)))                │   │
│  │              .group_by('query')                                              │   │
│  │              .aggregate(count())                                             │   │
│  │              .filter(lambda x: x.count > MIN_THRESHOLD)                     │   │
│  │              .filter(lambda x: not is_spam(x.query))                        │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  3. Update scoring                                                          │   │
│  │                                                                              │   │
│  │  score = recent_count * 0.6 + historical_count * 0.3 + trending_boost * 0.1│   │
│  │                                                                              │   │
│  │  - recent_count: Last 24 hours                                              │   │
│  │  - historical_count: All time (decayed)                                     │   │
│  │  - trending_boost: Velocity of increase                                     │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  4. Build new trie (periodic: every 5 minutes)                              │   │
│  │                                                                              │   │
│  │  - Build new trie in background                                             │   │
│  │  - Atomic swap with old trie                                                │   │
│  │  - Broadcast to all suggestion servers                                      │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 4. Personalization

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          PERSONALIZATION                                             │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Two-layer approach:                                                                │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Layer 1: Global suggestions (from trie)                                    │   │
│  │  - Most popular queries for prefix                                          │   │
│  │  - Same for all users                                                       │   │
│  │                                                                              │   │
│  │  Layer 2: Personal suggestions (from user history)                          │   │
│  │  - User's past searches                                                     │   │
│  │  - Stored in Redis: user_id -> [recent_queries]                            │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Merge algorithm:                                                                   │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  def get_personalized_suggestions(prefix, user_id, limit=10):               │   │
│  │      # Get global suggestions                                               │   │
│  │      global_suggestions = trie.get_suggestions(prefix, limit=limit*2)      │   │
│  │                                                                              │   │
│  │      # Get user's matching history                                          │   │
│  │      user_history = redis.get(f"history:{user_id}")                        │   │
│  │      matching_history = [q for q in user_history if q.startswith(prefix)]  │   │
│  │                                                                              │   │
│  │      # Merge with boosting for personal                                     │   │
│  │      merged = []                                                             │   │
│  │      for query, score in global_suggestions:                                │   │
│  │          if query in matching_history:                                      │   │
│  │              score *= 2  # Boost personal matches                           │   │
│  │          merged.append((query, score))                                      │   │
│  │                                                                              │   │
│  │      # Add personal queries not in global                                   │   │
│  │      for query in matching_history:                                         │   │
│  │          if query not in [q for q, s in merged]:                           │   │
│  │              merged.append((query, PERSONAL_BASE_SCORE))                   │   │
│  │                                                                              │   │
│  │      # Sort and return top                                                   │   │
│  │      merged.sort(key=lambda x: -x[1])                                       │   │
│  │      return merged[:limit]                                                   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### Data Structure Comparison

| Structure | Lookup | Memory | Fuzzy Match |
|-----------|--------|--------|-------------|
| Trie | O(L) | High | No |
| Ternary Search Tree | O(L) | Medium | No |
| Sorted Array + Binary Search | O(L log N) | Low | No |
| Elasticsearch | O(1)* | Very High | Yes |

**Choice:** Trie for exact prefix matching, Elasticsearch for fuzzy/typo tolerance

### Caching Strategy

| Level | What | TTL | Hit Rate |
|-------|------|-----|----------|
| Browser | User's recent | 5 min | 20% |
| CDN | Popular prefixes | 1 min | 60% |
| App Cache | All prefixes | 30 sec | 15% |
| Trie | Full corpus | Updated every 5 min | 5% |

### Replication vs Sharding

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    REPLICATION vs SHARDING                                           │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Replication (Recommended for typeahead):                                           │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Server 1: Full trie                                                        │   │
│  │  Server 2: Full trie (replica)                                              │   │
│  │  Server 3: Full trie (replica)                                              │   │
│  │                                                                              │   │
│  │  Pros: Any server can answer any query                                      │   │
│  │  Cons: Each server needs full dataset in memory                            │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Sharding (For very large corpus):                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Server 1: a-f prefixes                                                     │   │
│  │  Server 2: g-l prefixes                                                     │   │
│  │  Server 3: m-r prefixes                                                     │   │
│  │  Server 4: s-z prefixes                                                     │   │
│  │                                                                              │   │
│  │  Pros: Larger corpus supported                                              │   │
│  │  Cons: Need to route to correct shard                                      │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  For 100GB trie: Replication (servers have 128GB+ RAM)                            │
│  For 1TB+ trie: Sharding by prefix range                                          │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Failure Scenarios

### High Latency

```
Problem: Suggestions take > 100ms

Mitigations:
1. Debounce on client (don't send every keystroke)
2. Cache at CDN (70%+ hit rate)
3. Pre-compute top suggestions
4. Serve stale while refreshing
5. Timeout and return partial results
```

### Stale Data

```
Problem: New trending term not showing

Solution: Multi-tier freshness
1. Hot terms: Update every minute
2. Regular terms: Update every 5 minutes
3. Long tail: Update every hour

Emergency update path:
- Admin can push immediate trie update
- Used for breaking news, major events
```

---

## Interviewer Questions & Answers

### Q1: Why use a Trie instead of a database?

**Answer:**

**Trie advantages for typeahead:**

1. **Optimized for prefix matching:**
```python
# Trie: O(L) where L = prefix length
def search_trie(prefix):
    node = root
    for char in prefix:
        node = node.children[char]
    return node.suggestions  # Pre-computed!

# Database: O(N log N) or requires index
SELECT query FROM suggestions 
WHERE query LIKE 'how t%'
ORDER BY count DESC LIMIT 10;
```

2. **In-memory, no network hop:**
```
Trie lookup: ~0.1ms
DB query: ~5-10ms (even with index)
```

3. **Pre-computed results:**
- Top suggestions stored at each node
- No sorting needed at query time

**When to use database:**
- Full-text search with fuzzy matching
- Very large corpus that doesn't fit in memory
- Complex ranking with many factors

---

### Q2: How do you handle typos and misspellings?

**Answer:**

**Multiple approaches:**

1. **Fuzzy matching with edit distance:**
```python
def fuzzy_suggestions(prefix, max_edit_distance=2):
    candidates = []
    
    def dfs(node, current_prefix, edits_remaining):
        if edits_remaining < 0:
            return
        
        # Found a match
        if node.is_end:
            candidates.append(current_prefix)
        
        for char, child in node.children.items():
            # Exact match
            dfs(child, current_prefix + char, edits_remaining)
            
            # Substitution
            for c in 'abcdefghijklmnopqrstuvwxyz':
                if c != char:
                    dfs(child, current_prefix + c, edits_remaining - 1)
            
            # Insertion
            dfs(node, current_prefix + char, edits_remaining - 1)
            
            # Deletion
            dfs(child, current_prefix, edits_remaining - 1)
    
    dfs(root, "", max_edit_distance)
    return candidates
```

2. **Phonetic matching (Soundex/Metaphone):**
```python
# "john" and "jon" have same soundex
def soundex_lookup(prefix):
    code = soundex(prefix)
    return soundex_index.get(code, [])
```

3. **Elasticsearch for complex fuzzy:**
```json
{
  "query": {
    "match": {
      "query": {
        "query": "hwo to",
        "fuzziness": "AUTO"
      }
    }
  }
}
```

---

### Q3: How do you rank suggestions?

**Answer:**

**Multi-factor ranking:**

```python
def calculate_score(query, user_context):
    score = 0
    
    # Base popularity (most important)
    score += log(query.search_count) * 0.4
    
    # Recency (trending boost)
    recent_count = get_recent_count(query, hours=24)
    historical_avg = query.search_count / query.age_days
    trending_ratio = recent_count / max(historical_avg, 1)
    if trending_ratio > 2:
        score += trending_ratio * 0.2
    
    # Personalization
    if query in user_context.history:
        score += 0.2
    if query in user_context.frequently_searched:
        score += 0.1
    
    # Query quality
    if query.has_good_results:  # Historical click-through
        score += 0.1
    
    # Context
    if user_context.device == 'mobile':
        if len(query) < 30:  # Shorter queries on mobile
            score += 0.05
    
    return score
```

**Real-time trending:**
```python
class TrendingDetector:
    def __init__(self, window_minutes=60):
        self.windows = defaultdict(lambda: deque())
        
    def record(self, query):
        now = time.time()
        self.windows[query].append(now)
        
        # Remove old entries
        cutoff = now - self.window_minutes * 60
        while self.windows[query] and self.windows[query][0] < cutoff:
            self.windows[query].popleft()
    
    def is_trending(self, query):
        current_rate = len(self.windows[query])
        baseline = self.get_baseline(query)
        return current_rate > baseline * 3
```

---

### Q4: How do you update the trie without downtime?

**Answer:**

**Double-buffering approach:**

```python
class TrieManager:
    def __init__(self):
        self.active_trie = Trie()
        self.building_trie = None
        self.lock = RWLock()
    
    def query(self, prefix):
        with self.lock.read():
            return self.active_trie.get_suggestions(prefix)
    
    async def rebuild(self, new_data):
        # Build new trie in background
        self.building_trie = Trie()
        for query, count in new_data:
            self.building_trie.insert(query, count)
        
        # Atomic swap
        with self.lock.write():
            old_trie = self.active_trie
            self.active_trie = self.building_trie
            self.building_trie = None
        
        # Clean up old trie
        del old_trie
```

**Distributed update:**
```
1. Build new trie on aggregation service
2. Serialize and upload to blob storage
3. Notify all servers via Kafka
4. Each server downloads and swaps atomically
5. Staggered rollout (canary)
```

---

### Q5: How do you handle multiple languages?

**Answer:**

**Approaches:**

1. **Separate tries per language:**
```python
class MultiLanguageSuggester:
    def __init__(self):
        self.tries = {
            'en': Trie(),
            'es': Trie(),
            'zh': Trie(),
            # ...
        }
    
    def get_suggestions(self, prefix, language='en'):
        return self.tries[language].get_suggestions(prefix)
```

2. **Language detection:**
```python
def detect_language(prefix):
    # Check character set
    if any('\u4e00' <= c <= '\u9fff' for c in prefix):
        return 'zh'  # Chinese
    if any('\u0400' <= c <= '\u04ff' for c in prefix):
        return 'ru'  # Russian
    
    # Use language detection library for others
    return langdetect.detect(prefix)
```

3. **Mixed language (e.g., Spanglish):**
- Maintain cross-lingual suggestions
- Map common transliterations

---

### Q6: How do you handle offensive/inappropriate suggestions?

**Answer:**

**Multi-layer filtering:**

```python
class ContentFilter:
    def __init__(self):
        self.blocklist = load_blocklist()
        self.ml_classifier = load_offensive_classifier()
    
    def is_allowed(self, query):
        # Layer 1: Exact blocklist match
        if query.lower() in self.blocklist:
            return False
        
        # Layer 2: Substring check
        for blocked in self.blocklist:
            if blocked in query.lower():
                return False
        
        # Layer 3: ML classification
        if self.ml_classifier.predict(query) == 'offensive':
            return False
        
        return True
    
    def filter_suggestions(self, suggestions):
        return [s for s in suggestions if self.is_allowed(s)]
```

**Additional measures:**
- User reporting mechanism
- Periodic audit of top suggestions
- Regional variations (different standards)

---

### Q7: How do you optimize for mobile?

**Answer:**

**Mobile-specific optimizations:**

1. **Aggressive debouncing:**
```javascript
// Web: 200ms debounce
// Mobile: 300ms debounce (slower typing)
const DEBOUNCE_MS = isMobile ? 300 : 200;
```

2. **Prefetch on focus:**
```javascript
searchBox.addEventListener('focus', () => {
    // Prefetch popular suggestions
    fetch('/suggest?q=&limit=10');
});
```

3. **Bandwidth optimization:**
```python
# Shorter responses for mobile
def format_response(suggestions, is_mobile):
    if is_mobile:
        return [{
            'q': s['query'][:50],  # Truncate
            's': s['score']
        } for s in suggestions[:5]]  # Fewer suggestions
    else:
        return suggestions[:10]
```

4. **Local caching:**
```javascript
// Cache recent suggestions in localStorage
const cache = new LRUCache(100);

async function getSuggestions(prefix) {
    const cached = cache.get(prefix);
    if (cached) return cached;
    
    const result = await fetch(`/suggest?q=${prefix}`);
    cache.set(prefix, result);
    return result;
}
```

---

### Q8: How do you measure success?

**Answer:**

**Key metrics:**

```python
class TypeaheadMetrics:
    def track(self, event):
        # Latency
        metrics.timing('typeahead.latency', event.latency_ms)
        
        # Cache hit rate
        metrics.increment('typeahead.cache_hit' if event.from_cache else 'typeahead.cache_miss')
        
        # Suggestion acceptance
        if event.suggestion_clicked:
            metrics.increment('typeahead.accepted')
            metrics.histogram('typeahead.position', event.clicked_position)
        
        # Abandonment
        if event.typed_full_query:  # Didn't use suggestion
            metrics.increment('typeahead.abandoned')
```

**Success indicators:**
- p99 latency < 100ms
- Cache hit rate > 80%
- Suggestion acceptance rate > 30%
- Mean position of clicked suggestion < 3

---

### Q9: Design for a specific use case: E-commerce product search.

**Answer:**

**Product-specific considerations:**

```python
class ProductTypeahead:
    def __init__(self):
        self.product_trie = Trie()  # Product names
        self.category_trie = Trie()  # Categories
        self.brand_trie = Trie()  # Brands
    
    def get_suggestions(self, prefix, user_context):
        suggestions = []
        
        # Products matching prefix
        products = self.product_trie.get_suggestions(prefix, limit=5)
        suggestions.extend([{
            'type': 'product',
            'value': p.name,
            'image': p.thumbnail,
            'price': p.price
        } for p in products])
        
        # Categories
        categories = self.category_trie.get_suggestions(prefix, limit=2)
        suggestions.extend([{
            'type': 'category',
            'value': c.name,
            'count': c.product_count
        } for c in categories])
        
        # Brands
        brands = self.brand_trie.get_suggestions(prefix, limit=2)
        suggestions.extend([{
            'type': 'brand',
            'value': b.name
        } for b in brands])
        
        # Personalize based on user's browsing history
        return self.personalize(suggestions, user_context)
```

**Rich suggestions UI:**
```
┌─────────────────────────────────────────────────────────────────┐
│ Search: "iph"                                                    │
├─────────────────────────────────────────────────────────────────┤
│ 🔍 PRODUCTS                                                      │
│  📱 iPhone 15 Pro                      $999                     │
│  📱 iPhone 15                          $799                     │
│                                                                 │
│ 📂 CATEGORIES                                                    │
│  Smartphones > iPhone (1,234 products)                          │
│                                                                 │
│ 🏷️ BRANDS                                                       │
│  Apple iPhone                                                   │
│                                                                 │
│ 🕒 RECENT SEARCHES                                              │
│  iphone case                                                    │
└─────────────────────────────────────────────────────────────────┘
```

---

### Q10: How would you implement "Did you mean?" for misspelled queries?

**Answer:**

**Spell correction approach:**

```python
class SpellCorrector:
    def __init__(self):
        self.word_freq = {}  # word -> frequency
        self.load_dictionary()
    
    def correct(self, query):
        words = query.split()
        corrected = []
        
        for word in words:
            if word in self.word_freq:
                corrected.append(word)  # Known word
            else:
                # Find closest match
                candidates = self.get_candidates(word)
                if candidates:
                    best = max(candidates, key=lambda w: self.word_freq.get(w, 0))
                    if self.word_freq.get(best, 0) > THRESHOLD:
                        corrected.append(best)
                    else:
                        corrected.append(word)  # Keep original
                else:
                    corrected.append(word)
        
        corrected_query = ' '.join(corrected)
        if corrected_query != query:
            return corrected_query
        return None
    
    def get_candidates(self, word, max_distance=2):
        # Generate all strings within edit distance
        candidates = set()
        
        # Edit distance 1
        for i in range(len(word)):
            # Deletions
            candidates.add(word[:i] + word[i+1:])
            # Substitutions
            for c in 'abcdefghijklmnopqrstuvwxyz':
                candidates.add(word[:i] + c + word[i+1:])
            # Insertions
            for c in 'abcdefghijklmnopqrstuvwxyz':
                candidates.add(word[:i] + c + word[i:])
        
        # Filter to known words
        return [w for w in candidates if w in self.word_freq]
```

**Integration with typeahead:**
```python
def get_suggestions_with_correction(prefix):
    suggestions = trie.get_suggestions(prefix)
    
    if len(suggestions) < 3:  # Possibly misspelled
        corrected = spell_corrector.correct(prefix)
        if corrected:
            corrected_suggestions = trie.get_suggestions(corrected)
            return {
                'suggestions': suggestions,
                'did_you_mean': corrected,
                'corrected_suggestions': corrected_suggestions
            }
    
    return {'suggestions': suggestions}
```

---

### Bonus: Quick Reference

```
┌─────────────────────────────────────────────────────────────────┐
│              TYPEAHEAD - QUICK REFERENCE                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Data Structure: Trie with pre-computed suggestions             │
│  Latency target: < 100ms (ideally < 50ms)                      │
│                                                                 │
│  Optimizations:                                                 │
│  ├── Client debounce (200ms)                                   │
│  ├── CDN caching (popular prefixes, 1-5 min TTL)              │
│  ├── In-memory trie (no DB hit)                                │
│  └── Pre-computed top-K at each node                           │
│                                                                 │
│  Personalization:                                               │
│  ├── User history in Redis                                     │
│  └── Boost personal + global merge                             │
│                                                                 │
│  Data pipeline:                                                 │
│  ├── Kafka → Flink (real-time aggregation)                    │
│  ├── Build new trie every 5 min                                │
│  └── Atomic swap (double-buffering)                            │
│                                                                 │
│  Ranking formula:                                               │
│  score = popularity * 0.4 + trending * 0.2 +                   │
│          personal * 0.2 + recency * 0.2                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
