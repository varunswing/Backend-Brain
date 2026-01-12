# Security Fundamentals

## Overview
Security is a critical aspect of system design. This document covers authentication, authorization, encryption, and security best practices for building secure systems.

## Authentication vs Authorization

| Aspect | Authentication | Authorization |
|--------|---------------|---------------|
| **Definition** | Verifying identity (Who are you?) | Verifying permissions (What can you do?) |
| **When** | First step | After authentication |
| **Example** | Login with username/password | Checking if user can delete a record |

## Authentication Mechanisms

### 1. Password-Based Authentication
Basic username + password verification.

**Best Practices**:
- Hash passwords (bcrypt, Argon2)
- Enforce strong password policies
- Implement rate limiting
- Use HTTPS only

### 2. Multi-Factor Authentication (MFA)
Something you know + something you have + something you are.

**Factors**:
- **Knowledge**: Password, PIN
- **Possession**: Phone, hardware token
- **Inherence**: Fingerprint, face recognition

### 3. OAuth 2.0
Industry standard for authorization delegation.

**Flows**:
- **Authorization Code**: Web apps (most secure)
- **Implicit**: Single-page apps (deprecated)
- **Client Credentials**: Service-to-service
- **Password Grant**: Trusted apps only

### 4. OpenID Connect (OIDC)
Identity layer on top of OAuth 2.0.

Adds:
- ID Token (JWT)
- UserInfo endpoint
- Standard claims

### 5. JSON Web Tokens (JWT)

```json
{
  "header": {
    "alg": "RS256",
    "typ": "JWT"
  },
  "payload": {
    "sub": "user123",
    "name": "John Doe",
    "iat": 1516239022,
    "exp": 1516242622
  },
  "signature": "..."
}
```

**Best Practices**:
- Use short expiration times
- Store in httpOnly cookies
- Implement refresh tokens
- Use RS256 over HS256 for production

## Authorization Models

### 1. Role-Based Access Control (RBAC)

```
User → Role → Permissions

Example:
Admin Role → [create, read, update, delete]
Editor Role → [create, read, update]
Viewer Role → [read]
```

### 2. Attribute-Based Access Control (ABAC)
Access based on attributes of user, resource, and environment.

```
Policy: Allow if user.department == resource.department 
        AND time.hour >= 9 AND time.hour <= 17
```

### 3. Relationship-Based Access Control (ReBAC)
Access based on relationships between entities.

```
User can edit Document if User is Owner of Document
User can view Document if User is in Team that owns Document
```

## Encryption

### Symmetric Encryption
Same key for encryption and decryption.

- **AES-256**: Standard for data at rest
- **ChaCha20**: Mobile-friendly

### Asymmetric Encryption
Public key encrypts, private key decrypts.

- **RSA**: Widely used
- **ECC**: More efficient

### Encryption at Transit
Protect data in motion.

- **TLS 1.3**: Latest standard
- **mTLS**: Mutual TLS for service-to-service

### Encryption at Rest
Protect stored data.

- Database encryption
- File system encryption
- Backup encryption

## Security Patterns

### 1. Defense in Depth
Multiple layers of security controls.

```
Internet → WAF → Load Balancer → Application → Database
           ↓         ↓              ↓            ↓
        Layer 1   Layer 2       Layer 3      Layer 4
```

### 2. Zero Trust
Never trust, always verify.

Principles:
- Verify explicitly
- Use least privilege access
- Assume breach

### 3. Least Privilege
Grant only minimum required permissions.

## OWASP Top 10 (2021)

1. **Broken Access Control** - Enforce authorization
2. **Cryptographic Failures** - Use proper encryption
3. **Injection** - Validate and sanitize inputs
4. **Insecure Design** - Follow secure design patterns
5. **Security Misconfiguration** - Harden systems
6. **Vulnerable Components** - Keep dependencies updated
7. **Auth Failures** - Implement strong authentication
8. **Data Integrity Failures** - Verify data integrity
9. **Logging Failures** - Comprehensive security logging
10. **SSRF** - Validate server-side requests

## Security Testing

### Static Application Security Testing (SAST)
Analyze source code for vulnerabilities.

Tools: SonarQube, Checkmarx, Fortify

### Dynamic Application Security Testing (DAST)
Test running application for vulnerabilities.

Tools: OWASP ZAP, Burp Suite

### Penetration Testing
Simulated attacks by security experts.

### Dependency Scanning
Check for vulnerable dependencies.

Tools: Snyk, Dependabot, npm audit

## Compliance Frameworks

| Framework | Focus |
|-----------|-------|
| **PCI DSS** | Payment card data |
| **GDPR** | EU data privacy |
| **SOC 2** | Service organization controls |
| **HIPAA** | Healthcare data |
| **ISO 27001** | Information security management |

## Best Practices

### Application Security
1. Validate all inputs
2. Encode outputs
3. Use parameterized queries
4. Implement proper error handling
5. Keep dependencies updated

### API Security
1. Use API keys or OAuth
2. Rate limiting
3. Input validation
4. HTTPS only
5. Audit logging

### Data Security
1. Encrypt sensitive data
2. Implement data classification
3. Regular backups
4. Access controls
5. Data retention policies

### Infrastructure Security
1. Network segmentation
2. Firewall rules
3. Security patches
4. Monitoring and alerting
5. Incident response plan

## Security Monitoring

### Key Metrics
- Failed authentication attempts
- Unusual access patterns
- Data exfiltration indicators
- Privilege escalation attempts

### SIEM (Security Information and Event Management)
Centralized security event collection and analysis.

Tools: Splunk, Elastic SIEM, Sentinel

## Conclusion

**Key Principles**:
- Defense in depth
- Least privilege
- Zero trust
- Secure by default
- Regular auditing

**Remember**: Security is not a one-time effort but an ongoing process. Regular assessments, updates, and training are essential.
