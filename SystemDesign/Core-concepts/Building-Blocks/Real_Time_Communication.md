# Real-Time Communication: WebSockets, SSE, Long Polling, and MQTT

## Overview
Real-time communication enables instant data transfer between clients and servers without explicit client requests. Essential for chat apps, live updates, gaming, collaborative tools, and IoT devices.

## Communication Patterns Comparison

| Pattern | Direction | Connection | Protocol | Use Case |
|---------|-----------|------------|----------|----------|
| **HTTP Polling** | Client → Server | Short-lived | HTTP | Simple updates |
| **Long Polling** | Client → Server | Extended | HTTP | Notifications |
| **SSE** | Server → Client | Persistent | HTTP | Live feeds |
| **WebSocket** | Bidirectional | Persistent | WS/WSS | Chat, gaming |
| **MQTT** | Pub/Sub | Persistent | MQTT | IoT, telemetry |

---

## Complete Comparison Matrix

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                        REAL-TIME COMMUNICATION PROTOCOLS COMPARISON                              │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                 │
│  Protocol        Direction          Best For              Overhead    Complexity   Browser     │
│  ─────────────────────────────────────────────────────────────────────────────────────────────  │
│  HTTP Polling    Client→Server      Simple updates        High        Low          ✅ Native   │
│  Long Polling    Client→Server      Notifications         Medium      Medium       ✅ Native   │
│  SSE             Server→Client      Live feeds            Low         Low          ✅ Native   │
│  WebSocket       Bidirectional      Chat, Gaming          Very Low    High         ✅ Native   │
│  MQTT            Pub/Sub (Both)     IoT, Sensors          Very Low    Medium       ⚠️ Library  │
│                                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Detailed Feature Comparison

| Feature | HTTP Polling | Long Polling | SSE | WebSocket | MQTT |
|---------|--------------|--------------|-----|-----------|------|
| **Connection** | New per request | Held open | Persistent | Persistent | Persistent |
| **Direction** | Client → Server | Client → Server | Server → Client | Bidirectional | Pub/Sub |
| **Binary Support** | ✅ (base64) | ✅ (base64) | ❌ Text only | ✅ Native | ✅ Native |
| **Auto Reconnect** | Manual | Manual | ✅ Built-in | Manual | ✅ Built-in |
| **Message QoS** | ❌ | ❌ | ❌ | ❌ | ✅ 3 levels |
| **Retained Messages** | ❌ | ❌ | ❌ | ❌ | ✅ |
| **Last Will** | ❌ | ❌ | ❌ | ❌ | ✅ |
| **Scalability** | Easy | Medium | Medium | Complex | Easy (broker) |
| **Firewall Friendly** | ✅ | ✅ | ✅ | ⚠️ May need config | ⚠️ Different port |
| **Bandwidth** | High | Medium | Low | Very Low | Very Low |
| **Latency** | High | Medium | Low | Very Low | Very Low |

## 1. HTTP Polling

### How It Works
```
Client                          Server
  │                               │
  │──── GET /updates ────────────→│
  │←─── Response (empty) ─────────│
  │     (wait 5 seconds)          │
  │──── GET /updates ────────────→│
  │←─── Response (data) ──────────│
  │     (wait 5 seconds)          │
  │──── GET /updates ────────────→│
  ...
```

### Implementation
```javascript
// Client
function poll() {
    fetch('/api/updates')
        .then(response => response.json())
        .then(data => {
            processUpdates(data);
            setTimeout(poll, 5000); // Poll every 5 seconds
        });
}
poll();
```

**Pros**: Simple, works everywhere
**Cons**: Inefficient, high server load, delayed updates

---

## 2. Long Polling

### How It Works
```
Client                          Server
  │                               │
  │──── GET /updates ────────────→│
  │          (server holds connection until data available)
  │          ... waiting ...      │
  │←─── Response (data) ──────────│
  │──── GET /updates ────────────→│ (immediately reconnect)
  │          ... waiting ...      │
```

### Implementation

**Server (Node.js)**:
```javascript
const pendingRequests = new Map();

app.get('/updates/:userId', (req, res) => {
    const userId = req.params.userId;
    
    // Store the response object
    pendingRequests.set(userId, res);
    
    // Set timeout (30 seconds)
    req.setTimeout(30000, () => {
        pendingRequests.delete(userId);
        res.json({ updates: [] });
    });
    
    // Clean up on disconnect
    req.on('close', () => {
        pendingRequests.delete(userId);
    });
});

// When there's new data
function notifyUser(userId, data) {
    const res = pendingRequests.get(userId);
    if (res) {
        res.json({ updates: [data] });
        pendingRequests.delete(userId);
    }
}
```

**Client**:
```javascript
async function longPoll() {
    try {
        const response = await fetch('/updates/user123', {
            signal: AbortSignal.timeout(35000)
        });
        const data = await response.json();
        processUpdates(data.updates);
    } catch (err) {
        console.log('Timeout or error, reconnecting...');
    }
    longPoll(); // Immediately reconnect
}
longPoll();
```

**Pros**: Near real-time, better than polling
**Cons**: Connection overhead, complex server management

---

## 3. Server-Sent Events (SSE)

### How It Works
```
Client                          Server
  │                               │
  │──── GET /events ─────────────→│
  │←─── HTTP 200 + headers ───────│
  │←─── data: event1 ─────────────│
  │←─── data: event2 ─────────────│
  │          ...                  │
  │←─── data: eventN ─────────────│
  │     (connection stays open)   │
```

### Implementation

**Server (Node.js)**:
```javascript
app.get('/events', (req, res) => {
    // Set SSE headers
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    
    // Send initial connection message
    res.write('data: connected\n\n');
    
    // Send periodic updates
    const interval = setInterval(() => {
        const data = JSON.stringify({
            time: new Date().toISOString(),
            message: 'Update'
        });
        res.write(`data: ${data}\n\n`);
    }, 1000);
    
    // Clean up on disconnect
    req.on('close', () => {
        clearInterval(interval);
    });
});
```

**Client**:
```javascript
const eventSource = new EventSource('/events');

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Received:', data);
};

eventSource.onerror = (error) => {
    console.error('SSE error:', error);
    eventSource.close();
};

// Named events
eventSource.addEventListener('notification', (event) => {
    console.log('Notification:', event.data);
});
```

### SSE Message Format
```
event: notification
id: 12345
retry: 3000
data: {"type": "message", "content": "Hello"}

event: heartbeat
data: ping
```

**Pros**: Simple API, automatic reconnection, built-in
**Cons**: Unidirectional, limited browser connections (6 per domain)

---

## 4. WebSocket

### How It Works
```
Client                          Server
  │                               │
  │──── HTTP Upgrade Request ────→│
  │←─── HTTP 101 Switching ───────│
  │                               │
  │←───── WebSocket Frame ────────│
  │────── WebSocket Frame ───────→│
  │←───── WebSocket Frame ────────│
  │       (bidirectional)         │
```

### Implementation

**Server (Node.js with ws)**:
```javascript
const WebSocket = require('ws');
const wss = new WebSocket.Server({ port: 8080 });

const clients = new Map();

wss.on('connection', (ws, req) => {
    const userId = req.url.split('?user=')[1];
    clients.set(userId, ws);
    
    ws.on('message', (message) => {
        const data = JSON.parse(message);
        handleMessage(userId, data);
    });
    
    ws.on('close', () => {
        clients.delete(userId);
    });
    
    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
    });
    
    // Send welcome message
    ws.send(JSON.stringify({ type: 'connected', userId }));
});

// Broadcast to all
function broadcast(data) {
    const message = JSON.stringify(data);
    clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(message);
        }
    });
}

// Send to specific user
function sendToUser(userId, data) {
    const client = clients.get(userId);
    if (client && client.readyState === WebSocket.OPEN) {
        client.send(JSON.stringify(data));
    }
}
```

**Client**:
```javascript
class WebSocketClient {
    constructor(url) {
        this.url = url;
        this.reconnectDelay = 1000;
        this.maxReconnectDelay = 30000;
        this.connect();
    }
    
    connect() {
        this.ws = new WebSocket(this.url);
        
        this.ws.onopen = () => {
            console.log('Connected');
            this.reconnectDelay = 1000; // Reset delay
        };
        
        this.ws.onmessage = (event) => {
            const data = JSON.parse(event.data);
            this.handleMessage(data);
        };
        
        this.ws.onclose = () => {
            console.log('Disconnected, reconnecting...');
            setTimeout(() => this.connect(), this.reconnectDelay);
            this.reconnectDelay = Math.min(
                this.reconnectDelay * 2,
                this.maxReconnectDelay
            );
        };
        
        this.ws.onerror = (error) => {
            console.error('WebSocket error:', error);
        };
    }
    
    send(data) {
        if (this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(data));
        }
    }
    
    handleMessage(data) {
        // Process incoming messages
        console.log('Received:', data);
    }
}

const client = new WebSocketClient('ws://localhost:8080?user=user123');
```

### WebSocket with Socket.IO
```javascript
// Server
const io = require('socket.io')(server);

io.on('connection', (socket) => {
    console.log('User connected:', socket.id);
    
    socket.on('join-room', (roomId) => {
        socket.join(roomId);
    });
    
    socket.on('message', (data) => {
        io.to(data.roomId).emit('message', data);
    });
    
    socket.on('disconnect', () => {
        console.log('User disconnected:', socket.id);
    });
});

// Client
const socket = io('http://localhost:3000');
socket.emit('join-room', 'room123');
socket.on('message', (data) => console.log(data));
```

**Pros**: Full duplex, low latency, efficient
**Cons**: Complex, connection management, scaling challenges

---

## 5. MQTT (Message Queuing Telemetry Transport)

### What is MQTT?
MQTT is a lightweight publish-subscribe messaging protocol designed for constrained devices and low-bandwidth, high-latency networks. Originally created for oil pipeline telemetry, it's now the standard for IoT communication.

### How It Works
```
                          ┌─────────────────┐
                          │   MQTT BROKER   │
                          │   (Mosquitto,   │
                          │    HiveMQ, etc) │
                          └────────┬────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
              ▼                    ▼                    ▼
     ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
     │  Publisher  │      │  Subscriber │      │  Pub + Sub  │
     │             │      │             │      │             │
     │ Temperature │      │  Dashboard  │      │   Device    │
     │   Sensor    │      │    App      │      │  Controller │
     └─────────────┘      └─────────────┘      └─────────────┘
           │                    ▲                    │▲
           │                    │                    ││
           └──► PUBLISH ────────┼──► SUBSCRIBE ◄────┘│
               topic:           │   topic:           │
            sensors/temp/1      │  sensors/temp/#    │
                                └────────────────────┘
```

### Core Concepts

#### Topics (Hierarchical)
```
home/livingroom/temperature      → Specific sensor
home/livingroom/+                → All livingroom sensors (+ = single level wildcard)
home/#                           → Everything under home (# = multi-level wildcard)
sensors/+/temperature            → All temperature sensors
```

#### Quality of Service (QoS) Levels
```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            MQTT QoS LEVELS                                           │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  QoS 0: At Most Once ("Fire and Forget")                                           │
│  ┌────────────┐         ┌────────────┐                                             │
│  │  Publisher │──PUBLISH──▶│   Broker   │  No acknowledgment                       │
│  └────────────┘         └────────────┘  Message may be lost                        │
│  Use: Non-critical data, high frequency sensors                                    │
│                                                                                     │
│  ─────────────────────────────────────────────────────────────────────────────────  │
│                                                                                     │
│  QoS 1: At Least Once                                                              │
│  ┌────────────┐         ┌────────────┐                                             │
│  │  Publisher │──PUBLISH──▶│   Broker   │                                          │
│  │            │◀──PUBACK───│            │  May have duplicates                     │
│  └────────────┘         └────────────┘                                             │
│  Use: Important messages where duplicates are OK                                   │
│                                                                                     │
│  ─────────────────────────────────────────────────────────────────────────────────  │
│                                                                                     │
│  QoS 2: Exactly Once                                                               │
│  ┌────────────┐         ┌────────────┐                                             │
│  │  Publisher │──PUBLISH──▶│   Broker   │                                          │
│  │            │◀──PUBREC───│            │  4-way handshake                         │
│  │            │──PUBREL───▶│            │  Guaranteed exactly once                 │
│  │            │◀──PUBCOMP──│            │  Highest overhead                        │
│  └────────────┘         └────────────┘                                             │
│  Use: Financial transactions, critical commands                                    │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

#### Retained Messages
```
Publisher sends:
  PUBLISH topic: "sensors/status" message: "online" retain: true

Later, new subscriber connects:
  SUBSCRIBE topic: "sensors/status"
  → Immediately receives: "online" (retained message)
  
Use: Last known state, device status, configuration
```

#### Last Will and Testament (LWT)
```
Client connects with:
  Will Topic: "devices/sensor1/status"
  Will Message: "offline"
  Will Retain: true

If client disconnects unexpectedly:
  → Broker publishes "offline" to "devices/sensor1/status"

Use: Device presence detection, graceful degradation
```

### Implementation

**Broker Setup (Mosquitto)**:
```bash
# mosquitto.conf
listener 1883
allow_anonymous false
password_file /etc/mosquitto/passwd

# With TLS
listener 8883
certfile /etc/mosquitto/certs/server.crt
keyfile /etc/mosquitto/certs/server.key
```

**Publisher (Python with paho-mqtt)**:
```python
import paho.mqtt.client as mqtt
import json
import time

class MQTTPublisher:
    def __init__(self, broker, port=1883):
        self.client = mqtt.Client()
        self.client.on_connect = self.on_connect
        self.client.connect(broker, port)
        self.client.loop_start()
    
    def on_connect(self, client, userdata, flags, rc):
        print(f"Connected with result code {rc}")
    
    def publish(self, topic, message, qos=1, retain=False):
        payload = json.dumps(message) if isinstance(message, dict) else message
        result = self.client.publish(topic, payload, qos=qos, retain=retain)
        return result.rc == mqtt.MQTT_ERR_SUCCESS

# Usage
publisher = MQTTPublisher("broker.example.com")

# Publish sensor data
publisher.publish(
    topic="sensors/temperature/room1",
    message={"value": 23.5, "unit": "celsius", "timestamp": time.time()},
    qos=1
)

# Publish with retain (status)
publisher.publish(
    topic="devices/sensor1/status",
    message="online",
    qos=1,
    retain=True
)
```

**Subscriber (Python)**:
```python
import paho.mqtt.client as mqtt
import json

class MQTTSubscriber:
    def __init__(self, broker, port=1883):
        self.client = mqtt.Client()
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.handlers = {}
        self.client.connect(broker, port)
    
    def on_connect(self, client, userdata, flags, rc):
        print(f"Connected with result code {rc}")
        # Resubscribe on reconnect
        for topic in self.handlers:
            client.subscribe(topic)
    
    def on_message(self, client, userdata, msg):
        try:
            payload = json.loads(msg.payload.decode())
        except:
            payload = msg.payload.decode()
        
        # Find matching handler
        for pattern, handler in self.handlers.items():
            if self.matches(pattern, msg.topic):
                handler(msg.topic, payload)
    
    def subscribe(self, topic, handler, qos=1):
        self.handlers[topic] = handler
        self.client.subscribe(topic, qos)
    
    def matches(self, pattern, topic):
        # Simple wildcard matching
        if pattern == topic:
            return True
        if '#' in pattern:
            prefix = pattern.replace('#', '')
            return topic.startswith(prefix)
        return False
    
    def run(self):
        self.client.loop_forever()

# Usage
subscriber = MQTTSubscriber("broker.example.com")

def handle_temperature(topic, data):
    print(f"Temperature from {topic}: {data['value']}°{data['unit']}")

def handle_status(topic, data):
    print(f"Device status: {topic} is {data}")

# Subscribe to topics
subscriber.subscribe("sensors/temperature/#", handle_temperature)
subscriber.subscribe("devices/+/status", handle_status)

subscriber.run()
```

**Node.js Implementation**:
```javascript
const mqtt = require('mqtt');

// Publisher
const publisher = mqtt.connect('mqtt://broker.example.com');

publisher.on('connect', () => {
    console.log('Publisher connected');
    
    // Publish with QoS 1
    publisher.publish('sensors/temperature', JSON.stringify({
        value: 23.5,
        timestamp: Date.now()
    }), { qos: 1 });
    
    // Publish with retain
    publisher.publish('devices/status', 'online', {
        qos: 1,
        retain: true
    });
});

// Subscriber
const subscriber = mqtt.connect('mqtt://broker.example.com');

subscriber.on('connect', () => {
    console.log('Subscriber connected');
    subscriber.subscribe('sensors/#', { qos: 1 });
});

subscriber.on('message', (topic, message) => {
    console.log(`${topic}: ${message.toString()}`);
});
```

### MQTT vs WebSocket

| Aspect | MQTT | WebSocket |
|--------|------|-----------|
| **Pattern** | Pub/Sub with broker | Direct client-server |
| **Architecture** | Decoupled (via broker) | Tightly coupled |
| **Scaling** | Add broker replicas | Complex (Redis Pub/Sub) |
| **Message Delivery** | QoS guarantees | No built-in guarantee |
| **Offline Support** | Retained messages, queuing | No native support |
| **Header Overhead** | 2 bytes minimum | 2-14 bytes |
| **Use Case** | IoT, sensors, telemetry | Chat, gaming, real-time UI |
| **Browser Support** | Via MQTT.js over WebSocket | Native |

### When to Use MQTT

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                        WHEN TO USE MQTT                                              │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  ✅ USE MQTT FOR:                         ❌ DON'T USE MQTT FOR:                    │
│  ├── IoT devices & sensors                ├── Browser-only applications            │
│  ├── Low bandwidth networks               ├── Request-response patterns            │
│  ├── Unreliable connections               ├── Large file transfers                 │
│  ├── Battery-powered devices              ├── When you need HTTP features          │
│  ├── Many-to-many communication           ├── Simple client-server apps            │
│  ├── Need for QoS guarantees              └── Low latency gaming (use WebSocket)  │
│  ├── Offline message queuing                                                       │
│  └── Device status tracking                                                        │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Pros**: Lightweight, QoS levels, retained messages, LWT, perfect for IoT
**Cons**: Requires broker, not native in browsers, different port (1883/8883)

---

## Scaling Real-Time Systems

### WebSocket Scaling Architecture
```
┌─────────────┐
│   Clients   │
└──────┬──────┘
       │
┌──────▼──────┐
│Load Balancer│ (Sticky Sessions or L7)
└──────┬──────┘
       │
┌──────┴──────────────┐
│                     │
▼                     ▼
┌─────────┐     ┌─────────┐
│ WS Srv 1│     │ WS Srv 2│
└────┬────┘     └────┬────┘
     │               │
     └───────┬───────┘
             │
      ┌──────▼──────┐
      │ Redis Pub/Sub│
      └─────────────┘
```

### Cross-Server Communication
```javascript
const Redis = require('ioredis');
const pub = new Redis();
const sub = new Redis();

// Subscribe to channel
sub.subscribe('messages');
sub.on('message', (channel, message) => {
    const data = JSON.parse(message);
    // Broadcast to local WebSocket clients
    broadcast(data);
});

// Publish message
function publishMessage(data) {
    pub.publish('messages', JSON.stringify(data));
}
```

---

## When to Use What

| Use Case | Recommended | Reason |
|----------|-------------|--------|
| **Chat Application** | WebSocket | Bidirectional, low latency |
| **Live Notifications** | SSE | Server push only |
| **Stock Tickers** | WebSocket/SSE | Continuous updates |
| **Social Feed** | SSE | One-way updates |
| **Online Gaming** | WebSocket | Real-time bidirectional |
| **Collaborative Editing** | WebSocket | Bidirectional sync |
| **Simple Polling** | Long Polling | Fallback option |
| **IoT Sensors** | MQTT | Low overhead, QoS, offline support |
| **Smart Home** | MQTT | Device status, commands |
| **Vehicle Telemetry** | MQTT | Unreliable networks, QoS |
| **Industrial Monitoring** | MQTT | Many sensors, low bandwidth |
| **Push Notifications (Mobile)** | MQTT/FCM | Battery efficient |

---

## Decision Flowchart

```
                                    START
                                      │
                                      ▼
                        ┌─────────────────────────┐
                        │   Need bidirectional    │
                        │     communication?      │
                        └───────────┬─────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │ NO                            │ YES
                    ▼                               ▼
           ┌───────────────┐              ┌───────────────┐
           │ Server → Client│              │   Browser or  │
           │     only?     │              │   IoT/Device? │
           └───────┬───────┘              └───────┬───────┘
                   │                              │
         ┌─────────┴─────────┐          ┌─────────┴─────────┐
         │ YES               │ NO       │ Browser           │ IoT
         ▼                   ▼          ▼                   ▼
    ┌─────────┐       ┌───────────┐  ┌─────────┐     ┌─────────┐
    │   SSE   │       │Long Polling│  │WebSocket│     │  MQTT   │
    └─────────┘       └───────────┘  └─────────┘     └─────────┘
    
    Live feeds         Fallback       Chat, Gaming    Sensors,
    Notifications      Simple apps    Collaboration   Telemetry
```

---

## Protocol Comparison by Scenario

### Scenario 1: Building a Chat App
```
                    WebSocket ← Winner
Requirement         ─────────────────────────────────────────────
Bidirectional       ✅ Yes     │ SSE ❌  │ Long Poll ❌ │ MQTT ⚠️
Low latency         ✅ <50ms   │ ✅      │ ❌ 100ms+   │ ✅
Browser native      ✅ Yes     │ ✅      │ ✅          │ ❌ Library
Typing indicators   ✅ Easy    │ ❌      │ ❌          │ ✅
Presence            ✅ Easy    │ ⚠️      │ ❌          │ ✅ (LWT)
```

### Scenario 2: IoT Sensor Network (1000 devices)
```
                    MQTT ← Winner
Requirement         ─────────────────────────────────────────────
Low bandwidth       ✅ 2 bytes │ WS 2-14 │ HTTP 100s  │
Unreliable network  ✅ QoS     │ ❌      │ ❌          │
Device status       ✅ LWT     │ ❌      │ ❌          │
Offline queuing     ✅ Yes     │ ❌      │ ❌          │
Many-to-many        ✅ Pub/Sub │ ⚠️      │ ❌          │
Battery efficient   ✅ Yes     │ ⚠️      │ ❌          │
```

### Scenario 3: Live Dashboard (Stock Prices)
```
                    SSE ← Winner (if one-way) / WebSocket (if interactive)
Requirement         ─────────────────────────────────────────────
Server push         ✅ Native  │ WS ✅   │ MQTT ✅    │
Auto-reconnect      ✅ Built-in│ ❌      │ ✅          │
Simple setup        ✅ Easy    │ ⚠️      │ ❌ Broker   │
Browser native      ✅ Yes     │ ✅      │ ❌          │
Firewall friendly   ✅ HTTP    │ ⚠️      │ ❌          │
```

---

## Best Practices

### Connection Management
1. Implement heartbeat/ping-pong
2. Handle reconnection with exponential backoff
3. Clean up stale connections
4. Use connection pooling

### Performance
1. Compress messages (WebSocket permessage-deflate)
2. Batch small messages
3. Use binary format for large data
4. Implement message queuing

### Security
1. Use WSS (WebSocket Secure)
2. Validate origin header
3. Authenticate connections
4. Rate limit messages

## Interview Questions

**Q: WebSocket vs HTTP/2 Server Push?**
> WebSocket is bidirectional and stays open. HTTP/2 Push is server-initiated but follows request-response pattern. WebSocket for real-time chat, HTTP/2 Push for preloading resources.

**Q: How to scale WebSocket servers?**
> Sticky sessions at load balancer + Redis Pub/Sub for cross-server communication. Each server handles its connections, Redis broadcasts across servers.

**Q: SSE vs WebSocket?**
> SSE is simpler, unidirectional (server to client), has automatic reconnection. WebSocket is bidirectional, more complex, better for chat/gaming. Use SSE when you only need server push.

**Q: When would you use MQTT over WebSocket?**
> MQTT for IoT/sensors with unreliable networks, need QoS guarantees, device status tracking (LWT), or many-to-many pub/sub. WebSocket for browser apps, gaming, chat where you need direct bidirectional communication without a broker.

**Q: Explain MQTT QoS levels.**
> - QoS 0: Fire and forget, no guarantee (sensor readings)
> - QoS 1: At least once, may duplicate (important but idempotent messages)
> - QoS 2: Exactly once, 4-way handshake (financial transactions, critical commands)

**Q: How does MQTT handle offline devices?**
> Three mechanisms: (1) Retained messages - new subscribers get last value, (2) Persistent sessions - broker queues messages for offline clients, (3) Last Will Testament (LWT) - broker publishes "offline" status when client disconnects unexpectedly.

**Q: WebSocket vs Long Polling for notifications?**
> WebSocket: Lower latency, more efficient for frequent updates, but more complex. Long Polling: Simpler, works everywhere, good fallback. Use WebSocket for real-time apps, Long Polling as fallback or for infrequent updates.

**Q: How would you design a real-time system for 1 million concurrent users?**
> Use WebSocket with:
> 1. Horizontal scaling with multiple WebSocket servers
> 2. Sticky sessions at load balancer (IP hash or cookie-based)
> 3. Redis Pub/Sub for cross-server message broadcasting
> 4. Connection pooling and heartbeat for stale connection cleanup
> 5. Message queuing (Kafka) for durability
> 6. Consider MQTT if it's IoT/device-focused

---

## Quick Reference Summary

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                      REAL-TIME PROTOCOLS - QUICK REFERENCE                           │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  HTTP Polling       Long Polling         SSE              WebSocket      MQTT       │
│  ────────────────   ────────────────     ──────────       ──────────     ────────   │
│  Client pulls       Server holds         Server pushes    Both ways      Pub/Sub    │
│  High overhead      Medium overhead      Low overhead     Very low       Very low   │
│  Simple             Medium complexity    Simple           Complex        Medium     │
│  Everywhere         Everywhere           Modern browsers  Modern         IoT focus  │
│                                                                                     │
│  Use for:           Use for:             Use for:         Use for:       Use for:   │
│  - Simple apps      - Fallback           - News feeds     - Chat         - IoT      │
│  - Infrequent       - Notifications      - Stock prices   - Gaming       - Sensors  │
│    updates          - Legacy support     - Social feeds   - Collab edit  - Telemetry│
│                                                                                     │
│  Example:           Example:             Example:         Example:       Example:   │
│  Dashboard refresh  Gmail notifications  Twitter feed     Slack          Nest       │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```
