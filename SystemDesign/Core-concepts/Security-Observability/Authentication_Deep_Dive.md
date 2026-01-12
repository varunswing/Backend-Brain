# Authentication Deep Dive: SSO, OAuth, Password Security

## Single Sign-On (SSO)

### What is SSO?

SSO allows users to authenticate once and access multiple applications without re-entering credentials.

```
Traditional (No SSO):
User → App A → Login
User → App B → Login (again!)
User → App C → Login (again!!)

With SSO:
User → Identity Provider → Login once
User → App A → Already authenticated ✓
User → App B → Already authenticated ✓
User → App C → Already authenticated ✓
```

---

### SSO Flow (SAML)

```
┌────────┐     ┌────────────────┐     ┌────────────────┐
│  User  │     │ Service Provider│    │Identity Provider│
│        │     │    (Your App)   │    │ (Okta/Azure AD)│
└───┬────┘     └───────┬────────┘     └───────┬────────┘
    │                  │                      │
    │ 1. Access App    │                      │
    │─────────────────→│                      │
    │                  │                      │
    │                  │ 2. Redirect to IdP   │
    │                  │     (SAML Request)   │
    │←─────────────────│                      │
    │                  │                      │
    │ 3. Login at IdP  │                      │
    │─────────────────────────────────────────→│
    │                  │                      │
    │ 4. IdP validates │                      │
    │   credentials    │                      │
    │                  │                      │
    │←─────────────────────────────────────────│
    │  5. SAML Response (Assertion)           │
    │                  │                      │
    │ 6. Submit assertion to App              │
    │─────────────────→│                      │
    │                  │                      │
    │ 7. App validates │                      │
    │    assertion     │                      │
    │                  │                      │
    │ 8. Access granted│                      │
    │←─────────────────│                      │
```

---

### SSO Protocols

| Protocol | Use Case | Token Format |
|----------|----------|--------------|
| **SAML 2.0** | Enterprise SSO | XML |
| **OAuth 2.0** | Authorization | Access Token |
| **OIDC** | Authentication + OAuth | JWT (ID Token) |
| **Kerberos** | Windows/AD | Tickets |

---

## OAuth 2.0 Flows

### Authorization Code Flow (Most Secure)

For server-side applications.

```
┌────────┐     ┌────────────┐     ┌──────────────────┐
│  User  │     │  Your App  │     │ Auth Server      │
│        │     │ (Client)   │     │ (Google/Auth0)   │
└───┬────┘     └─────┬──────┘     └────────┬─────────┘
    │                │                     │
    │ 1. Click Login │                     │
    │───────────────→│                     │
    │                │                     │
    │                │ 2. Redirect to Auth │
    │←───────────────│                     │
    │                                      │
    │ 3. Login & Consent                   │
    │─────────────────────────────────────→│
    │                                      │
    │ 4. Redirect with Auth Code           │
    │←─────────────────────────────────────│
    │                │                     │
    │ 5. Submit code │                     │
    │───────────────→│                     │
    │                │                     │
    │                │ 6. Exchange code    │
    │                │    for tokens       │
    │                │────────────────────→│
    │                │                     │
    │                │ 7. Access Token +   │
    │                │    Refresh Token    │
    │                │←────────────────────│
    │                │                     │
    │ 8. Access granted                    │
    │←───────────────│                     │
```

### Authorization Code with PKCE

For mobile/SPA apps (no client secret).

```python
import hashlib
import base64
import secrets

# Generate code verifier (random string)
code_verifier = secrets.token_urlsafe(32)

# Generate code challenge
code_challenge = base64.urlsafe_b64encode(
    hashlib.sha256(code_verifier.encode()).digest()
).decode().rstrip('=')

# Step 1: Authorization request includes code_challenge
auth_url = f"""
https://auth.example.com/authorize?
  response_type=code&
  client_id={client_id}&
  redirect_uri={redirect_uri}&
  code_challenge={code_challenge}&
  code_challenge_method=S256
"""

# Step 2: Token request includes code_verifier
token_response = requests.post('https://auth.example.com/token', data={
    'grant_type': 'authorization_code',
    'code': auth_code,
    'redirect_uri': redirect_uri,
    'client_id': client_id,
    'code_verifier': code_verifier  # Server verifies this matches challenge
})
```

### Client Credentials Flow

For service-to-service (no user involved).

```python
response = requests.post('https://auth.example.com/token', data={
    'grant_type': 'client_credentials',
    'client_id': 'service-a',
    'client_secret': 'secret123',
    'scope': 'read:users'
})

access_token = response.json()['access_token']
```

---

## OpenID Connect (OIDC)

OIDC adds identity layer on top of OAuth 2.0.

### ID Token (JWT)

```json
{
  "iss": "https://auth.example.com",
  "sub": "user123",
  "aud": "my-app-client-id",
  "exp": 1699999999,
  "iat": 1699990000,
  "nonce": "abc123",
  "name": "John Doe",
  "email": "john@example.com",
  "email_verified": true
}
```

### OIDC vs OAuth 2.0

| Aspect | OAuth 2.0 | OIDC |
|--------|-----------|------|
| Purpose | Authorization | Authentication + Authorization |
| Token | Access Token | ID Token + Access Token |
| User Info | Separate API call | In ID Token |
| Standard Claims | No | Yes (name, email, etc.) |

---

## Password Security

### Password Hashing

**Never store plaintext passwords!**

```python
import bcrypt
import hashlib
import os

# ❌ BAD - Plain MD5/SHA
password_hash = hashlib.md5(password.encode()).hexdigest()

# ❌ BAD - No salt
password_hash = hashlib.sha256(password.encode()).hexdigest()

# ✅ GOOD - bcrypt with auto-salt
password_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12))

# ✅ GOOD - Argon2 (recommended)
from argon2 import PasswordHasher
ph = PasswordHasher()
password_hash = ph.hash(password)
```

### What is Salting?

Salt = random data added to password before hashing.

```
Without Salt:
  "password123" → SHA256 → "ef92b778..."
  "password123" → SHA256 → "ef92b778..."  (Same hash!)
  
Attacker can use rainbow tables to crack.

With Salt:
  "password123" + "x7k9m2" → SHA256 → "a1b2c3d4..."
  "password123" + "p3q8r1" → SHA256 → "e5f6g7h8..."  (Different!)
  
Each password has unique hash even if passwords are same.
```

### Salting Implementation

```python
import os
import hashlib

def hash_password(password):
    # Generate random salt (16 bytes)
    salt = os.urandom(16)
    
    # Hash password with salt
    password_hash = hashlib.pbkdf2_hmac(
        'sha256',
        password.encode(),
        salt,
        iterations=100000  # Slow = secure
    )
    
    # Store salt + hash together
    return salt + password_hash

def verify_password(password, stored):
    # Extract salt (first 16 bytes)
    salt = stored[:16]
    stored_hash = stored[16:]
    
    # Hash input with same salt
    password_hash = hashlib.pbkdf2_hmac(
        'sha256',
        password.encode(),
        salt,
        iterations=100000
    )
    
    # Compare (constant-time to prevent timing attacks)
    return hmac.compare_digest(password_hash, stored_hash)
```

### bcrypt (Recommended)

bcrypt includes salt automatically.

```python
import bcrypt

# Hash (salt is embedded in result)
hashed = bcrypt.hashpw(b"password123", bcrypt.gensalt(rounds=12))
# Result: $2b$12$K4x7v8Yx9Z2w3U4t5R6q7OeF8G9H0I1J2K3L4M5N6O7P8Q9R0S1T2

# Verify
if bcrypt.checkpw(b"password123", hashed):
    print("Password correct!")
```

### Argon2 (Most Secure)

Winner of Password Hashing Competition (2015).

```python
from argon2 import PasswordHasher

ph = PasswordHasher(
    time_cost=3,      # Iterations
    memory_cost=65536, # Memory in KB
    parallelism=4      # Threads
)

# Hash
hash = ph.hash("password123")

# Verify
try:
    ph.verify(hash, "password123")
    print("Password correct!")
except:
    print("Invalid password")
```

---

## Secrets Management

### What are Secrets?

- API keys
- Database passwords
- Encryption keys
- OAuth client secrets
- TLS certificates

### ❌ Bad Practices

```python
# Hardcoded in code
API_KEY = "sk-12345abcdef"

# In version control
# .env committed to git
DATABASE_URL=postgres://user:password@host/db

# Plain text config files
config.json: {"secret": "mysecret"}
```

### ✅ Good Practices

```python
# Environment variables (basic)
import os
API_KEY = os.environ.get('API_KEY')

# Secret manager (production)
from google.cloud import secretmanager

client = secretmanager.SecretManagerServiceClient()
secret = client.access_secret_version(name="projects/myproject/secrets/api-key/versions/latest")
API_KEY = secret.payload.data.decode('UTF-8')
```

### Secret Management Tools

| Tool | Provider | Features |
|------|----------|----------|
| **HashiCorp Vault** | Open source | Dynamic secrets, encryption |
| **AWS Secrets Manager** | AWS | Rotation, IAM integration |
| **Azure Key Vault** | Azure | HSM, certificates |
| **GCP Secret Manager** | GCP | IAM, versioning |
| **Kubernetes Secrets** | K8s | Base64 encoded (use with CSI) |

### Vault Example

```python
import hvac

# Connect to Vault
client = hvac.Client(url='https://vault.example.com')
client.token = os.environ['VAULT_TOKEN']

# Read secret
secret = client.secrets.kv.v2.read_secret_version(
    path='myapp/database'
)
db_password = secret['data']['data']['password']

# Dynamic secrets (auto-rotating)
creds = client.secrets.database.generate_credentials(
    name='my-role'
)
# Returns temporary username/password
```

---

## Token Security Best Practices

### JWT Best Practices

```python
import jwt
from datetime import datetime, timedelta

# ✅ Use RS256 (asymmetric) in production
# ❌ Avoid HS256 with weak secrets

# Short expiration
token = jwt.encode({
    'sub': user_id,
    'exp': datetime.utcnow() + timedelta(minutes=15),  # Short-lived
    'iat': datetime.utcnow(),
    'jti': str(uuid.uuid4())  # Unique token ID for revocation
}, private_key, algorithm='RS256')

# Store in httpOnly cookie (not localStorage)
response.set_cookie(
    'access_token',
    token,
    httponly=True,
    secure=True,
    samesite='Strict'
)
```

### Refresh Token Pattern

```python
# Access token: Short-lived (15 min)
# Refresh token: Long-lived (7 days), stored securely

def refresh_tokens(refresh_token):
    # Verify refresh token
    payload = verify_refresh_token(refresh_token)
    
    # Check if revoked
    if is_token_revoked(payload['jti']):
        raise InvalidTokenError()
    
    # Issue new tokens
    new_access = generate_access_token(payload['sub'])
    new_refresh = generate_refresh_token(payload['sub'])
    
    # Revoke old refresh token (rotation)
    revoke_token(payload['jti'])
    
    return new_access, new_refresh
```

---

## Interview Questions

**Q: Explain SSO and how it works.**
> SSO allows one login for multiple apps. User authenticates with Identity Provider (IdP), receives token/assertion, presents to Service Providers. Protocols: SAML (enterprise), OIDC (modern).

**Q: What is password salting and why is it important?**
> Salt = random data added before hashing. Prevents rainbow table attacks, ensures same passwords have different hashes. Salt stored with hash. Use bcrypt/Argon2 which handle salting automatically.

**Q: OAuth 2.0 vs OIDC?**
> OAuth 2.0: authorization (access to resources). OIDC: adds authentication layer (who is the user). OIDC returns ID Token with user info. Use OIDC for "Login with Google" type features.

**Q: Where should you store secrets in production?**
> Secret managers (Vault, AWS Secrets Manager), not in code or git. Use environment variables as minimum. Implement rotation, audit logging, least privilege access.

---

## Quick Reference

| Topic | Recommendation |
|-------|----------------|
| **Password Hash** | Argon2 or bcrypt |
| **JWT Signing** | RS256 (asymmetric) |
| **Token Storage** | httpOnly cookies |
| **Access Token TTL** | 15-60 minutes |
| **Secrets** | Vault/Cloud Secret Manager |
| **SSO Protocol** | OIDC (modern) |
