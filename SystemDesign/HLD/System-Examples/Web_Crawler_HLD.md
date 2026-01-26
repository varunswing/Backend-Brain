# Web Crawler - High Level Design

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Capacity Estimation](#capacity-estimation)
3. [High-Level Design](#high-level-design)
4. [Request Flows](#request-flows)
5. [Detailed Component Design](#detailed-component-design)
6. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
7. [Failure Scenarios](#failure-scenarios)
8. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **URL Discovery**
   - Crawl web pages starting from seed URLs
   - Extract and follow hyperlinks
   - Discover new content continuously

2. **Content Fetching**
   - Download HTML, images, documents
   - Handle different content types
   - Support JavaScript rendering (for SPAs)

3. **Politeness**
   - Respect robots.txt
   - Rate limit per domain
   - Handle crawl delays

4. **URL Management**
   - Avoid duplicate URLs
   - Prioritize important pages
   - Track crawl history

5. **Data Storage**
   - Store fetched content
   - Update stale content
   - Maintain URL frontier

### Non-Functional Requirements

1. **Scale**: Crawl billions of pages
2. **Throughput**: 1000+ pages/second
3. **Freshness**: Re-crawl important pages frequently
4. **Robustness**: Handle failures gracefully
5. **Politeness**: Don't overwhelm websites
6. **Extensibility**: Support different content types

---

## Capacity Estimation

### Crawl Targets

```
Web size estimate:
- Indexed web: ~50 billion pages
- New pages daily: ~500 million

Crawl goals:
- Initial crawl: 1 billion pages
- Maintain freshness: Re-crawl 500M pages/month
- Discovery: 10M new pages/day
```

### Throughput Calculation

```
Target: 1 billion pages in 30 days

Pages per day: 1B / 30 = 33 million
Pages per second: 33M / 86400 = 380 pages/sec

With buffer: Target 1000 pages/sec
```

### Storage Estimates

```
Per page:
- URL: 100 bytes (average)
- HTML content: 100 KB (average)
- Metadata: 500 bytes

For 1 billion pages:
- URLs: 1B * 100 B = 100 GB
- Content: 1B * 100 KB = 100 TB
- Metadata: 1B * 500 B = 500 GB

Total: ~100 TB (compressed: ~30 TB)
```

### Network Bandwidth

```
1000 pages/sec * 100 KB = 100 MB/sec
Daily: 100 MB/sec * 86400 = 8.6 TB/day
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              WEB CRAWLER ARCHITECTURE                                │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                           SEED URLs                                          │   │
│  │                    (Starting points for crawl)                               │   │
│  └───────────────────────────────────┬─────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                        URL FRONTIER                                          │   │
│  │                                                                              │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │   │
│  │  │  Priority Queue │  │   URL Seen?     │  │  Domain Queue   │             │   │
│  │  │   (by score)    │  │   (Bloom/Set)   │  │ (rate limiting) │             │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘             │   │
│  │                                                                              │   │
│  └───────────────────────────────────┬─────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                      CRAWLER WORKERS (Distributed)                           │   │
│  │                                                                              │   │
│  │  ┌──────────────────────────────────────────────────────────────────────┐   │   │
│  │  │  Worker 1        Worker 2        Worker 3        Worker N            │   │   │
│  │  │     │               │               │               │                │   │   │
│  │  │     ▼               ▼               ▼               ▼                │   │   │
│  │  │  1. Check robots.txt                                                 │   │   │
│  │  │  2. Fetch page (HTTP GET)                                           │   │   │
│  │  │  3. Parse HTML                                                       │   │   │
│  │  │  4. Extract links                                                    │   │   │
│  │  │  5. Store content                                                    │   │   │
│  │  │  6. Add new URLs to frontier                                        │   │   │
│  │  └──────────────────────────────────────────────────────────────────────┘   │   │
│  │                                                                              │   │
│  └───────────────────────────────────┬─────────────────────────────────────────┘   │
│                                      │                                             │
│            ┌─────────────────────────┴─────────────────────────────┐              │
│            │                                                       │              │
│            ▼                                                       ▼              │
│  ┌─────────────────────────┐                       ┌───────────────────────────┐  │
│  │     CONTENT STORE       │                       │      URL STORE            │  │
│  │                         │                       │                           │  │
│  │  - Raw HTML             │                       │  - Visited URLs           │  │
│  │  - Parsed content       │                       │  - Last crawl time        │  │
│  │  - Metadata             │                       │  - Crawl frequency        │  │
│  │  (Distributed FS/S3)    │                       │  (Distributed DB)         │  │
│  └─────────────────────────┘                       └───────────────────────────┘  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         SUPPORTING SERVICES                                  │   │
│  │                                                                              │   │
│  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐                │   │
│  │  │  DNS Resolver  │  │  Robots.txt    │  │    Content     │                │   │
│  │  │    Cache       │  │    Cache       │  │   Deduplicator │                │   │
│  │  └────────────────┘  └────────────────┘  └────────────────┘                │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **URL Frontier**: Queue of URLs to crawl
2. **Crawler Workers**: Fetch and process pages
3. **DNS Resolver**: Resolve domain names (cached)
4. **Content Store**: Store crawled content
5. **URL Store**: Track crawl history
6. **Robots.txt Cache**: Store politeness rules

---

## Request Flows

### Basic Crawl Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│Frontier│  │ Worker  │  │  DNS    │  │  Robots   │  │  Web    │  │ Parser  │
│        │  │         │  │ Resolver│  │   Cache   │  │ Server  │  │         │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ Get next URL            │             │             │            │
    │───────────>│            │             │             │            │
    │            │            │             │             │            │
    │            │ Resolve domain           │             │            │
    │            │───────────>│             │             │            │
    │            │<───────────│ IP address  │             │            │
    │            │            │             │             │            │
    │            │ Check robots.txt         │             │            │
    │            │──────────────────────────>│             │            │
    │            │            │             │             │            │
    │            │<──────────────────────────│ Rules      │            │
    │            │            │             │             │            │
    │            │ If allowed:              │             │            │
    │            │ GET /page                │             │            │
    │            │─────────────────────────────────────>│            │
    │            │            │             │             │            │
    │            │<─────────────────────────────────────│ HTML       │
    │            │            │             │             │            │
    │            │ Parse HTML │             │             │            │
    │            │──────────────────────────────────────────────────>│
    │            │            │             │             │            │
    │            │<──────────────────────────────────────────────────│
    │            │ Links + Content          │             │            │
    │            │            │             │             │            │
    │ Add new URLs            │             │             │            │
    │<───────────│            │             │             │            │
    │            │            │             │             │            │
    │ Store content           │             │             │            │
    │            │───────────────────────────────────────────────────>│
```

### URL Frontier Processing

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                        URL FRONTIER WORKFLOW                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  New URL discovered: https://example.com/page1                                      │
│                                                                                     │
│  Step 1: URL Normalization                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Input:  https://EXAMPLE.com/page1?utm_source=google#section                │   │
│  │  Output: https://example.com/page1                                          │   │
│  │                                                                              │   │
│  │  - Lowercase domain                                                         │   │
│  │  - Remove tracking params                                                   │   │
│  │  - Remove fragments                                                         │   │
│  │  - Resolve relative URLs                                                    │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Step 2: Deduplication Check                                                        │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Check Bloom filter: Is URL already seen?                                   │   │
│  │  - If yes: Skip                                                             │   │
│  │  - If no: Continue                                                          │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Step 3: Priority Calculation                                                       │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Score = f(PageRank, Domain authority, Freshness, Depth)                   │   │
│  │                                                                              │   │
│  │  High priority: Homepage, popular sites, news                              │   │
│  │  Low priority: Deep pages, unknown domains                                 │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Step 4: Domain Queue Assignment                                                    │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Route to domain-specific queue for politeness                             │   │
│  │                                                                              │   │
│  │  example.com → Queue A (crawl every 5 sec)                                 │   │
│  │  news.site  → Queue B (crawl every 10 sec)                                 │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Component Design

### 1. URL Frontier

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            URL FRONTIER ARCHITECTURE                                 │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Two-level queue system:                                                            │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  FRONT QUEUES (Priority-based)                                              │   │
│  │  ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │  │                                                                      │   │   │
│  │  │  P1 (Highest): [url1, url2, url3...]  ← Hot/important pages        │   │   │
│  │  │  P2 (High):    [url4, url5, url6...]  ← Fresh content              │   │   │
│  │  │  P3 (Medium):  [url7, url8, url9...]  ← Regular pages              │   │   │
│  │  │  P4 (Low):     [url10, url11...]      ← Deep/old pages             │   │   │
│  │  │                                                                      │   │   │
│  │  └─────────────────────────────────────────────────────────────────────┘   │   │
│  │                           │                                                 │   │
│  │                           │ Select by priority (weighted random)           │   │
│  │                           ▼                                                 │   │
│  │  BACK QUEUES (Domain-based)                                                 │   │
│  │  ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │  │                                                                      │   │   │
│  │  │  example.com:  [url1, url4, url7...]   Last fetch: 10:00:00        │   │   │
│  │  │  google.com:   [url2, url5...]         Last fetch: 10:00:02        │   │   │
│  │  │  twitter.com:  [url3, url6...]         Last fetch: 10:00:01        │   │   │
│  │  │  ...                                                                 │   │   │
│  │  │                                                                      │   │   │
│  │  │  Each queue has minimum delay between fetches (politeness)          │   │   │
│  │  │                                                                      │   │   │
│  │  └─────────────────────────────────────────────────────────────────────┘   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class URLFrontier:                                                          │   │
│  │      def __init__(self):                                                     │   │
│  │          self.priority_queues = [PriorityQueue() for _ in range(4)]        │   │
│  │          self.domain_queues = defaultdict(deque)                            │   │
│  │          self.domain_last_fetch = {}                                        │   │
│  │          self.seen_urls = BloomFilter(expected_items=10_000_000_000)        │   │
│  │                                                                              │   │
│  │      def add_url(self, url, priority):                                       │   │
│  │          normalized = self.normalize(url)                                   │   │
│  │          if normalized in self.seen_urls:                                   │   │
│  │              return                                                          │   │
│  │                                                                              │   │
│  │          self.seen_urls.add(normalized)                                     │   │
│  │          domain = extract_domain(normalized)                                │   │
│  │          self.domain_queues[domain].append(normalized)                     │   │
│  │          self.priority_queues[priority].put(domain)                        │   │
│  │                                                                              │   │
│  │      def get_next_url(self):                                                 │   │
│  │          # Select domain from priority queues                               │   │
│  │          domain = self.select_ready_domain()                                │   │
│  │          if not domain:                                                      │   │
│  │              return None                                                     │   │
│  │                                                                              │   │
│  │          # Get URL from domain queue                                        │   │
│  │          url = self.domain_queues[domain].popleft()                        │   │
│  │          self.domain_last_fetch[domain] = time.time()                      │   │
│  │          return url                                                          │   │
│  │                                                                              │   │
│  │      def select_ready_domain(self):                                          │   │
│  │          now = time.time()                                                   │   │
│  │          for pq in self.priority_queues:                                    │   │
│  │              for domain in pq:                                              │   │
│  │                  last_fetch = self.domain_last_fetch.get(domain, 0)        │   │
│  │                  delay = self.get_crawl_delay(domain)                      │   │
│  │                  if now - last_fetch >= delay:                             │   │
│  │                      return domain                                          │   │
│  │          return None                                                         │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Crawler Worker

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              CRAWLER WORKER                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  class CrawlerWorker:                                                               │
│      def __init__(self, frontier, content_store, url_store):                       │
│          self.frontier = frontier                                                   │
│          self.content_store = content_store                                        │
│          self.url_store = url_store                                                │
│          self.dns_cache = DNSCache()                                               │
│          self.robots_cache = RobotsCache()                                         │
│          self.session = aiohttp.ClientSession()                                    │
│                                                                                     │
│      async def run(self):                                                           │
│          while True:                                                                │
│              url = await self.frontier.get_next_url()                              │
│              if url:                                                                │
│                  await self.crawl(url)                                             │
│              else:                                                                  │
│                  await asyncio.sleep(0.1)                                          │
│                                                                                     │
│      async def crawl(self, url):                                                    │
│          try:                                                                       │
│              # Check robots.txt                                                     │
│              if not await self.is_allowed(url):                                    │
│                  return                                                             │
│                                                                                     │
│              # Fetch page                                                           │
│              response = await self.fetch(url)                                      │
│              if not response:                                                       │
│                  return                                                             │
│                                                                                     │
│              # Process content                                                      │
│              content_type = response.headers.get('Content-Type', '')              │
│              if 'text/html' in content_type:                                       │
│                  await self.process_html(url, response.text)                      │
│              elif self.is_downloadable(content_type):                              │
│                  await self.store_binary(url, response.content)                   │
│                                                                                     │
│              # Update URL store                                                     │
│              await self.url_store.update(url, {                                    │
│                  'last_crawl': time.time(),                                        │
│                  'status': response.status,                                        │
│                  'content_hash': hash(response.content)                           │
│              })                                                                     │
│                                                                                     │
│          except Exception as e:                                                     │
│              await self.handle_error(url, e)                                       │
│                                                                                     │
│      async def process_html(self, url, html):                                       │
│          # Parse HTML                                                               │
│          soup = BeautifulSoup(html, 'lxml')                                        │
│                                                                                     │
│          # Extract links                                                            │
│          links = self.extract_links(soup, url)                                     │
│          for link, priority in links:                                              │
│              await self.frontier.add_url(link, priority)                          │
│                                                                                     │
│          # Store content                                                            │
│          await self.content_store.store(url, {                                     │
│              'html': html,                                                         │
│              'title': soup.title.string if soup.title else '',                    │
│              'text': soup.get_text(),                                              │
│              'links': [l[0] for l in links],                                      │
│              'crawl_time': time.time()                                             │
│          })                                                                         │
│                                                                                     │
│      def extract_links(self, soup, base_url):                                       │
│          links = []                                                                 │
│          for anchor in soup.find_all('a', href=True):                              │
│              href = anchor['href']                                                 │
│              absolute_url = urljoin(base_url, href)                               │
│              if self.is_valid_url(absolute_url):                                  │
│                  priority = self.calculate_priority(absolute_url, anchor)         │
│                  links.append((absolute_url, priority))                           │
│          return links                                                               │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Politeness and robots.txt

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            POLITENESS HANDLING                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  robots.txt example:                                                                │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  User-agent: *                                                              │   │
│  │  Disallow: /admin/                                                          │   │
│  │  Disallow: /private/                                                        │   │
│  │  Crawl-delay: 10                                                            │   │
│  │                                                                              │   │
│  │  User-agent: Googlebot                                                      │   │
│  │  Allow: /admin/public/                                                      │   │
│  │  Crawl-delay: 5                                                             │   │
│  │                                                                              │   │
│  │  Sitemap: https://example.com/sitemap.xml                                  │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  RobotsParser implementation:                                                       │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class RobotsCache:                                                          │   │
│  │      def __init__(self, cache_ttl=86400):                                   │   │
│  │          self.cache = {}                                                     │   │
│  │          self.cache_ttl = cache_ttl                                         │   │
│  │                                                                              │   │
│  │      async def is_allowed(self, url, user_agent="MyCrawler"):               │   │
│  │          domain = extract_domain(url)                                       │   │
│  │          robots = await self.get_robots(domain)                             │   │
│  │          return robots.can_fetch(user_agent, url)                          │   │
│  │                                                                              │   │
│  │      async def get_crawl_delay(self, domain, user_agent="MyCrawler"):       │   │
│  │          robots = await self.get_robots(domain)                             │   │
│  │          delay = robots.crawl_delay(user_agent)                            │   │
│  │          return delay or 1  # Default 1 second                             │   │
│  │                                                                              │   │
│  │      async def get_robots(self, domain):                                     │   │
│  │          if domain in self.cache:                                           │   │
│  │              robots, timestamp = self.cache[domain]                         │   │
│  │              if time.time() - timestamp < self.cache_ttl:                  │   │
│  │                  return robots                                               │   │
│  │                                                                              │   │
│  │          robots_url = f"https://{domain}/robots.txt"                        │   │
│  │          try:                                                                │   │
│  │              response = await fetch(robots_url)                             │   │
│  │              robots = RobotFileParser()                                     │   │
│  │              robots.parse(response.text.split('\n'))                       │   │
│  │          except:                                                             │   │
│  │              robots = RobotFileParser()  # Allow all on failure            │   │
│  │                                                                              │   │
│  │          self.cache[domain] = (robots, time.time())                        │   │
│  │          return robots                                                       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Additional politeness measures:                                                    │
│  - Use descriptive User-Agent string with contact info                             │
│  - Limit concurrent connections per domain (1-2)                                   │
│  - Respect 429 (Too Many Requests) responses                                       │
│  - Back off exponentially on errors                                                │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 4. Content Deduplication

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          CONTENT DEDUPLICATION                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Challenge: Same content at different URLs                                          │
│  - Mirror sites                                                                     │
│  - URL parameters                                                                   │
│  - Session IDs in URLs                                                              │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Approach 1: Exact hash (MD5/SHA256)                                        │   │
│  │  - Simple: hash(content)                                                    │   │
│  │  - Problem: Tiny changes = different hash                                   │   │
│  │                                                                              │   │
│  │  Approach 2: SimHash (fingerprinting)                                       │   │
│  │  - Locality-sensitive hashing                                               │   │
│  │  - Similar content = similar hash                                           │   │
│  │  - Compare by Hamming distance                                              │   │
│  │                                                                              │   │
│  │  Approach 3: MinHash (Jaccard similarity)                                   │   │
│  │  - Good for shingle-based comparison                                        │   │
│  │  - Works well for near-duplicates                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  SimHash implementation:                                                            │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class SimHash:                                                              │   │
│  │      def __init__(self, hash_bits=64):                                      │   │
│  │          self.hash_bits = hash_bits                                         │   │
│  │                                                                              │   │
│  │      def compute(self, text):                                                │   │
│  │          # Tokenize into shingles                                           │   │
│  │          tokens = self.get_shingles(text, k=3)                              │   │
│  │                                                                              │   │
│  │          # Initialize vector                                                 │   │
│  │          v = [0] * self.hash_bits                                           │   │
│  │                                                                              │   │
│  │          # For each token                                                   │   │
│  │          for token in tokens:                                               │   │
│  │              h = self.hash_token(token)                                     │   │
│  │              for i in range(self.hash_bits):                               │   │
│  │                  if h & (1 << i):                                          │   │
│  │                      v[i] += 1                                             │   │
│  │                  else:                                                      │   │
│  │                      v[i] -= 1                                             │   │
│  │                                                                              │   │
│  │          # Convert to hash                                                   │   │
│  │          fingerprint = 0                                                     │   │
│  │          for i in range(self.hash_bits):                                    │   │
│  │              if v[i] > 0:                                                   │   │
│  │                  fingerprint |= (1 << i)                                   │   │
│  │                                                                              │   │
│  │          return fingerprint                                                  │   │
│  │                                                                              │   │
│  │      def similarity(self, hash1, hash2):                                     │   │
│  │          # Hamming distance                                                  │   │
│  │          diff = bin(hash1 ^ hash2).count('1')                              │   │
│  │          return 1 - (diff / self.hash_bits)                                 │   │
│  │                                                                              │   │
│  │  # Usage                                                                     │   │
│  │  simhash = SimHash()                                                         │   │
│  │  h1 = simhash.compute(page1_text)                                           │   │
│  │  h2 = simhash.compute(page2_text)                                           │   │
│  │  if simhash.similarity(h1, h2) > 0.9:                                       │   │
│  │      print("Near duplicate!")                                                │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### URL Frontier Storage

| Storage | Pros | Cons | Use Case |
|---------|------|------|----------|
| Redis | Fast, in-memory | Memory limited | Small/medium crawls |
| Kafka | Persistent, ordered | Complex | Large-scale, distributed |
| Cassandra | Scalable, durable | Slower | Very large crawls |

### Deduplication

| Method | Accuracy | Speed | Memory |
|--------|----------|-------|--------|
| Exact hash | Perfect | Fast | High |
| SimHash | ~95% | Fast | Low |
| MinHash | ~90% | Medium | Medium |

**Choice:** Use Bloom filter for URL dedup + SimHash for content

### Crawl Strategy

| Strategy | Coverage | Freshness | Politeness |
|----------|----------|-----------|------------|
| BFS | Wide | Low | Good |
| DFS | Deep | Medium | Risk |
| Priority | Balanced | High | Good |

**Choice:** Priority-based with BFS bias

---

## Failure Scenarios

### Network Failures

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          ERROR HANDLING                                              │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  async def fetch_with_retry(self, url, max_retries=3):                              │
│      retry_delays = [1, 5, 30]  # Exponential backoff                               │
│                                                                                     │
│      for attempt in range(max_retries):                                             │
│          try:                                                                       │
│              response = await self.session.get(                                     │
│                  url,                                                               │
│                  timeout=30,                                                        │
│                  allow_redirects=True,                                             │
│                  max_redirects=5                                                   │
│              )                                                                      │
│                                                                                     │
│              if response.status == 200:                                            │
│                  return response                                                    │
│              elif response.status == 429:  # Rate limited                          │
│                  retry_after = int(response.headers.get('Retry-After', 60))       │
│                  await asyncio.sleep(retry_after)                                  │
│              elif response.status in [500, 502, 503]:  # Server error             │
│                  await asyncio.sleep(retry_delays[attempt])                       │
│              elif response.status == 404:                                          │
│                  return None  # Page not found, don't retry                       │
│              else:                                                                  │
│                  return None                                                        │
│                                                                                     │
│          except (aiohttp.ClientError, asyncio.TimeoutError) as e:                  │
│              if attempt < max_retries - 1:                                         │
│                  await asyncio.sleep(retry_delays[attempt])                       │
│              else:                                                                  │
│                  self.log_error(url, e)                                            │
│                                                                                     │
│      return None                                                                    │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Spider Traps

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          SPIDER TRAP DETECTION                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Spider traps: URLs that generate infinite variations                               │
│  - Calendar: /calendar/2024/01/01 → /calendar/2024/01/02 → ...                    │
│  - Session IDs: /page?sid=abc123 → /page?sid=def456 → ...                        │
│  - Pagination: /page/1 → /page/2 → ... → /page/999999                            │
│                                                                                     │
│  Detection strategies:                                                              │
│                                                                                     │
│  1. URL depth limit                                                                 │
│     max_depth = 15  # Don't crawl beyond 15 levels                                │
│                                                                                     │
│  2. Per-domain page limit                                                           │
│     max_pages_per_domain = 10000                                                    │
│                                                                                     │
│  3. URL pattern detection                                                           │
│     if count_similar_urls(url) > threshold:                                        │
│         mark_as_trap(url_pattern)                                                   │
│                                                                                     │
│  4. Content similarity                                                              │
│     if simhash(new_content) == simhash(recent_content):                           │
│         skip_similar_urls()                                                         │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Interviewer Questions & Answers

### Q1: How do you handle billions of URLs efficiently?

**Answer:**

**URL Seen Check: Bloom Filter**

```python
class URLDeduplicator:
    def __init__(self, expected_urls=10_000_000_000):
        # Bloom filter: 1% false positive rate
        # Size: ~10 bits per item = 12.5 GB
        self.bloom = BloomFilter(
            expected_items=expected_urls,
            fp_rate=0.01
        )
        
        # Exact check for false positives
        self.exact_store = RocksDB()  # On-disk hash set
    
    def is_seen(self, url):
        normalized = self.normalize(url)
        
        # Quick check with Bloom filter
        if normalized not in self.bloom:
            return False
        
        # Bloom says yes - might be false positive
        # Check exact store
        return self.exact_store.contains(normalized)
    
    def mark_seen(self, url):
        normalized = self.normalize(url)
        self.bloom.add(normalized)
        self.exact_store.put(normalized)
```

**Why Bloom filter:**
- 10B URLs × 100 bytes = 1 TB (too much for RAM)
- Bloom: 10B URLs × 10 bits = 12.5 GB (fits in RAM)
- False positive → Extra disk check (acceptable)

---

### Q2: How do you prioritize which URLs to crawl?

**Answer:**

**Multi-factor priority scoring:**

```python
def calculate_priority(url, context):
    score = 0
    
    # 1. PageRank / Domain authority
    domain = extract_domain(url)
    score += domain_rank.get(domain, 0) * 0.3
    
    # 2. URL depth (closer to homepage = higher priority)
    depth = url.count('/') - 2  # Exclude protocol slashes
    score -= depth * 0.1
    
    # 3. Content freshness importance
    if is_news_site(domain):
        score += 0.2
    
    # 4. Link context
    if context.anchor_text:
        if 'important' in context.anchor_text.lower():
            score += 0.1
    
    # 5. Crawl history (re-crawl frequency)
    last_crawl = get_last_crawl(url)
    if last_crawl:
        age_hours = (time.time() - last_crawl) / 3600
        expected_change = get_change_frequency(url)
        if age_hours > expected_change:
            score += 0.15
    
    return score
```

**Priority buckets:**
- P1: Homepage, trending, breaking news
- P2: Category pages, popular content
- P3: Regular content pages
- P4: Deep pages, archives

---

### Q3: How do you handle JavaScript-rendered pages (SPAs)?

**Answer:**

**Two-tier approach:**

```
┌─────────────────────────────────────────────────────────────────┐
│           JAVASCRIPT RENDERING STRATEGY                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Tier 1: Static HTML fetch (fast, cheap)                       │
│  - 90% of pages don't need JS                                  │
│  - Simple HTTP GET                                             │
│                                                                 │
│  Tier 2: Headless browser (slow, expensive)                    │
│  - SPAs, React/Angular sites                                   │
│  - Use Puppeteer/Playwright                                    │
│                                                                 │
│  Detection:                                                      │
│  - If HTML has <script> with framework indicators              │
│  - If content is minimal (<1KB text)                           │
│  - Domain known to use SPA                                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class JSRenderer:
    def __init__(self, pool_size=10):
        self.browser_pool = BrowserPool(pool_size)
    
    async def render(self, url):
        browser = await self.browser_pool.acquire()
        try:
            page = await browser.new_page()
            await page.goto(url, wait_until='networkidle')
            
            # Wait for dynamic content
            await page.wait_for_selector('body', timeout=5000)
            
            html = await page.content()
            return html
        finally:
            await self.browser_pool.release(browser)

# Decision logic
async def fetch_page(url):
    # Try static first
    response = await http_fetch(url)
    
    if needs_js_rendering(response):
        return await js_renderer.render(url)
    
    return response
```

---

### Q4: How do you detect and handle duplicate content?

**Answer:**

**URL-level deduplication:**
```python
def normalize_url(url):
    parsed = urlparse(url)
    
    # Lowercase domain
    domain = parsed.netloc.lower()
    
    # Remove tracking parameters
    params = parse_qs(parsed.query)
    clean_params = {k: v for k, v in params.items() 
                   if k not in TRACKING_PARAMS}
    
    # Remove fragments
    # Remove trailing slashes
    path = parsed.path.rstrip('/')
    
    return f"{parsed.scheme}://{domain}{path}"
```

**Content-level deduplication:**
```python
class ContentDeduplicator:
    def __init__(self):
        self.simhash_index = SimHashIndex()
    
    def is_duplicate(self, content):
        # Compute SimHash
        fingerprint = simhash(content)
        
        # Find near-duplicates (Hamming distance < 3)
        similar = self.simhash_index.find_similar(fingerprint, threshold=3)
        
        if similar:
            return True, similar[0]  # Is duplicate, return canonical URL
        
        return False, None
    
    def add(self, url, content):
        fingerprint = simhash(content)
        self.simhash_index.add(url, fingerprint)
```

---

### Q5: How do you distribute the crawling workload?

**Answer:**

**Domain-based partitioning:**

```
┌─────────────────────────────────────────────────────────────────┐
│            DISTRIBUTED CRAWLER ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Coordinator                                                     │
│       │                                                         │
│       ├── Worker 1: domains A-F                                 │
│       ├── Worker 2: domains G-L                                 │
│       ├── Worker 3: domains M-R                                 │
│       └── Worker 4: domains S-Z                                 │
│                                                                 │
│  Partitioning: hash(domain) % num_workers                       │
│                                                                 │
│  Benefits:                                                       │
│  - Each worker handles its own politeness                       │
│  - No coordination needed for rate limiting                     │
│  - Better DNS/robots.txt caching                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class CrawlerCoordinator:
    def __init__(self, num_workers):
        self.num_workers = num_workers
        self.workers = []
        
    def assign_url(self, url):
        domain = extract_domain(url)
        worker_id = hash(domain) % self.num_workers
        return worker_id
    
    async def distribute_urls(self, urls):
        batches = defaultdict(list)
        for url in urls:
            worker_id = self.assign_url(url)
            batches[worker_id].append(url)
        
        # Send to workers via message queue
        for worker_id, batch in batches.items():
            await kafka.send(f"worker-{worker_id}", batch)
```

---

### Q6: How do you determine re-crawl frequency?

**Answer:**

**Adaptive re-crawl based on change patterns:**

```python
class RecrawlScheduler:
    def calculate_next_crawl(self, url, history):
        if not history:
            return default_interval(url)
        
        # Analyze change frequency
        changes = self.count_changes(history)
        total_crawls = len(history)
        change_rate = changes / total_crawls
        
        # Adjust interval based on change rate
        if change_rate > 0.8:  # Changes frequently
            interval = timedelta(hours=1)
        elif change_rate > 0.5:
            interval = timedelta(hours=6)
        elif change_rate > 0.2:
            interval = timedelta(days=1)
        else:  # Rarely changes
            interval = timedelta(days=7)
        
        # Adjust for importance
        domain_rank = get_domain_rank(url)
        if domain_rank > 0.9:  # Very important
            interval = interval / 2
        
        return interval
    
    def count_changes(self, history):
        changes = 0
        for i in range(1, len(history)):
            if history[i].content_hash != history[i-1].content_hash:
                changes += 1
        return changes
```

**Categories:**
- News sites: Every 15 minutes
- Blogs: Every day
- Static pages: Every week
- Archives: Every month

---

### Q7: How do you handle crawl budget?

**Answer:**

**Crawl budget = max pages to crawl per domain per day**

```python
class CrawlBudgetManager:
    def __init__(self):
        self.budgets = {}  # domain → remaining budget
        
    def allocate_budgets(self, total_budget, domains):
        # Allocate based on domain importance
        total_rank = sum(get_rank(d) for d in domains)
        
        for domain in domains:
            rank = get_rank(domain)
            allocation = int((rank / total_rank) * total_budget)
            self.budgets[domain] = max(allocation, 100)  # Minimum 100
    
    def can_crawl(self, url):
        domain = extract_domain(url)
        return self.budgets.get(domain, 0) > 0
    
    def consume(self, url):
        domain = extract_domain(url)
        if domain in self.budgets:
            self.budgets[domain] -= 1
```

**Budget allocation factors:**
- Domain authority/PageRank
- Content freshness requirements
- Historical crawl value
- robots.txt Crawl-delay

---

### Q8: How do you handle different content types?

**Answer:**

```python
class ContentHandler:
    def __init__(self):
        self.handlers = {
            'text/html': self.handle_html,
            'application/pdf': self.handle_pdf,
            'application/xml': self.handle_sitemap,
            'image/': self.handle_image,
        }
    
    async def process(self, url, response):
        content_type = response.headers.get('Content-Type', '')
        
        for mime_prefix, handler in self.handlers.items():
            if content_type.startswith(mime_prefix):
                return await handler(url, response)
        
        return None  # Unsupported content type
    
    async def handle_html(self, url, response):
        soup = BeautifulSoup(response.text, 'lxml')
        return {
            'type': 'html',
            'title': soup.title.string if soup.title else '',
            'text': soup.get_text(),
            'links': self.extract_links(soup, url),
            'meta': self.extract_meta(soup)
        }
    
    async def handle_sitemap(self, url, response):
        # Parse sitemap.xml
        urls = []
        root = ET.fromstring(response.text)
        for url_elem in root.findall('.//{*}url'):
            loc = url_elem.find('{*}loc')
            if loc is not None:
                urls.append(loc.text)
        return {'type': 'sitemap', 'urls': urls}
    
    async def handle_pdf(self, url, response):
        # Extract text from PDF
        text = pdf_to_text(response.content)
        return {'type': 'pdf', 'text': text}
```

---

### Q9: How do you monitor crawler health?

**Answer:**

**Key metrics:**
```python
class CrawlerMetrics:
    def record_fetch(self, url, response, duration):
        domain = extract_domain(url)
        
        # Throughput
        metrics.increment('crawler.pages_fetched', tags={'domain': domain})
        
        # Latency
        metrics.timing('crawler.fetch_latency', duration)
        
        # Status codes
        metrics.increment('crawler.status_codes', 
                         tags={'status': response.status})
        
        # Content size
        metrics.histogram('crawler.content_size', len(response.content))
        
        # Error rate
        if response.status >= 400:
            metrics.increment('crawler.errors', tags={'status': response.status})

# Alerting rules
alerts:
  - name: low_throughput
    condition: rate(crawler.pages_fetched) < 500
    severity: warning
    
  - name: high_error_rate
    condition: rate(crawler.errors) / rate(crawler.pages_fetched) > 0.1
    severity: critical
    
  - name: queue_backup
    condition: frontier.queue_size > 10_000_000
    severity: warning
```

---

### Q10: Design a focused crawler for a specific topic.

**Answer:**

**Topic-focused crawling:**

```python
class FocusedCrawler:
    def __init__(self, topic_model, relevance_threshold=0.7):
        self.topic_model = topic_model  # Trained classifier
        self.threshold = relevance_threshold
        
    def should_crawl(self, url, context):
        # Quick check: Domain relevance
        domain = extract_domain(url)
        if domain in self.high_relevance_domains:
            return True
        
        # Check anchor text
        if context.anchor_text:
            relevance = self.topic_model.predict(context.anchor_text)
            if relevance > self.threshold:
                return True
        
        # Check URL patterns
        if self.matches_topic_patterns(url):
            return True
        
        return False
    
    def prioritize_links(self, links, page_content):
        # Score links based on surrounding context
        scored = []
        for link in links:
            context = self.extract_context(link, page_content)
            relevance = self.topic_model.predict(context)
            scored.append((link, relevance))
        
        # Sort by relevance
        return sorted(scored, key=lambda x: x[1], reverse=True)
    
    def should_continue(self, page_content):
        # Check if page is still relevant
        relevance = self.topic_model.predict(page_content)
        return relevance > self.threshold * 0.8
```

**Benefits:**
- Fewer irrelevant pages crawled
- Better resource utilization
- Higher quality content for specific use case

---

### Bonus: Quick Reference

```
┌─────────────────────────────────────────────────────────────────┐
│                WEB CRAWLER - QUICK REFERENCE                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Components:                                                    │
│  ├── URL Frontier - Priority + domain queues                   │
│  ├── Crawler Workers - Distributed fetchers                    │
│  ├── DNS Cache - Reduce DNS lookups                            │
│  ├── Robots Cache - Politeness rules                           │
│  └── Content Store - Store crawled data                        │
│                                                                 │
│  Politeness:                                                    │
│  ├── Respect robots.txt                                        │
│  ├── Crawl-delay per domain                                    │
│  ├── Identify with User-Agent                                  │
│  └── Handle 429 (rate limit) responses                         │
│                                                                 │
│  Deduplication:                                                 │
│  ├── URL: Bloom filter (10 bits/URL)                          │
│  └── Content: SimHash (64-bit fingerprint)                    │
│                                                                 │
│  Scale strategies:                                              │
│  ├── Domain-based partitioning                                 │
│  ├── Async I/O (aiohttp)                                       │
│  └── Headless browsers for JS (Puppeteer pool)                │
│                                                                 │
│  Target: 1000+ pages/sec, 1B pages/month                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
