# Security Fundamentals in System Design

## Overview
Security is a critical aspect of system design that must be considered at every layer. This document covers authentication, authorization, encryption, tokenization, compliance, and other security fundamentals essential for building secure distributed systems.

## Authentication vs Authorization

### Authentication (AuthN)
**Definition**: The process of verifying the identity of a user, device, or system.

**Key Concepts**:
- **Identity Verification**: Confirming "who you are"
- **Credentials**: Username/password, certificates, biometrics
- **Multi-factor Authentication (MFA)**: Multiple verification methods
- **Single Sign-On (SSO)**: One login for multiple services

### Authorization (AuthZ)
**Definition**: The process of determining what an authenticated user is allowed to do.

**Key Concepts**:
- **Access Control**: Determining "what you can do"
- **Permissions**: Specific actions allowed
- **Roles**: Groups of permissions
- **Policies**: Rules governing access decisions

## Authentication Mechanisms

### 1. Password-Based Authentication
```
User → Username/Password → Server Verification → Session/Token
```

**Pros**:
- Simple to implement
- Familiar to users
- Low infrastructure requirements

**Cons**:
- Vulnerable to brute force attacks
- Password reuse issues
- Phishing susceptibility

**Best Practices**:
- Strong password policies
- Password hashing (bcrypt, Argon2)
- Account lockout mechanisms
- Rate limiting

### 2. Multi-Factor Authentication (MFA)
```
User → Primary Factor → Secondary Factor → Verification → Access
```

**Factors**:
- **Something you know**: Password, PIN
- **Something you have**: Phone, hardware token
- **Something you are**: Biometrics, fingerprint

**Implementation**:
- SMS/Email codes
- TOTP (Time-based One-Time Password)
- Hardware security keys (FIDO2/WebAuthn)
- Biometric authentication

### 3. Certificate-Based Authentication
```
Client Certificate → Server Verification → Mutual TLS → Access
```

**Use Cases**:
- Service-to-service communication
- High-security environments
- API authentication

**Benefits**:
- Strong cryptographic security
- Non-repudiation
- Mutual authentication

### 4. OAuth 2.0 and OpenID Connect
```
User → Authorization Server → Access Token → Resource Server
```

**OAuth 2.0 Flows**:
- **Authorization Code**: Web applications
- **Client Credentials**: Service-to-service
- **Resource Owner Password**: Legacy systems
- **Implicit**: Single-page applications (deprecated)

**OpenID Connect**:
- Identity layer on top of OAuth 2.0
- ID tokens with user information
- Standardized user info endpoint

## JSON Web Tokens (JWT)

### Structure
```
Header.Payload.Signature
```

**Header**:
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload**:
```json
{
  "sub": "1234567890",
  "name": "John Doe",
  "iat": 1516239022,
  "exp": 1516242622
}
```

**Signature**:
```
HMACSHA256(
  base64UrlEncode(header) + "." +
  base64UrlEncode(payload),
  secret
)
```

### JWT Best Practices
1. **Use appropriate algorithms**: RS256 for asymmetric, HS256 for symmetric
2. **Set expiration times**: Short-lived tokens (15-30 minutes)
3. **Implement refresh tokens**: For long-term access
4. **Validate thoroughly**: Signature, expiration, issuer, audience
5. **Store securely**: HttpOnly cookies or secure storage

### JWT vs Session Tokens

| Aspect | JWT | Session Tokens |
|--------|-----|----------------|
| **Storage** | Client-side | Server-side |
| **Scalability** | Stateless | Requires shared storage |
| **Security** | Self-contained | Server validation |
| **Revocation** | Difficult | Easy |
| **Size** | Larger | Smaller |
| **Performance** | No server lookup | Server lookup required |

## Encryption

### Symmetric Encryption
**Definition**: Same key used for encryption and decryption.

**Algorithms**:
- **AES (Advanced Encryption Standard)**: Industry standard
- **ChaCha20**: High performance, mobile-friendly
- **3DES**: Legacy, being phased out

**Use Cases**:
- Data at rest encryption
- Bulk data encryption
- Session encryption

**Key Management**:
- Key rotation policies
- Secure key storage (HSM, KMS)
- Key derivation functions

### Asymmetric Encryption
**Definition**: Different keys for encryption (public) and decryption (private).

**Algorithms**:
- **RSA**: Widely supported, slower
- **ECC (Elliptic Curve)**: Smaller keys, better performance
- **Ed25519**: Modern, high performance

**Use Cases**:
- Key exchange
- Digital signatures
- Certificate-based authentication

### Encryption in Transit
**TLS/SSL Implementation**:
```
Client Hello → Server Hello → Certificate Exchange → 
Key Exchange → Encrypted Communication
```

**Best Practices**:
- Use TLS 1.2 or higher
- Strong cipher suites
- Perfect Forward Secrecy (PFS)
- Certificate pinning
- HSTS (HTTP Strict Transport Security)

### Encryption at Rest
**Database Encryption**:
- **Transparent Data Encryption (TDE)**: Database-level
- **Column-level encryption**: Specific sensitive fields
- **Application-level encryption**: Before storing

**File System Encryption**:
- Full disk encryption
- File-level encryption
- Cloud storage encryption (S3, Azure Blob)

## Tokenization

### Definition
Replacing sensitive data with non-sensitive tokens that have no exploitable meaning.

### Tokenization vs Encryption

| Aspect | Tokenization | Encryption |
|--------|--------------|------------|
| **Reversibility** | Via token vault | Via decryption key |
| **Format** | Can preserve format | Usually changes format |
| **Performance** | Fast lookup | Cryptographic overhead |
| **Compliance** | Reduces scope | Data still sensitive |

### Implementation Patterns

#### 1. Vault-Based Tokenization
```
Sensitive Data → Token Vault → Token
Token → Token Vault → Sensitive Data
```

#### 2. Vaultless Tokenization
```
Sensitive Data + Key → Algorithm → Token
Token + Key → Algorithm → Sensitive Data
```

### Use Cases
- **Payment Card Industry (PCI)**: Credit card numbers
- **Healthcare**: Patient identifiers, medical records
- **Financial Services**: Account numbers, SSNs
- **General**: Any sensitive data requiring protection

## Authorization Models

### Role-Based Access Control (RBAC)
```
User → Role → Permissions → Resources
```

**Components**:
- **Users**: Individuals or services
- **Roles**: Job functions or responsibilities
- **Permissions**: Specific actions
- **Resources**: Protected objects

**Example**:
```
User: john.doe
Role: Editor
Permissions: read, write, delete
Resources: /articles/*
```

### Attribute-Based Access Control (ABAC)
```
Subject + Action + Resource + Environment → Policy Decision
```

**Attributes**:
- **Subject**: User attributes (department, clearance level)
- **Action**: Operation being performed
- **Resource**: Object being accessed
- **Environment**: Time, location, network

**Policy Example**:
```
ALLOW if (
  subject.department == "Finance" AND
  action == "read" AND
  resource.type == "financial_report" AND
  environment.time BETWEEN 9:00 AND 17:00
)
```

### Relationship-Based Access Control (ReBAC)
```
User → Relationship → Resource → Permission
```

**Use Cases**:
- Social networks
- Document sharing
- Organizational hierarchies

**Example**:
```
User "alice" can "edit" Document "doc1" 
because alice is "owner" of doc1
```

## Security Patterns

### 1. Defense in Depth
**Principle**: Multiple layers of security controls.

**Layers**:
- **Perimeter**: Firewalls, WAF
- **Network**: Network segmentation, VPN
- **Host**: Antivirus, host-based firewalls
- **Application**: Input validation, authentication
- **Data**: Encryption, access controls

### 2. Zero Trust Architecture
**Principle**: "Never trust, always verify"

**Components**:
- **Identity verification**: Every user and device
- **Least privilege access**: Minimum necessary permissions
- **Micro-segmentation**: Network isolation
- **Continuous monitoring**: Real-time threat detection

### 3. Principle of Least Privilege
**Implementation**:
- Default deny policies
- Just-in-time access
- Regular access reviews
- Privilege escalation controls

## Compliance and Standards

### Common Frameworks

#### 1. PCI DSS (Payment Card Industry Data Security Standard)
**Requirements**:
- Build and maintain secure networks
- Protect cardholder data
- Maintain vulnerability management program
- Implement strong access control measures
- Regularly monitor and test networks
- Maintain information security policy

#### 2. GDPR (General Data Protection Regulation)
**Key Principles**:
- Lawfulness, fairness, transparency
- Purpose limitation
- Data minimization
- Accuracy
- Storage limitation
- Integrity and confidentiality
- Accountability

**Technical Requirements**:
- Data protection by design and default
- Right to erasure (right to be forgotten)
- Data portability
- Breach notification (72 hours)

#### 3. SOC 2 (Service Organization Control 2)
**Trust Principles**:
- **Security**: Protection against unauthorized access
- **Availability**: System availability for operation
- **Processing Integrity**: Complete, valid, accurate processing
- **Confidentiality**: Protection of confidential information
- **Privacy**: Personal information handling

#### 4. HIPAA (Health Insurance Portability and Accountability Act)
**Safeguards**:
- **Administrative**: Security officer, training, access management
- **Physical**: Facility access, workstation use, device controls
- **Technical**: Access control, audit controls, integrity, transmission security

### Compliance Implementation
1. **Data Classification**: Identify sensitive data
2. **Risk Assessment**: Evaluate threats and vulnerabilities
3. **Control Implementation**: Technical and administrative controls
4. **Monitoring**: Continuous compliance monitoring
5. **Auditing**: Regular compliance audits
6. **Documentation**: Maintain compliance documentation

## Security Testing

### 1. Static Application Security Testing (SAST)
- **Purpose**: Analyze source code for vulnerabilities
- **Benefits**: Early detection, comprehensive coverage
- **Tools**: SonarQube, Checkmarx, Veracode

### 2. Dynamic Application Security Testing (DAST)
- **Purpose**: Test running applications
- **Benefits**: Real-world testing, runtime vulnerabilities
- **Tools**: OWASP ZAP, Burp Suite, Nessus

### 3. Interactive Application Security Testing (IAST)
- **Purpose**: Combine SAST and DAST approaches
- **Benefits**: Real-time feedback, accurate results
- **Tools**: Contrast Security, Seeker

### 4. Penetration Testing
- **Purpose**: Simulate real-world attacks
- **Types**: Black box, white box, gray box
- **Phases**: Reconnaissance, scanning, exploitation, reporting

## Common Security Vulnerabilities

### OWASP Top 10 (2021)
1. **Broken Access Control**
2. **Cryptographic Failures**
3. **Injection**
4. **Insecure Design**
5. **Security Misconfiguration**
6. **Vulnerable and Outdated Components**
7. **Identification and Authentication Failures**
8. **Software and Data Integrity Failures**
9. **Security Logging and Monitoring Failures**
10. **Server-Side Request Forgery (SSRF)**

### Mitigation Strategies
- **Input Validation**: Sanitize and validate all inputs
- **Output Encoding**: Prevent injection attacks
- **Parameterized Queries**: Prevent SQL injection
- **Content Security Policy**: Prevent XSS attacks
- **Security Headers**: HSTS, X-Frame-Options, etc.

## Security Monitoring and Incident Response

### Security Information and Event Management (SIEM)
**Capabilities**:
- Log aggregation and correlation
- Real-time monitoring
- Threat detection
- Compliance reporting

**Popular Solutions**:
- Splunk
- IBM QRadar
- Microsoft Sentinel
- Elastic Security

### Incident Response Process
1. **Preparation**: Plans, procedures, tools
2. **Identification**: Detect and analyze incidents
3. **Containment**: Limit damage and prevent spread
4. **Eradication**: Remove threat from environment
5. **Recovery**: Restore systems and services
6. **Lessons Learned**: Post-incident review and improvement

## Best Practices Summary

### 1. Authentication
- Implement MFA wherever possible
- Use strong password policies
- Consider passwordless authentication
- Implement account lockout and rate limiting

### 2. Authorization
- Follow principle of least privilege
- Implement proper session management
- Use appropriate authorization model (RBAC, ABAC)
- Regular access reviews and cleanup

### 3. Encryption
- Encrypt data in transit and at rest
- Use strong encryption algorithms
- Implement proper key management
- Regular key rotation

### 4. Compliance
- Understand applicable regulations
- Implement controls by design
- Regular compliance assessments
- Maintain proper documentation

### 5. Monitoring
- Implement comprehensive logging
- Real-time threat detection
- Regular security assessments
- Incident response procedures

## Conclusion

Security in system design requires a comprehensive approach covering authentication, authorization, encryption, compliance, and monitoring. Key principles include:

- **Defense in depth**: Multiple security layers
- **Zero trust**: Verify everything, trust nothing
- **Least privilege**: Minimum necessary access
- **Security by design**: Built-in from the start
- **Continuous monitoring**: Ongoing threat detection

Success factors:
- Clear security requirements
- Proper threat modeling
- Regular security assessments
- Incident response capabilities
- Compliance with regulations
- Security awareness and training
