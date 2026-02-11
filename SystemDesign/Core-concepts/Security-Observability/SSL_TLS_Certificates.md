# SSL/TLS & Certificates - Deep Dive

## Overview

SSL (Secure Sockets Layer) and TLS (Transport Layer Security) are cryptographic protocols that provide secure communication over networks. This document covers the protocol, certificates, implementation, and security best practices.

---

## SSL vs TLS

| Aspect | SSL | TLS |
|--------|-----|-----|
| **Status** | Deprecated | Current standard |
| **Latest Version** | SSL 3.0 (1996) | TLS 1.3 (2018) |
| **Security** | Vulnerable | Secure |
| **Usage** | Don't use | Use TLS 1.2 or 1.3 |

**Note**: The term "SSL" is still commonly used, but we're actually using TLS.

### TLS Versions

| Version | Year | Status | Notes |
|---------|------|--------|-------|
| **SSL 2.0** | 1995 | ❌ Deprecated | Seriously flawed |
| **SSL 3.0** | 1996 | ❌ Deprecated | POODLE attack |
| **TLS 1.0** | 1999 | ❌ Deprecated | BEAST attack |
| **TLS 1.1** | 2006 | ❌ Deprecated | No longer secure |
| **TLS 1.2** | 2008 | ✅ Acceptable | Still widely used |
| **TLS 1.3** | 2018 | ✅ Recommended | Faster, more secure |

---

## TLS Handshake Process

### TLS 1.2 Full Handshake

```
Client                                                Server
  │                                                      │
  │ ─────── ClientHello ─────────────────────────────→  │
  │   (TLS version, cipher suites, random)              │
  │                                                      │
  │ ←────── ServerHello ──────────────────────────────  │
  │   (Selected TLS version, cipher, random)            │
  │ ←────── Certificate ──────────────────────────────  │
  │   (Server's certificate chain)                      │
  │ ←────── ServerKeyExchange ────────────────────────  │
  │   (Key exchange parameters)                         │
  │ ←────── ServerHelloDone ──────────────────────────  │
  │                                                      │
  │ ─────── ClientKeyExchange ───────────────────────→  │
  │   (Pre-master secret, encrypted)                    │
  │ ─────── ChangeCipherSpec ────────────────────────→  │
  │ ─────── Finished ────────────────────────────────→  │
  │   (Encrypted with session keys)                     │
  │                                                      │
  │ ←────── ChangeCipherSpec ─────────────────────────  │
  │ ←────── Finished ─────────────────────────────────  │
  │                                                      │
  │ ═══════ Encrypted Application Data ═══════════════→ │
  │ ←══════ Encrypted Application Data ════════════════ │
```

**Steps Explained:**

1. **ClientHello**: Client proposes TLS versions and cipher suites
2. **ServerHello**: Server selects version and cipher
3. **Certificate**: Server sends its certificate for authentication
4. **ServerKeyExchange**: Server sends key exchange parameters (for DHE/ECDHE)
5. **ClientKeyExchange**: Client sends pre-master secret
6. **ChangeCipherSpec**: Both sides indicate switching to encrypted communication
7. **Finished**: Verify handshake integrity
8. **Application Data**: Encrypted communication begins

### TLS 1.3 Handshake (Faster)

TLS 1.3 reduces round trips from 2-RTT to 1-RTT:

```
Client                                                Server
  │                                                      │
  │ ─────── ClientHello ─────────────────────────────→  │
  │   + Key Share (ECDHE public key)                    │
  │                                                      │
  │ ←────── ServerHello ──────────────────────────────  │
  │   + Key Share                                       │
  │ ←────── {EncryptedExtensions} ────────────────────  │
  │ ←────── {Certificate} ────────────────────────────  │
  │ ←────── {CertificateVerify} ───────────────────────  │
  │ ←────── {Finished} ────────────────────────────────  │
  │   (Already encrypted!)                              │
  │                                                      │
  │ ─────── {Finished} ───────────────────────────────→ │
  │                                                      │
  │ ═══════ Encrypted Application Data ═══════════════→ │
```

**Improvements in TLS 1.3:**
- Only 1 round trip (faster)
- Perfect forward secrecy mandatory
- Removed insecure features (RSA key exchange, CBC mode)
- 0-RTT mode for resumed connections

---

## Digital Certificates

### What is a Certificate?

A digital certificate binds a public key to an identity (domain name, organization).

**Certificate Contents:**

```
Certificate:
    Version: 3
    Serial Number: 12:34:56:78:90:ab:cd:ef
    Signature Algorithm: sha256WithRSAEncryption
    Issuer: C=US, O=Let's Encrypt, CN=R3
    Validity:
        Not Before: Jan  1 00:00:00 2024 GMT
        Not After : Apr  1 00:00:00 2024 GMT
    Subject: CN=example.com
    Subject Public Key Info:
        Public Key Algorithm: rsaEncryption
        RSA Public-Key: (2048 bit)
        Modulus: 00:a1:b2:c3:...
        Exponent: 65537
    X509v3 Extensions:
        X509v3 Subject Alternative Name:
            DNS:example.com, DNS:www.example.com
        X509v3 Key Usage: critical
            Digital Signature, Key Encipherment
        X509v3 Extended Key Usage:
            TLS Web Server Authentication
    Signature: 5f:3a:2b:1c:...
```

### Certificate Chain (Chain of Trust)

```
┌─────────────────────────────────────────┐
│      Root CA Certificate                │
│  (Self-signed, trusted by OS/Browser)   │
│  Example: DigiCert Global Root CA       │
└───────────────┬─────────────────────────┘
                │ Signs
                ▼
┌─────────────────────────────────────────┐
│   Intermediate CA Certificate           │
│  (Issued by Root CA)                    │
│  Example: DigiCert TLS RSA SHA256       │
└───────────────┬─────────────────────────┘
                │ Signs
                ▼
┌─────────────────────────────────────────┐
│      End Entity Certificate             │
│  (Your domain certificate)              │
│  Example: example.com                   │
└─────────────────────────────────────────┘
```

**Why Chain?**
- Root CA private keys are kept offline for security
- If intermediate is compromised, only revoke that one
- Easier key rotation

### Certificate Types

#### By Validation Level

| Type | Validation | Cost | Time | Use Case |
|------|------------|------|------|----------|
| **DV (Domain Validated)** | Domain ownership only | Free-$50 | Minutes | Basic websites |
| **OV (Organization Validated)** | Domain + organization | $50-$200 | Days | Business websites |
| **EV (Extended Validation)** | Extensive verification | $200-$1000 | Weeks | Banking, e-commerce |

#### By Coverage

| Type | Covers | Example |
|------|--------|---------|
| **Single Domain** | One domain | example.com |
| **Wildcard** | Domain + all subdomains | *.example.com |
| **Multi-Domain (SAN)** | Multiple specific domains | example.com, api.example.com, app.example.org |

---

## Certificate Management

### Obtaining a Certificate

#### Option 1: Let's Encrypt (Free, Automated)

```bash
# Install certbot
sudo apt-get install certbot python3-certbot-nginx

# Obtain certificate (HTTP-01 challenge)
sudo certbot --nginx -d example.com -d www.example.com

# Certificate locations:
# /etc/letsencrypt/live/example.com/fullchain.pem  (certificate + chain)
# /etc/letsencrypt/live/example.com/privkey.pem    (private key)

# Auto-renewal (certbot sets up cron job)
sudo certbot renew --dry-run
```

#### Option 2: Manual CSR (Certificate Signing Request)

```bash
# 1. Generate private key
openssl genrsa -out example.com.key 2048

# 2. Generate CSR
openssl req -new -key example.com.key -out example.com.csr \
    -subj "/C=US/ST=California/L=San Francisco/O=Example Inc/CN=example.com"

# 3. Submit CSR to CA (via web interface)
cat example.com.csr
# Copy contents to CA's order form

# 4. CA returns signed certificate
# Download: example.com.crt

# 5. Install certificate on server
```

### Certificate Validation Methods

#### HTTP-01 Challenge (Let's Encrypt)

```
CA → "Prove you control example.com"
    → "Place file at http://example.com/.well-known/acme-challenge/TOKEN"

Server creates file at that location

CA requests the URL
    → If file exists with correct content → Certificate issued
```

#### DNS-01 Challenge (for wildcards)

```
CA → "Add TXT record: _acme-challenge.example.com = TOKEN"

Admin adds DNS record

CA queries DNS
    → If TXT record exists → Certificate issued
```

---

## Mutual TLS (mTLS)

### What is mTLS?

**Regular TLS**: Only server is authenticated (client trusts server)  
**Mutual TLS**: Both client and server authenticate each other

**Use Cases:**
- Microservices authentication (service-to-service)
- IoT device authentication
- **Agent authentication (like N-able RMM agents!)**
- Zero-trust networks

### mTLS Flow

```
Client                                    Server
  │                                          │
  │ ─────── ClientHello ──────────────────→ │
  │                                          │
  │ ←────── ServerHello ───────────────────  │
  │ ←────── Server Certificate ────────────  │
  │ ←────── Request Client Certificate ────  │
  │                                          │
  │ ─────── Client Certificate ────────────→ │
  │ ─────── CertificateVerify ─────────────→ │
  │   (Proof of private key ownership)       │
  │                                          │
  │ ═══════ Both Authenticated ════════════  │
```

### mTLS Implementation

#### Server-Side (Go - N-able's language)

```go
package main

import (
    "crypto/tls"
    "crypto/x509"
    "io/ioutil"
    "log"
    "net/http"
)

func setupMTLSServer() {
    // Load server certificate
    serverCert, err := tls.LoadX509KeyPair("server.crt", "server.key")
    if err != nil {
        log.Fatal(err)
    }
    
    // Load CA certificate to verify client certificates
    caCert, err := ioutil.ReadFile("ca.crt")
    if err != nil {
        log.Fatal(err)
    }
    
    caCertPool := x509.NewCertPool()
    caCertPool.AppendCertsFromPEM(caCert)
    
    // Configure TLS
    tlsConfig := &tls.Config{
        Certificates: []tls.Certificate{serverCert},
        ClientAuth:   tls.RequireAndVerifyClientCert,  // Require client cert
        ClientCAs:    caCertPool,                       // Trusted CAs for client certs
        MinVersion:   tls.VersionTLS12,
        CipherSuites: []uint16{
            tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        },
    }
    
    server := &http.Server{
        Addr:      ":8443",
        TLSConfig: tlsConfig,
        Handler:   http.HandlerFunc(handleRequest),
    }
    
    log.Println("Starting mTLS server on :8443")
    log.Fatal(server.ListenAndServeTLS("", ""))  // Certs already in TLSConfig
}

func handleRequest(w http.ResponseWriter, r *http.Request) {
    // Extract client certificate information
    if r.TLS != nil && len(r.TLS.PeerCertificates) > 0 {
        clientCert := r.TLS.PeerCertificates[0]
        
        log.Printf("Client authenticated: %s", clientCert.Subject.CommonName)
        
        // Get device ID from certificate
        deviceID := clientCert.Subject.CommonName
        
        w.Write([]byte("Hello " + deviceID))
    } else {
        http.Error(w, "No client certificate", http.StatusUnauthorized)
    }
}
```

#### Client-Side (Agent)

```go
package main

import (
    "crypto/tls"
    "crypto/x509"
    "io/ioutil"
    "log"
    "net/http"
)

func setupMTLSClient() *http.Client {
    // Load client certificate
    clientCert, err := tls.LoadX509KeyPair("device-123.crt", "device-123.key")
    if err != nil {
        log.Fatal(err)
    }
    
    // Load CA certificate to verify server
    caCert, err := ioutil.ReadFile("ca.crt")
    if err != nil {
        log.Fatal(err)
    }
    
    caCertPool := x509.NewCertPool()
    caCertPool.AppendCertsFromPEM(caCert)
    
    // Configure TLS
    tlsConfig := &tls.Config{
        Certificates: []tls.Certificate{clientCert},  // Client cert
        RootCAs:      caCertPool,                     // Trusted CAs for server cert
        MinVersion:   tls.VersionTLS12,
    }
    
    transport := &http.Transport{
        TLSClientConfig: tlsConfig,
    }
    
    return &http.Client{
        Transport: transport,
    }
}

func main() {
    client := setupMTLSClient()
    
    // Make authenticated request
    resp, err := client.Get("https://api.example.com/heartbeat")
    if err != nil {
        log.Fatal(err)
    }
    defer resp.Body.Close()
    
    body, _ := ioutil.ReadAll(resp.Body)
    log.Printf("Response: %s", body)
}
```

### Certificate Management for mTLS at Scale

**Challenge**: Managing certificates for 9M+ agents (N-able scale)

```go
package certmanager

import (
    "crypto/rand"
    "crypto/rsa"
    "crypto/x509"
    "crypto/x509/pkix"
    "encoding/pem"
    "math/big"
    "time"
)

type CertificateManager struct {
    CACert       *x509.Certificate
    CAPrivateKey *rsa.PrivateKey
    CertStore    CertificateStore  // Database/S3
}

// Issue certificate for new agent
func (cm *CertificateManager) IssueDeviceCertificate(deviceID string) (*Certificate, error) {
    // Generate key pair for device
    privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
    if err != nil {
        return nil, err
    }
    
    // Create certificate template
    template := &x509.Certificate{
        SerialNumber: big.NewInt(time.Now().Unix()),
        Subject: pkix.Name{
            CommonName:   deviceID,
            Organization: []string{"N-able Managed Devices"},
        },
        NotBefore:             time.Now(),
        NotAfter:              time.Now().Add(365 * 24 * time.Hour),  // 1 year
        KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
        ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
        BasicConstraintsValid: true,
        IsCA:                  false,
    }
    
    // Sign certificate with CA
    certDER, err := x509.CreateCertificate(
        rand.Reader,
        template,
        cm.CACert,
        &privateKey.PublicKey,
        cm.CAPrivateKey,
    )
    if err != nil {
        return nil, err
    }
    
    // Encode to PEM
    certPEM := pem.EncodeToMemory(&pem.Block{
        Type:  "CERTIFICATE",
        Bytes: certDER,
    })
    
    keyPEM := pem.EncodeToMemory(&pem.Block{
        Type:  "RSA PRIVATE KEY",
        Bytes: x509.MarshalPKCS1PrivateKey(privateKey),
    })
    
    // Store certificate
    cert := &Certificate{
        DeviceID:    deviceID,
        Certificate: certPEM,
        PrivateKey:  keyPEM,
        IssuedAt:    time.Now(),
        ExpiresAt:   time.Now().Add(365 * 24 * time.Hour),
    }
    
    cm.CertStore.Save(cert)
    
    return cert, nil
}

// Revoke certificate (device compromised)
func (cm *CertificateManager) RevokeCertificate(deviceID string) error {
    // Add to Certificate Revocation List (CRL)
    return cm.CertStore.AddToCRL(deviceID)
}

// Rotate certificate (before expiration)
func (cm *CertificateManager) RotateCertificate(deviceID string) (*Certificate, error) {
    // Revoke old certificate
    cm.RevokeCertificate(deviceID)
    
    // Issue new certificate
    return cm.IssueDeviceCertificate(deviceID)
}
```

---

## Certificate Pinning

Prevent man-in-the-middle attacks by "pinning" expected certificate.

### Why Pin?

Even with valid certificates, MITM is possible if:
- User installs malicious root CA
- Compromised CA issues fake certificate
- DNS spoofing + valid certificate

### Implementation

```go
package main

import (
    "crypto/sha256"
    "crypto/tls"
    "encoding/hex"
    "fmt"
)

// Pin the server's public key hash
const expectedPublicKeyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

func verifyPinning(conn *tls.Conn) error {
    // Get peer certificates
    certs := conn.ConnectionState().PeerCertificates
    if len(certs) == 0 {
        return fmt.Errorf("no certificates provided")
    }
    
    // Hash the public key
    serverCert := certs[0]
    publicKeyHash := sha256.Sum256(serverCert.RawSubjectPublicKeyInfo)
    hashHex := hex.EncodeToString(publicKeyHash[:])
    
    // Compare with pinned hash
    if hashHex != expectedPublicKeyHash {
        return fmt.Errorf("certificate pinning failed: expected %s, got %s", 
            expectedPublicKeyHash, hashHex)
    }
    
    return nil
}

func dialWithPinning(address string) (*tls.Conn, error) {
    conn, err := tls.Dial("tcp", address, &tls.Config{
        InsecureSkipVerify: true,  // We'll verify manually
    })
    if err != nil {
        return nil, err
    }
    
    // Verify pinning
    if err := verifyPinning(conn); err != nil {
        conn.Close()
        return nil, err
    }
    
    return conn, nil
}
```

**Pros:**
- Prevents MITM even with compromised CA
- Extra security layer

**Cons:**
- Brittle (breaks when certificate rotates)
- Requires app updates to change pins
- Use "backup pins" for rotation

---

## Common TLS Attacks & Mitigations

### 1. Man-in-the-Middle (MITM)

**Attack**: Intercept communication between client and server

**Mitigation**:
- Use HTTPS everywhere
- HSTS (force HTTPS)
- Certificate pinning
- Don't ignore certificate warnings

### 2. Downgrade Attack

**Attack**: Force use of weak TLS version or cipher

**Mitigation**:
```go
tlsConfig := &tls.Config{
    MinVersion: tls.VersionTLS12,  // Reject TLS 1.1 and below
    CipherSuites: []uint16{
        // Only allow strong ciphers
        tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
    },
}
```

### 3. POODLE (Padding Oracle On Downgraded Legacy Encryption)

**Attack**: Exploit SSL 3.0 CBC mode

**Mitigation**: Disable SSL 3.0 (use TLS 1.2+)

### 4. BEAST (Browser Exploit Against SSL/TLS)

**Attack**: Exploit TLS 1.0 CBC mode

**Mitigation**: Use TLS 1.2+, prefer AEAD ciphers (GCM, ChaCha20)

### 5. Heartbleed

**Attack**: OpenSSL bug leaking memory

**Mitigation**: Keep OpenSSL updated, revoke/reissue certificates

### 6. Certificate Spoofing

**Attack**: Fake certificate from compromised CA

**Mitigation**:
- Certificate Transparency logs
- CT (Certificate Transparency) monitoring
- Certificate pinning

---

## HTTP Strict Transport Security (HSTS)

Force browsers to use HTTPS.

```go
w.Header().Set("Strict-Transport-Security", 
    "max-age=31536000; includeSubDomains; preload")
```

**Parameters:**
- `max-age=31536000`: Remember for 1 year
- `includeSubDomains`: Apply to all subdomains
- `preload`: Submit to browser preload list

**HSTS Preload**: Hardcode in browsers (100% protection)

```bash
# Submit to preload list
https://hstspreload.org/
```

---

## Certificate Revocation

### Certificate Revocation List (CRL)

Periodically published list of revoked certificates.

```bash
# Check CRL
openssl crl -in revoked.crl -noout -text

# CRL Distribution Point in certificate
X509v3 CRL Distribution Points:
    URI:http://crl.example.com/revoked.crl
```

**Cons:**
- Can grow large
- Clients must download entire list
- Not real-time

### Online Certificate Status Protocol (OCSP)

Real-time certificate status check.

```
Client → OCSP Responder: "Is certificate X revoked?"
OCSP Responder → Client: "Good" | "Revoked" | "Unknown"
```

**OCSP Stapling**: Server fetches OCSP response, sends to client (faster, more private)

```nginx
# Nginx OCSP stapling
ssl_stapling on;
ssl_stapling_verify on;
ssl_trusted_certificate /path/to/chain.pem;
resolver 8.8.8.8;
```

---

## TLS Configuration Best Practices

### Server Configuration (Nginx)

```nginx
server {
    listen 443 ssl http2;
    server_name example.com;
    
    # Certificates
    ssl_certificate /etc/letsencrypt/live/example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/example.com/privkey.pem;
    
    # Protocol versions
    ssl_protocols TLSv1.2 TLSv1.3;
    
    # Cipher suites (prioritize security)
    ssl_ciphers 'ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305';
    ssl_prefer_server_ciphers off;  # Let client choose (TLS 1.3)
    
    # Perfect forward secrecy
    ssl_ecdh_curve secp384r1;
    
    # Session resumption
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
    ssl_session_tickets off;  # Disable for better security
    
    # OCSP stapling
    ssl_stapling on;
    ssl_stapling_verify on;
    ssl_trusted_certificate /etc/letsencrypt/live/example.com/chain.pem;
    resolver 8.8.8.8 8.8.4.4 valid=300s;
    resolver_timeout 5s;
    
    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;
    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    
    location / {
        proxy_pass http://backend;
    }
}

# Redirect HTTP to HTTPS
server {
    listen 80;
    server_name example.com;
    return 301 https://$server_name$request_uri;
}
```

### Application Configuration (Go)

```go
package main

import (
    "crypto/tls"
    "log"
    "net/http"
    "time"
)

func main() {
    // TLS configuration
    tlsConfig := &tls.Config{
        // Protocol versions
        MinVersion: tls.VersionTLS12,
        MaxVersion: tls.VersionTLS13,
        
        // Cipher suites (TLS 1.2)
        CipherSuites: []uint16{
            tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            tls.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
            tls.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
        },
        
        // Prefer server cipher order
        PreferServerCipherSuites: false,  // TLS 1.3 ignores this
        
        // Curve preferences
        CurvePreferences: []tls.CurveID{
            tls.X25519,
            tls.CurveP256,
        },
        
        // Session resumption
        SessionTicketsDisabled: true,  // Better security
    }
    
    server := &http.Server{
        Addr:         ":8443",
        Handler:      http.HandlerFunc(handler),
        TLSConfig:    tlsConfig,
        ReadTimeout:  10 * time.Second,
        WriteTimeout: 10 * time.Second,
        IdleTimeout:  120 * time.Second,
    }
    
    log.Println("Starting server on :8443")
    log.Fatal(server.ListenAndServeTLS("server.crt", "server.key"))
}

func handler(w http.ResponseWriter, r *http.Request) {
    // Add security headers
    w.Header().Set("Strict-Transport-Security", 
        "max-age=31536000; includeSubDomains; preload")
    w.Header().Set("X-Frame-Options", "DENY")
    w.Header().Set("X-Content-Type-Options", "nosniff")
    
    w.Write([]byte("Hello, TLS!"))
}
```

---

## Testing TLS Configuration

### SSL Labs Test

```bash
# Online test (best tool)
https://www.ssllabs.com/ssltest/analyze.html?d=example.com

# Aim for A+ rating
```

### Command Line Tools

```bash
# Test TLS connection
openssl s_client -connect example.com:443 -tls1_3

# View certificate
openssl s_client -connect example.com:443 -showcerts

# Test specific cipher
openssl s_client -connect example.com:443 -cipher ECDHE-RSA-AES256-GCM-SHA384

# Check certificate expiry
echo | openssl s_client -connect example.com:443 2>/dev/null | \
    openssl x509 -noout -dates

# Verify certificate chain
openssl verify -CAfile ca-bundle.crt example.com.crt
```

### testssl.sh (Comprehensive Testing)

```bash
# Install
git clone https://github.com/drwetter/testssl.sh.git

# Test
./testssl.sh https://example.com

# Checks for:
# - Protocol support
# - Cipher suites
# - Vulnerabilities (POODLE, BEAST, Heartbleed)
# - Certificate issues
# - Forward secrecy
# - And more...
```

---

## Performance Optimization

### Session Resumption

**Problem**: TLS handshake is expensive (2-RTT for TLS 1.2)

**Solution**: Resume previous session without full handshake

#### Session IDs (TLS 1.2)

```
First Connection: Full handshake
Server stores session state, returns session ID

Subsequent Connections:
Client sends session ID → Server resumes → Skip key exchange
```

#### Session Tickets (Better)

```
Server encrypts session state, sends to client
Client stores ticket, presents on reconnect
Server decrypts ticket, resumes session

Advantage: Server doesn't store state
```

**Configuration:**

```go
tlsConfig := &tls.Config{
    ClientSessionCache: tls.NewLRUClientSessionCache(128),  // Client-side
}

// Server-side
tlsConfig := &tls.Config{
    SessionTicketsDisabled: false,  // Enable tickets
}
```

### TLS 1.3 0-RTT (Zero Round Trip Time)

Resume connection with application data in first message.

```
Client sends:
  - ClientHello
  - Early Data (application data)
  
Server responds immediately with data

Faster but less secure (replay attacks possible)
```

**When to use**: Idempotent requests only (GET, safe POST)

### Certificate Compression

TLS 1.3 supports certificate compression (reduce handshake size).

```go
// Brotli-compressed certificates
tlsConfig.CertCompressionAlgo = []CertCompressionAlgo{
    {ID: CertCompressionBrotli},
}
```

---

## Monitoring & Observability

### Metrics to Track

```
# Certificate expiry
tls_certificate_expiry_days{domain="example.com"} 45

# TLS version distribution
tls_version{version="1.2"} 350
tls_version{version="1.3"} 650

# Cipher suite usage
tls_cipher{cipher="TLS_AES_256_GCM_SHA384"} 800

# Handshake duration
tls_handshake_duration_seconds{quantile="0.95"} 0.05

# Certificate errors
tls_certificate_errors_total 5
```

### Prometheus Exporter (Go)

```go
package main

import (
    "github.com/prometheus/client_golang/prometheus"
    "github.com/prometheus/client_golang/prometheus/promauto"
)

var (
    tlsHandshakeDuration = promauto.NewHistogram(prometheus.HistogramOpts{
        Name: "tls_handshake_duration_seconds",
        Help: "TLS handshake duration",
    })
    
    tlsVersion = promauto.NewCounterVec(prometheus.CounterOpts{
        Name: "tls_connections_total",
        Help: "TLS connections by version",
    }, []string{"version"})
    
    certExpiry = promauto.NewGaugeVec(prometheus.GaugeOpts{
        Name: "tls_certificate_expiry_days",
        Help: "Days until certificate expiry",
    }, []string{"domain"})
)

func monitorTLSConnection(conn *tls.Conn) {
    state := conn.ConnectionState()
    
    // Track version
    version := "unknown"
    switch state.Version {
    case tls.VersionTLS12:
        version = "1.2"
    case tls.VersionTLS13:
        version = "1.3"
    }
    tlsVersion.WithLabelValues(version).Inc()
    
    // Track handshake duration
    tlsHandshakeDuration.Observe(state.HandshakeComplete.Seconds())
}
```

### Certificate Expiry Monitoring

```bash
#!/bin/bash
# Script to check certificate expiry

DOMAIN=$1
DAYS_WARNING=30

# Get certificate expiry date
EXPIRY=$(echo | openssl s_client -connect $DOMAIN:443 2>/dev/null | \
         openssl x509 -noout -dates | grep notAfter | cut -d= -f2)

# Calculate days until expiry
EXPIRY_EPOCH=$(date -d "$EXPIRY" +%s)
NOW_EPOCH=$(date +%s)
DAYS_LEFT=$(( ($EXPIRY_EPOCH - $NOW_EPOCH) / 86400 ))

echo "Certificate expires in $DAYS_LEFT days"

if [ $DAYS_LEFT -lt $DAYS_WARNING ]; then
    echo "WARNING: Certificate expiring soon!"
    # Send alert
fi
```

---

## Certificate Automation

### ACME Protocol (Let's Encrypt)

Automatic certificate management.

```go
package main

import (
    "golang.org/x/crypto/acme/autocert"
    "net/http"
)

func main() {
    // Automatic HTTPS with Let's Encrypt
    certManager := autocert.Manager{
        Prompt:     autocert.AcceptTOS,
        HostPolicy: autocert.HostWhitelist("example.com", "www.example.com"),
        Cache:      autocert.DirCache("/var/www/.cache"),  // Store certificates
    }
    
    server := &http.Server{
        Addr:    ":https",
        Handler: http.HandlerFunc(handler),
        TLSConfig: &tls.Config{
            GetCertificate: certManager.GetCertificate,
        },
    }
    
    // Also handle HTTP-01 challenge
    go http.ListenAndServe(":http", certManager.HTTPHandler(nil))
    
    server.ListenAndServeTLS("", "")
}
```

### Cert-Manager (Kubernetes)

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: example-com
spec:
  secretName: example-com-tls
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
  - example.com
  - www.example.com
  renewBefore: 720h  # Renew 30 days before expiry
```

---

## Interview Questions

**Q: Explain the TLS handshake process.**
> Client sends ClientHello with supported versions/ciphers. Server responds with ServerHello selecting version/cipher, sends certificate. Client verifies certificate, generates pre-master secret, encrypts with server's public key. Both derive session keys. Exchange ChangeCipherSpec and Finished messages. TLS 1.3 is faster (1-RTT vs 2-RTT).

**Q: What is the difference between TLS and mTLS?**
> TLS: Only server authenticated (client trusts server via certificate). mTLS: Both authenticate (both have certificates). Used for service-to-service auth, IoT, agent authentication. More secure but complex to manage certificates at scale.

**Q: How do you handle certificate rotation for 9M+ devices?**
> 1) Issue certificates with reasonable TTL (1 year), 2) Implement automatic renewal (agents check expiry, request new cert before expiration), 3) Certificate management service with database tracking, 4) Graceful fallback (allow old cert for grace period), 5) Monitoring for expiring certs, 6) ACME protocol for automation.

**Q: What is certificate pinning and when to use it?**
> Pinning = hardcoding expected certificate/public key hash in client. Prevents MITM even if CA is compromised. Use for high-security apps (banking, critical infrastructure). Cons: Brittle (breaks on cert rotation), requires app updates. Use backup pins for rotation.

**Q: How to prevent downgrade attacks?**
> 1) Set MinVersion to TLS 1.2+, 2) Disable weak ciphers, 3) Use HSTS to force HTTPS, 4) TLS 1.3 has built-in protection (no legacy algorithms), 5) Monitor for connections attempting old versions.

**Q: Explain perfect forward secrecy.**
> Even if server's private key is compromised, past sessions can't be decrypted. Achieved using ephemeral key exchange (DHE, ECDHE). Each session has unique session key, deleted after use. TLS 1.3 mandates PFS. Choose ECDHE ciphers.

**Q: How to debug TLS connection issues?**
> 1) `openssl s_client -connect host:443` - shows handshake details, 2) Check certificate chain validity, 3) Verify cert not expired, 4) Check cipher compatibility, 5) Inspect server logs, 6) Use Wireshark for packet capture, 7) Test with testssl.sh, 8) Check firewall rules.

---

## Quick Reference

### TLS Versions to Use

| Version | Status | When to Use |
|---------|--------|-------------|
| **TLS 1.3** | ✅ Recommended | Modern systems, best security & performance |
| **TLS 1.2** | ✅ Acceptable | Legacy compatibility, still secure |
| **TLS 1.1** | ❌ Deprecated | Don't use |
| **TLS 1.0** | ❌ Deprecated | Don't use |
| **SSL 3.0** | ❌ Deprecated | Seriously, don't use |

### Recommended Cipher Suites (TLS 1.2)

```
TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305
TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305
TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
```

### Certificate Checklist

- [ ] Valid (not expired)
- [ ] Complete chain (root + intermediates)
- [ ] Correct domain (matches hostname)
- [ ] Proper key usage
- [ ] 2048+ bit RSA or 256+ bit ECC
- [ ] Issued by trusted CA
- [ ] Not revoked (check CRL/OCSP)
- [ ] SAN includes all domains

### Security Best Practices

- [ ] TLS 1.2+ only (TLS 1.3 preferred)
- [ ] Strong cipher suites (ECDHE + AEAD)
- [ ] Perfect forward secrecy (PFS)
- [ ] HSTS enabled
- [ ] Certificate valid & not expiring soon
- [ ] Automated renewal (Let's Encrypt/ACME)
- [ ] OCSP stapling enabled
- [ ] Session tickets disabled (for best security)
- [ ] Monitor certificate expiry
- [ ] Regular security audits (SSL Labs)

---

## Further Reading

- [Mozilla SSL Configuration Generator](https://ssl-config.mozilla.org/)
- [SSL Labs Testing Tool](https://www.ssllabs.com/ssltest/)
- [Let's Encrypt Documentation](https://letsencrypt.org/docs/)
- [RFC 8446 - TLS 1.3](https://tools.ietf.org/html/rfc8446)
- [OWASP Transport Layer Protection](https://cheatsheetseries.owasp.org/cheatsheets/Transport_Layer_Protection_Cheat_Sheet.html)
