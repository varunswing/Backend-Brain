# Real-Time Communication: WebSockets, SSE, and Long Polling

## Overview
Real-time communication enables instant data transfer between clients and servers without explicit client requests. Essential for chat apps, live updates, gaming, and collaborative tools.

## Communication Patterns Comparison

| Pattern | Direction | Connection | Use Case |
|---------|-----------|------------|----------|
| **HTTP Polling** | Client → Server | Short-lived | Simple updates |
| **Long Polling** | Client → Server | Extended | Notifications |
| **SSE** | Server → Client | Persistent | Live feeds |
| **WebSocket** | Bidirectional | Persistent | Chat, gaming |

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
