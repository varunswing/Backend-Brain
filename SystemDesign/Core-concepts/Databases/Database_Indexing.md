# Database Indexing

## What is an Index?

An index is a data structure that improves the speed of data retrieval operations on a database table at the cost of additional storage space and write performance.

```
Without Index:              With Index:
┌─────────────────┐         ┌─────────────────┐
│ Full Table Scan │         │ Index Lookup    │
│ O(n) - scan all │         │ O(log n) - fast │
└─────────────────┘         └─────────────────┘
```

---

## How Indexes Work

### Analogy: Book Index
```
Book without index:
  → Read every page to find "Database"
  
Book with index:
  → Look up "Database" in index → Page 42
  → Go directly to page 42
```

### Database Example
```sql
-- Without index: Full table scan
SELECT * FROM users WHERE email = 'john@example.com';
-- Scans millions of rows

-- With index on email: Index seek
CREATE INDEX idx_email ON users(email);
SELECT * FROM users WHERE email = 'john@example.com';
-- Looks up index, finds row pointer, retrieves row
```

---

## Types of Indexes

### 1. B-Tree Index (Most Common)

**Structure**: Balanced tree with sorted keys.

```
                    [M]
                   /   \
              [D,H]     [R,X]
             /  |  \    /  |  \
          [A-C][E-G][I-L][N-Q][S-W][Y-Z]
```

**Characteristics**:
- Self-balancing
- O(log n) lookups
- Good for: =, <, >, <=, >=, BETWEEN, LIKE 'prefix%'

**Best For**:
- Range queries
- Equality lookups
- Sorting
- Most general-purpose use

```sql
-- B-Tree is good for:
SELECT * FROM orders WHERE created_at BETWEEN '2024-01-01' AND '2024-12-31';
SELECT * FROM users WHERE name LIKE 'John%';
SELECT * FROM products ORDER BY price;
```

### 2. Hash Index

**Structure**: Hash table mapping keys to row locations.

```
hash('john@example.com') → bucket_42 → row_pointer
hash('jane@example.com') → bucket_17 → row_pointer
```

**Characteristics**:
- O(1) exact lookups
- Cannot do range queries
- Cannot be used for sorting

**Best For**:
- Exact match queries only
- Primary key lookups

```sql
-- Hash index is good for:
SELECT * FROM users WHERE id = 12345;
SELECT * FROM cache WHERE key = 'session_abc';

-- Hash index CANNOT help with:
SELECT * FROM users WHERE id > 100;  -- Range query
SELECT * FROM users ORDER BY id;      -- Sorting
```

### 3. Composite Index (Multi-Column)

**Structure**: Index on multiple columns.

```sql
CREATE INDEX idx_name_age ON users(last_name, first_name, age);
```

**Key Rule**: Leftmost Prefix
```
Index on (A, B, C) can be used for:
✓ WHERE A = ?
✓ WHERE A = ? AND B = ?
✓ WHERE A = ? AND B = ? AND C = ?

Cannot be efficiently used for:
✗ WHERE B = ?           -- Skips A
✗ WHERE C = ?           -- Skips A, B
✗ WHERE B = ? AND C = ? -- Skips A
```

**Example**:
```sql
-- Index on (country, city, zipcode)

-- Uses index (full):
SELECT * FROM addresses WHERE country = 'US' AND city = 'NYC' AND zipcode = '10001';

-- Uses index (partial):
SELECT * FROM addresses WHERE country = 'US' AND city = 'NYC';
SELECT * FROM addresses WHERE country = 'US';

-- Cannot use index effectively:
SELECT * FROM addresses WHERE city = 'NYC';  -- Country missing
SELECT * FROM addresses WHERE zipcode = '10001';  -- Country, city missing
```

### 4. Covering Index

An index that contains all columns needed for a query (no table lookup required).

```sql
-- Query
SELECT first_name, last_name FROM users WHERE email = 'john@example.com';

-- Regular index (requires table lookup)
CREATE INDEX idx_email ON users(email);

-- Covering index (no table lookup needed)
CREATE INDEX idx_email_covering ON users(email, first_name, last_name);
```

**Benefit**: Avoids expensive random I/O to table.

### 5. Clustered vs Non-Clustered

**Clustered Index**:
- Table data is physically sorted by index
- Only ONE per table (usually primary key)
- Faster for range queries on clustered column

**Non-Clustered Index**:
- Separate structure pointing to table rows
- Multiple per table
- Extra lookup to get actual data

```
Clustered Index:
┌─────────────────────────────────┐
│ Index IS the table (sorted)     │
│ [1, data] [2, data] [3, data]   │
└─────────────────────────────────┘

Non-Clustered Index:
┌───────────────┐      ┌─────────────┐
│ Index         │ ──→  │ Table       │
│ [email → ptr] │      │ [actual row]│
└───────────────┘      └─────────────┘
```

### 6. Other Index Types

| Type | Use Case |
|------|----------|
| **Full-Text** | Text search (MATCH, AGAINST) |
| **Spatial** | Geographic data (PostGIS) |
| **Bitmap** | Low cardinality columns |
| **Partial/Filtered** | Subset of rows |
| **Expression/Functional** | Index on computed values |

```sql
-- Full-text index
CREATE FULLTEXT INDEX idx_content ON articles(title, body);
SELECT * FROM articles WHERE MATCH(title, body) AGAINST('database');

-- Partial index (PostgreSQL)
CREATE INDEX idx_active_users ON users(email) WHERE status = 'active';

-- Expression index
CREATE INDEX idx_lower_email ON users(LOWER(email));
```

---

## Index Selection Guidelines

### When to Create Indexes

✅ **Create index when**:
- Column frequently used in WHERE clauses
- Column used in JOIN conditions
- Column used in ORDER BY / GROUP BY
- High selectivity (many unique values)
- Table is large and queries are slow

❌ **Avoid index when**:
- Small tables (full scan is fast)
- Columns with low cardinality (e.g., boolean, status)
- Frequently updated columns
- Columns rarely used in queries
- Write-heavy tables with few reads

### Selectivity

**High selectivity** = Good for indexing
```
email: high selectivity (unique)
user_id: high selectivity (unique)
created_at: medium selectivity

gender: low selectivity (M/F/Other)
is_active: low selectivity (true/false)
country: depends on data distribution
```

---

## Query Optimization with Indexes

### EXPLAIN Plan
```sql
EXPLAIN SELECT * FROM users WHERE email = 'john@example.com';

-- Output tells you:
-- type: index (using index) vs ALL (full scan)
-- possible_keys: indexes that could be used
-- key: index actually used
-- rows: estimated rows scanned
```

### Common Patterns

**Covering Index for COUNT**:
```sql
-- Slow (scans table)
SELECT COUNT(*) FROM orders WHERE status = 'pending';

-- Fast (scans index only)
CREATE INDEX idx_status ON orders(status);
-- Now COUNT uses index
```

**Index for ORDER BY**:
```sql
-- Slow (filesort)
SELECT * FROM products ORDER BY price DESC LIMIT 10;

-- Fast (index scan)
CREATE INDEX idx_price ON products(price DESC);
```

**Composite Index Order**:
```sql
-- Query pattern
SELECT * FROM logs 
WHERE user_id = ? 
AND created_at > '2024-01-01'
ORDER BY created_at DESC;

-- Best index
CREATE INDEX idx_user_date ON logs(user_id, created_at DESC);
```

---

## Index Trade-offs

### Storage Overhead
```
Table: 10 GB
Index 1 (email): 500 MB
Index 2 (name): 800 MB
Index 3 (composite): 1.2 GB
Total: 12.5 GB (25% overhead)
```

### Write Performance Impact
```
INSERT without indexes:
  → Write 1 row to table

INSERT with 3 indexes:
  → Write 1 row to table
  → Update index 1 (B-tree rebalance)
  → Update index 2 (B-tree rebalance)
  → Update index 3 (B-tree rebalance)
  → 4x more work
```

### Balance
| Operation | No Index | With Index |
|-----------|----------|------------|
| SELECT | Slow | Fast |
| INSERT | Fast | Slower |
| UPDATE | Fast | Slower |
| DELETE | Fast | Slower |
| Storage | Less | More |

---

## Best Practices

### Do's ✅
1. Index columns in WHERE, JOIN, ORDER BY
2. Use composite indexes for multi-column queries
3. Put high-selectivity columns first in composite index
4. Monitor slow queries and add indexes as needed
5. Use EXPLAIN to verify index usage

### Don'ts ❌
1. Don't index every column
2. Don't create redundant indexes
3. Don't forget to remove unused indexes
4. Don't index low-cardinality columns alone
5. Don't use functions on indexed columns in WHERE

```sql
-- BAD: Function prevents index use
SELECT * FROM users WHERE YEAR(created_at) = 2024;

-- GOOD: Range query uses index
SELECT * FROM users 
WHERE created_at >= '2024-01-01' AND created_at < '2025-01-01';
```

---

## Interview Questions

**Q: What is a database index and how does it work?**
> An index is a data structure (usually B-tree) that speeds up data retrieval. It maintains sorted pointers to rows, enabling O(log n) lookups instead of O(n) full table scans. Trade-off: faster reads, slower writes, more storage.

**Q: When would you NOT use an index?**
> Small tables, low-cardinality columns, write-heavy workloads, columns rarely queried, frequently updated columns. Index overhead may not be worth it.

**Q: Explain composite index and the leftmost prefix rule.**
> Composite index is on multiple columns. Leftmost prefix rule: index on (A,B,C) can be used for queries on A, or A+B, or A+B+C, but NOT B alone or C alone. Column order matters.

**Q: What's the difference between clustered and non-clustered index?**
> Clustered: table data physically sorted by index, only one per table, eliminates extra lookup. Non-clustered: separate structure with pointers to rows, multiple allowed, requires extra lookup.

---

## Quick Reference

| Index Type | Best For | Limitations |
|------------|----------|-------------|
| B-Tree | General purpose, ranges | Slower than hash for exact |
| Hash | Exact lookups | No ranges, no sorting |
| Composite | Multi-column queries | Leftmost prefix only |
| Covering | Frequent queries | More storage |
| Full-Text | Text search | Specialized use |
