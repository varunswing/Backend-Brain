# Deployment Strategies

## Overview

Deployment strategies determine how new versions of applications are released to production with minimal downtime and risk.

---

## Deployment Strategies Comparison

| Strategy | Downtime | Rollback | Risk | Complexity |
|----------|----------|----------|------|------------|
| **Recreate** | Yes | Slow | High | Low |
| **Rolling** | No | Medium | Medium | Low |
| **Blue-Green** | No | Fast | Low | Medium |
| **Canary** | No | Fast | Very Low | High |
| **A/B Testing** | No | Fast | Very Low | High |
| **Shadow** | No | N/A | Very Low | High |

---

## 1. Recreate Deployment

Stop old version completely, then start new version.

```
Before:  [V1] [V1] [V1]
During:  [  ] [  ] [  ]  ← Downtime!
After:   [V2] [V2] [V2]
```

**Pros:**
- Simple to implement
- Clean state

**Cons:**
- Downtime during deployment
- No gradual rollout

**Use When:**
- Development/staging environments
- Downtime is acceptable
- Major version changes requiring clean slate

---

## 2. Rolling Deployment

Gradually replace old instances with new ones.

```
Step 1:  [V1] [V1] [V1] → [V2] [V1] [V1]
Step 2:  [V2] [V1] [V1] → [V2] [V2] [V1]
Step 3:  [V2] [V2] [V1] → [V2] [V2] [V2]
```

### Kubernetes Rolling Update

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1        # Max pods above desired
      maxUnavailable: 0  # Keep all pods available
  template:
    spec:
      containers:
      - name: myapp
        image: myapp:v2
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
```

**Pros:**
- Zero downtime
- Gradual rollout
- Built into most platforms

**Cons:**
- Both versions run simultaneously
- Slower rollback
- Database migrations tricky

**Use When:**
- Standard deployments
- Backward-compatible changes
- Stateless applications

---

## 3. Blue-Green Deployment

Maintain two identical environments, switch traffic instantly.

```
┌─────────────────────────────────────────────────┐
│                 Load Balancer                   │
└─────────────────────┬───────────────────────────┘
                      │
         ┌────────────┴────────────┐
         │                         │
         ▼                         ▼
┌─────────────────┐      ┌─────────────────┐
│   Blue (V1)     │      │   Green (V2)    │
│   [Active]      │      │   [Standby]     │
│   ┌───┐ ┌───┐   │      │   ┌───┐ ┌───┐   │
│   │V1 │ │V1 │   │      │   │V2 │ │V2 │   │
│   └───┘ └───┘   │      │   └───┘ └───┘   │
└─────────────────┘      └─────────────────┘

After switch:
┌─────────────────┐      ┌─────────────────┐
│   Blue (V1)     │      │   Green (V2)    │
│   [Standby]     │      │   [Active]      │
└─────────────────┘      └─────────────────┘
```

### Implementation

```yaml
# Blue environment
apiVersion: v1
kind: Service
metadata:
  name: myapp
spec:
  selector:
    app: myapp
    version: blue  # Switch to 'green' for cutover
  ports:
  - port: 80
    targetPort: 8080

---
# Blue deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp-blue
spec:
  replicas: 3
  selector:
    matchLabels:
      app: myapp
      version: blue
  template:
    metadata:
      labels:
        app: myapp
        version: blue
    spec:
      containers:
      - name: myapp
        image: myapp:v1

---
# Green deployment (new version)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp-green
spec:
  replicas: 3
  selector:
    matchLabels:
      app: myapp
      version: green
  template:
    metadata:
      labels:
        app: myapp
        version: green
    spec:
      containers:
      - name: myapp
        image: myapp:v2
```

**Switch Traffic:**
```bash
# Test green environment
curl http://myapp-green-internal/health

# Switch traffic (update service selector)
kubectl patch service myapp -p '{"spec":{"selector":{"version":"green"}}}'

# Rollback if needed
kubectl patch service myapp -p '{"spec":{"selector":{"version":"blue"}}}'
```

**Pros:**
- Instant rollback
- Full testing before switch
- Zero downtime

**Cons:**
- Double infrastructure cost
- Database migrations complex
- Session handling needed

**Use When:**
- Critical applications
- Need instant rollback
- Thorough pre-production testing

---

## 4. Canary Deployment

Route small percentage of traffic to new version, gradually increase.

```
Step 1:  90% → [V1]    10% → [V2]  (Test with real traffic)
Step 2:  75% → [V1]    25% → [V2]  (Monitor metrics)
Step 3:  50% → [V1]    50% → [V2]  (Compare performance)
Step 4:  0%  → [V1]   100% → [V2]  (Full rollout)
```

### Kubernetes with Istio

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: myapp
spec:
  hosts:
  - myapp
  http:
  - route:
    - destination:
        host: myapp
        subset: v1
      weight: 90
    - destination:
        host: myapp
        subset: v2
      weight: 10  # Canary gets 10%

---
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: myapp
spec:
  host: myapp
  subsets:
  - name: v1
    labels:
      version: v1
  - name: v2
    labels:
      version: v2
```

### Canary with Nginx

```nginx
upstream myapp {
    server v1.myapp.local weight=9;
    server v2.myapp.local weight=1;  # 10% canary
}
```

**Automated Canary Analysis:**
```python
def canary_analysis(v1_metrics, v2_metrics):
    # Compare error rates
    if v2_metrics['error_rate'] > v1_metrics['error_rate'] * 1.1:
        return "ROLLBACK"
    
    # Compare latency
    if v2_metrics['p99_latency'] > v1_metrics['p99_latency'] * 1.2:
        return "ROLLBACK"
    
    return "CONTINUE"
```

**Pros:**
- Minimal blast radius
- Real production testing
- Data-driven decisions

**Cons:**
- Complex traffic management
- Monitoring overhead
- Longer deployment time

**Use When:**
- High-risk changes
- Need production validation
- Have good monitoring

---

## 5. A/B Testing Deployment

Route specific users to different versions based on criteria.

```
User criteria:
- User ID hash
- Geographic location
- User attributes (premium, beta)
- Random percentage

┌─────────────────┐
│   User Request  │
└────────┬────────┘
         │
    ┌────▼────┐
    │ Router  │──── User in Group A? ────→ Version A
    └────┬────┘
         │
         └─── User in Group B? ────→ Version B
```

### Implementation

```python
def route_request(user_id, request):
    # Consistent hashing for user assignment
    group = hash(user_id) % 100
    
    if group < 10:  # 10% to new version
        return route_to_v2(request)
    else:
        return route_to_v1(request)

# Or based on user attributes
def route_by_attribute(user, request):
    if user.is_beta_tester:
        return route_to_v2(request)
    if user.country in ['US', 'CA']:
        return route_to_v2(request)
    return route_to_v1(request)
```

**Pros:**
- Feature experimentation
- User-specific targeting
- Measurable outcomes

**Cons:**
- Complex routing logic
- Analytics overhead
- Potential inconsistent experience

---

## 6. Shadow Deployment

New version receives copy of production traffic but responses are discarded.

```
┌─────────────────┐
│  User Request   │
└────────┬────────┘
         │
         ├────────────────┐
         │                │ (duplicate)
         ▼                ▼
┌─────────────┐    ┌─────────────┐
│ Production  │    │   Shadow    │
│    (V1)     │    │    (V2)     │
└──────┬──────┘    └──────┬──────┘
       │                  │
       ▼                  ▼ (discard)
   Response            Compare
   to User             Metrics
```

**Pros:**
- Zero risk to users
- Real traffic testing
- Performance comparison

**Cons:**
- Double resource usage
- No real user feedback
- Complex setup

**Use When:**
- Major refactoring
- Performance testing
- Pre-production validation

---

## Database Migration Strategies

### Expand-Contract Pattern

```
Phase 1: Expand (Add new schema)
┌─────────────────────────────────┐
│ users                           │
│ - id                            │
│ - full_name (existing)          │
│ - first_name (new, nullable)    │
│ - last_name (new, nullable)     │
└─────────────────────────────────┘

Phase 2: Migrate (Backfill data)
- Copy data from full_name to first_name/last_name
- New code writes to both

Phase 3: Contract (Remove old schema)
- Stop writing to full_name
- Drop full_name column
```

### Parallel Run

```
1. Deploy new code that writes to BOTH old and new schema
2. Migrate existing data
3. Verify data consistency
4. Switch reads to new schema
5. Remove old schema writes
```

---

## Best Practices

### Pre-Deployment
- [ ] Run automated tests
- [ ] Database migration tested
- [ ] Rollback plan documented
- [ ] Monitoring alerts configured
- [ ] Feature flags ready

### During Deployment
- [ ] Monitor error rates
- [ ] Watch latency metrics
- [ ] Check resource usage
- [ ] Verify health checks pass

### Post-Deployment
- [ ] Verify functionality
- [ ] Check business metrics
- [ ] Update documentation
- [ ] Clean up old resources

---

## Interview Questions

**Q: Explain Blue-Green deployment.**
> Two identical environments (Blue/Green). Deploy new version to inactive environment, test it, then switch traffic instantly. Old environment becomes backup for instant rollback.

**Q: Blue-Green vs Canary - when to use which?**
> Blue-Green: need instant rollback, thorough testing before release. Canary: need gradual rollout, real production validation, data-driven decisions. Canary is lower risk but slower.

**Q: How do you handle database changes in Blue-Green?**
> Use expand-contract pattern. 1) Add new columns (nullable), 2) Deploy app writing to both, 3) Migrate data, 4) Switch traffic, 5) Remove old columns. Both versions must work with schema during transition.

---

## Quick Reference

| Strategy | Best For | Rollback |
|----------|----------|----------|
| **Recreate** | Dev/test | Redeploy |
| **Rolling** | Standard deploys | Redeploy |
| **Blue-Green** | Critical systems | Instant switch |
| **Canary** | High-risk changes | Stop canary |
| **A/B** | Feature experiments | Route change |
| **Shadow** | Pre-validation | N/A |
