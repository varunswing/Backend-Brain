Design the **Device Configuration Sync System** from old to new device.

---

## ✅ 1. Functional Requirements

1. User should log in on new device using credentials or existing account.
2. System should fetch user's device configuration from old device.
3. User should optionally select preferences (e.g., default apps, layout).
4. New device should be configured with the same settings.
5. Device should be ready to use within 1–5 minutes.

---

## ✅ 2. Non-Functional Requirements

| NFR                    | Goal                                                       |
| ---------------------- | ---------------------------------------------------------- |
| **Security**           | Encrypted config sync, authenticated device pairing        |
| **Scalability**        | Should handle millions of users/devices concurrently       |
| **Performance**        | Sync should complete within 1–5 minutes                    |
| **Storage Efficiency** | Minimize what we store by only keeping deltas/diff configs |

---

## ✅ 3. Core Entities

```plaintext
User
└── owns → Devices (old, new)
     └── has → Configurations
             └── includes → Settings, Preferences, App Mappings, etc.
```

---

## ✅ 4. DB Schema

### `Users`

```sql
CREATE TABLE Users (
  id UUID PRIMARY KEY,
  email TEXT UNIQUE,
  password_hash TEXT
);
```

### `Devices`

```sql
CREATE TABLE Devices (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES Users(id),
  type TEXT CHECK (type IN ('PHONE', 'ALEXA', 'TV', 'SMART_HOME')),
  name TEXT,
  last_seen TIMESTAMP,
  config_version INT DEFAULT 0
);
```

### `Configurations`

```sql
CREATE TABLE Configurations (
  id UUID PRIMARY KEY,
  device_id UUID REFERENCES Devices(id),
  config_type TEXT, -- e.g., app_layout, preferences
  config_data JSONB,
  version INT,
  created_at TIMESTAMP
);
```

---

## ✅ 5. UML Class Diagram (Simplified)

```
User
 └── Device (old, new)
       └── Configuration
```

```java
class User {
    UUID id;
    String email;
    List<Device> devices;
}

class Device {
    UUID id;
    String name;
    DeviceType type;
    List<Configuration> configurations;
}

class Configuration {
    UUID id;
    String configType;
    Map<String, Object> configData;
    int version;
}
```

---

## ✅ 6. API Design

### 🔐 Login

```http
POST /api/auth/login
{
  "email": "user@example.com",
  "password": "secure123"
}
```

### 📱 Sync Config from Old Device

```http
POST /api/devices/sync
Headers: Authorization: Bearer <JWT>
{
  "oldDeviceId": "uuid-old",
  "newDeviceId": "uuid-new"
}
```

### 📥 Fetch Config for New Device

```http
GET /api/devices/{deviceId}/config
```

---

## ✅ 7. Service Flow

1. **LoginService** validates user
2. **SyncService** fetches latest configuration from old device
3. **ConfigTransferService** maps configurations to new device
4. **DeviceService** writes final config into new device
5. **NotificationService** notifies when device is ready

---

## ✅ 8. Storage Optimization Strategies

| Strategy                               | Benefit                                 |
| -------------------------------------- | --------------------------------------- |
| Store configs as **JSONB**             | Easy partial update, flexible schema    |
| Use **versioned configs**              | Only save diffs / last N versions       |
| Compress config payloads               | Use gzip/zstd to reduce storage + speed |
| Use **cloud bucket** for large configs | Don’t store binaries in DB              |

---

## ✅ 9. Design Patterns Involved

| Pattern      | Use Case                                     |
| ------------ | -------------------------------------------- |
| **Strategy** | Config mapping per device type               |
| **Builder**  | Construct config object from multiple pieces |
| **Factory**  | Instantiate correct config reader/writer     |
| **Observer** | Notify user when sync is completed           |
| **Command**  | Config operations batch handling             |

---

## ✅ 10. Concurrency & Consistency

* Config should be **versioned**, with optimistic locking
* New device config sync should be **idempotent**
* Use **timestamp + version check** before overwrite
* Race conditions prevented via distributed locking (e.g., Redis `SETNX`)

---

## ✅ 11. Fault Tolerance

* If sync partially fails, retry per config block
* If new device fails mid-process, resume from checkpoint
* Use **idempotency token** for sync requests

---

## ✅ 12. Security

| Area         | Technique                           |
| ------------ | ----------------------------------- |
| Auth         | JWT with refresh tokens             |
| Data         | AES encryption for config blobs     |
| Transfer     | HTTPS only, optional config signing |
| Device trust | Device fingerprint verification     |

---

## ✅ 13. Observability & Alerts

| Metric                    | Tool/Strategy             |
| ------------------------- | ------------------------- |
| Config sync success rate  | Prometheus + Grafana      |
| Avg. sync time per device | Logged per user/device ID |
| Failed config deltas      | Sentry / logs             |

---

## ✅ 14. Cross Questions Interviewer Might Ask

| Area              | Sample Questions                                                  |
| ----------------- | ----------------------------------------------------------------- |
| 🧠 **Modeling**   | Why store config as JSONB instead of structured columns?          |
| 🔁 **Sync Logic** | How do you handle syncing between different device types?         |
| 🔐 **Security**   | What prevents unauthorized devices from impersonating?            |
| 🔀 **Versioning** | How do you prevent overwriting an old config onto a newer device? |
| ⚡ **Performance** | How do you ensure sync completes in <5 mins at scale?             |
| ☁️ **Storage**    | What do you store in DB vs. cloud blob store?                     |
| 🚨 **Failures**   | What if new device crashes mid-sync? How to resume?               |
| 🧪 **Testing**    | How would you test the end-to-end sync flow?                      |

---

Reducing storage in a **Device Configuration Sync System** is key to keeping the system **cost-effective**, **fast**, and **scalable**.

Here are **practical and advanced strategies** to **minimize storage usage** while ensuring the system remains reliable:

---

## ✅ 1. **Delta Storage (Store only differences)**

Instead of storing full configuration snapshots every time:

* Compare new config with last saved version
* Store only **changed fields** (diff/patch)

**Example:**

```json
// Instead of storing this:
{
  "wallpaper": "beach.jpg",
  "theme": "dark",
  "volume": 70
}

// Just store delta:
{
  "theme": "dark"
}
```

🧠 Apply this with **versioning** to reconstruct full config when needed.

---

## ✅ 2. **Use JSONB or Map-like fields in DB (PostgreSQL, MongoDB)**

* Store config as a flat or nested JSON map
* Avoid schema bloat (e.g., 100 columns for 100 preferences)
* Easy to compress, update, and fetch only what’s needed

---

## ✅ 3. **Compression Before Storage (gzip/zstd/snappy)**

* Compress large config payloads (app layout, screen settings, etc.)
* Apply to:

  * JSON blobs in DB
  * Files stored in S3/GCS

🧪 Typical reduction: 60–90%

---

## ✅ 4. **Client-side Filtering Before Upload**

Let the old device:

* Filter out **default or redundant config** before sending
* Only send what's different from defaults

Example: Don't sync default ringtone unless user changed it.

---

## ✅ 5. **Time-based Expiry or Archiving**

* Keep only **N latest versions** per device (`N = 3`, e.g.)
* Auto-expire unused configs (e.g., no sync activity in 180 days)
* Move old versions to **cold storage (e.g., S3 Glacier)**

---

## ✅ 6. **Deduplicate Common Configs (Hash-Based Storage)**

If many users/devices share the same config:

* Compute **hash of config JSON**
* Reuse stored config blob via `hash_id`
* Reference via pointer

```json
{
  "configHash": "abc123",
  "deviceId": "xyz"
}
```

---

## ✅ 7. **Modular Configuration Storage**

Split config into modules:

* App preferences
* System layout
* Network settings

Then:

* Store/transfer only the modules that changed
* Helps reduce size and isolate updates

---

## ✅ 8. **Use Object Storage for Large Blobs**

* Store complex UI or binary configs (e.g., icon grid, themes) in S3/GCS
* Store only pointer/reference in DB

```json
{
  "configUrl": "https://s3/device-configs/abc.json"
}
```

---

## ✅ 9. **Avoid Duplicating Across Devices**

* Let users choose: “Copy settings from old device” instead of duplicating
* Link config instead of cloning

---

## ✅ 10. **Use Protobuf or Avro for Structured Storage**

If you're working in a binary-safe or IoT environment:

* Use efficient binary serialization (Protobuf, Avro)
* Much smaller than raw JSON/XML

---

## 🔥 Summary of Strategies

| Strategy                  | Savings Potential |
| ------------------------- | ----------------- |
| Delta storage             | ⭐⭐⭐⭐              |
| JSONB / Map columns       | ⭐⭐⭐               |
| Compression               | ⭐⭐⭐⭐              |
| Deduplication by hash     | ⭐⭐⭐⭐              |
| Cold storage / expiry     | ⭐⭐                |
| Modular config separation | ⭐⭐⭐               |
| Binary formats (protobuf) | ⭐⭐⭐⭐              |

---

## 🤖 Cross Questions Interviewer May Ask

| Area              | Question                                           |
| ----------------- | -------------------------------------------------- |
| 🔍 Delta logic    | How would you detect and apply deltas?             |
| ⚠️ Edge case      | What if config structure changes between versions? |
| ☁️ Object storage | Why not store everything in DB?                    |
| ⚖️ Trade-offs     | When would you store full vs. delta?               |
| 🧹 Clean-up       | How do you expire configs safely?                  |
| 📊 Efficiency     | How would you measure storage savings?             |

---
