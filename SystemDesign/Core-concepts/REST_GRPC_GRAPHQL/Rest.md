## 1. REST (Representational State Transfer) — Detailed Overview

REST is an **architectural style** used for designing **networked applications**. It was introduced by **Roy Fielding** in his doctoral dissertation in 2000.

---

## 🔹 1. **Key Principles of REST**
REST is not a protocol but a set of **guidelines**:

1. **Statelessness**:  
   Each request from client to server must contain all information needed to understand and process the request.

2. **Client-Server Architecture**:  
   The client and server are independent. UI/UX on the client can evolve independently from the backend logic.

3. **Cacheable**:  
   Responses must explicitly indicate if they are cacheable to improve performance.

4. **Uniform Interface** (Core Principle):  
   Standard method of communication between client and server using:
   - Resource Identification via URI
   - Standardized methods (`GET`, `POST`, etc.)
   - Representation (usually JSON or XML)
   - Stateless communication

5. **Layered System**:  
   Intermediary layers (e.g., load balancers, proxies) can exist between client and server.

6. **Code on Demand** (Optional):  
   Servers can send executable code (like JavaScript) to clients.

---

## 🔹 2. **RESTful API Structure**
- **Base URI**: `https://api.example.com`
- **Resources**: Represented via URIs, e.g., `/users`, `/products/123`
- **Methods** (Mapped to CRUD):
  | HTTP Method | CRUD | Example                     |
  |-------------|------|-----------------------------|
  | GET         | Read | `GET /users`                |
  | POST        | Create | `POST /users`             |
  | PUT         | Update (full) | `PUT /users/123`   |
  | PATCH       | Update (partial) | `PATCH /users/123` |
  | DELETE      | Delete | `DELETE /users/123`       |

---

## 🔹 3. **REST Response Structure**
- **Status Code**: `200 OK`, `201 Created`, `404 Not Found`, etc.
- **Body**: Typically JSON (sometimes XML or HTML)
- **Headers**: `Content-Type`, `Authorization`, etc.

---

## 🔹 4. **Example**
### Request:
```
GET /users/1 HTTP/1.1
Host: api.example.com
Accept: application/json
```

### Response:
```json
{
  "id": 1,
  "name": "Alice",
  "email": "alice@example.com"
}
```

---

## 🔹 5. **Best Practices**
- Use **plural nouns** in resource names (`/users` not `/user`)
- Use **HTTP status codes** meaningfully
- Use **versioning** (`/v1/users`)
- Maintain **stateless** interactions
- Use **HATEOAS** optionally: links to related resources in responses

---

## 🔹 6. REST vs Other Architectures

| Feature          | REST              | SOAP               | GraphQL            |
|------------------|-------------------|---------------------|--------------------|
| Protocol         | HTTP              | HTTP, SMTP, etc.    | HTTP               |
| Format           | JSON/XML          | XML                 | JSON               |
| Flexibility      | High              | Low (strict)        | Very High          |
| Caching          | Yes               | Limited             | Limited            |

---

Would you like a visual diagram of a REST API flow or resource structure?

## 2. HTTP — Detailed Overview

HTTP (**HyperText Transfer Protocol**) is the **foundation of data communication** on the World Wide Web. It defines how clients (like browsers) communicate with servers to **request** or **send** data.

---

### 🔹 1. **Basics of HTTP**
- **Protocol**: Application-layer protocol over TCP/IP.
- **Stateless**: Each request is independent; server doesn't retain user information.
- **Client-Server Model**: Client sends request → Server processes → Responds.

---

### 🔹 2. **HTTP Methods**
| Method | Purpose |
|--------|---------|
| `GET` | Retrieve data |
| `POST` | Send data to create a resource |
| `PUT` | Update a resource |
| `DELETE` | Remove a resource |
| `PATCH` | Partially update a resource |
| `HEAD` | Like GET, but no body in response |
| `OPTIONS` | Returns allowed HTTP methods for resource |

---

### 🔹 3. **HTTP Request Structure**
- **Request Line**: `GET /index.html HTTP/1.1`
- **Headers**: Key-value pairs (`Content-Type`, `User-Agent`, etc.)
- **Body**: (Optional) Used in `POST`, `PUT`, etc.

---

### 🔹 4. **HTTP Response Structure**
- **Status Line**: `HTTP/1.1 200 OK`
- **Headers**: (`Content-Type`, `Content-Length`, etc.)
- **Body**: HTML, JSON, etc.

---

### 🔹 5. **Common HTTP Status Codes**
| Code | Meaning |
|------|---------|
| `200` | OK |
| `201` | Created |
| `204` | No Content |
| `301` | Moved Permanently |
| `302` | Found (Temporary redirect) |
| `400` | Bad Request |
| `401` | Unauthorized |
| `403` | Forbidden |
| `404` | Not Found |
| `500` | Internal Server Error |
| `503` | Service Unavailable |

---

### 🔹 6. **HTTP Versions**
- **HTTP/1.0**: No persistent connections.
- **HTTP/1.1**: Persistent connections by default, chunked transfer.
- **HTTP/2**: Binary protocol, multiplexing, header compression.
- **HTTP/3**: Based on QUIC (UDP), faster connection setup, better performance.

---

### 🔹 7. **Secure HTTP**
- **HTTPS** = HTTP + TLS/SSL
- Encrypts data in transit
- Uses port **443** (HTTP uses port **80**)

---

### 🔹 8. **Headers Examples**
- **Request**:
  - `Authorization: Bearer <token>`
  - `Content-Type: application/json`
- **Response**:
  - `Set-Cookie: sessionId=abc123`
  - `Cache-Control: no-cache`

---

### 🔹 9. **Cookies & Sessions**
- Cookies are set via HTTP headers.
- Used for session management, authentication, tracking.

---

### 🔹 10. **REST over HTTP**
- REST APIs use HTTP methods to perform CRUD operations on resources.

---

## 3. HTTP Methods — Detailed Overview

Here’s a detailed explanation of **all standard HTTP methods**, how they’re used, and their typical behavior in RESTful APIs:

---

## 🔹 1. **GET**
- **Purpose**: Retrieve data from the server.
- **Safe**: ✅ (doesn't change server state)
- **Idempotent**: ✅ (multiple identical calls have the same effect)
- **Request Body**: ❌ (not supported)
- **Response Body**: ✅ (contains requested data)

**Example**:
```
GET /users/123
```
Returns user with ID 123.

---

## 🔹 2. **POST**
- **Purpose**: Create a **new** resource on the server.
- **Safe**: ❌
- **Idempotent**: ❌ (same request may create multiple resources)
- **Request Body**: ✅ (contains data to be created)
- **Response**: Usually `201 Created`

**Example**:
```http
POST /users
Body:
{
  "name": "Alice",
  "email": "alice@example.com"
}
```

---

## 🔹 3. **PUT**
- **Purpose**: Replace an existing resource **entirely**
- **Safe**: ❌
- **Idempotent**: ✅
- **Request Body**: ✅ (contains full resource data)
- **Response**: `200 OK` or `204 No Content`

**Example**:
```http
PUT /users/123
Body:
{
  "name": "Bob",
  "email": "bob@example.com"
}
```
Replaces user 123 with new data.

---

## 🔹 4. **PATCH**
- **Purpose**: Partially update an existing resource.
- **Safe**: ❌
- **Idempotent**: ✅ (in most cases)
- **Request Body**: ✅ (only fields to be updated)

**Example**:
```http
PATCH /users/123
Body:
{
  "email": "newbob@example.com"
}
```
Only updates email.

---

## 🔹 5. **DELETE**
- **Purpose**: Delete a resource.
- **Safe**: ❌
- **Idempotent**: ✅
- **Request Body**: Rarely used

**Example**:
```
DELETE /users/123
```
Deletes user with ID 123.

---

## 🔹 6. **HEAD**
- **Purpose**: Like `GET` but only returns headers, no body.
- **Use Case**: Check if a resource exists or get metadata (e.g., content length).
- **Safe**: ✅
- **Idempotent**: ✅

**Example**:
```
HEAD /file.pdf
```

---

## 🔹 7. **OPTIONS**
- **Purpose**: Discover supported methods on a resource.
- **Safe**: ✅
- **Idempotent**: ✅

**Example**:
```
OPTIONS /users
```
Response might include:
```
Allow: GET, POST, PUT, DELETE
```

---

## 🔹 8. **CONNECT** (less common)
- **Purpose**: Establish a tunnel, typically for HTTPS via a proxy.
- **Use Case**: Web proxies, VPNs.

---

## 🔹 9. **TRACE** (rarely used)
- **Purpose**: Echoes back the received request, used for diagnostics.

---

### 🔸 Summary Table

| Method   | Safe | Idempotent | Usage                |
|----------|------|------------|----------------------|
| GET      | ✅   | ✅         | Read resource        |
| POST     | ❌   | ❌         | Create resource      |
| PUT      | ❌   | ✅         | Replace resource     |
| PATCH    | ❌   | ✅         | Partial update       |
| DELETE   | ❌   | ✅         | Delete resource      |
| HEAD     | ✅   | ✅         | Metadata only        |
| OPTIONS  | ✅   | ✅         | Discover capabilities|
| CONNECT  | ❌   | ❌         | Tunnel creation      |
| TRACE    | ✅   | ✅         | Echo request         |

---