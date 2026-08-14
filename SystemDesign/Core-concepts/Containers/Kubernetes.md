# Kubernetes (K8s) - Core Concepts Deep Dive

> **Where this sits:** `RoadMaps/Kubernates.md` is a short notes file. `Scalability-Architecture/Containers_and_Orchestration.md` is a combined overview. **This file** is the interview-depth Kubernetes guide.  
> **Prereq:** [Docker.md](./Docker.md) — K8s schedules **containers**; Docker (or another runtime) builds/runs them.

## Table of Contents
1. [What Problem Kubernetes Solves](#1-what-problem-kubernetes-solves)
2. [Architecture (Control Plane + Workers)](#2-architecture-control-plane--workers)
3. [Core Objects](#3-core-objects)
4. [Pods Deep Dive](#4-pods-deep-dive)
5. [Deployments & ReplicaSets](#5-deployments--replicasets)
6. [Services & Networking](#6-services--networking)
7. [ConfigMap & Secrets](#7-configmap--secrets)
8. [Ingress](#8-ingress)
9. [Probes (Liveness / Readiness / Startup)](#9-probes-liveness--readiness--startup)
10. [Scaling](#10-scaling)
11. [Deployment Strategies](#11-deployment-strategies)
12. [Volumes & StatefulSets](#12-volumes--statefulsets)
13. [Namespaces, RBAC, Resource Limits](#13-namespaces-rbac-resource-limits)
14. [What Happens on `kubectl apply`](#14-what-happens-on-kubectl-apply)
15. [Interview Q&A](#15-interview-qa)
16. [Cheat Sheet](#16-cheat-sheet)

---

## 1. What Problem Kubernetes Solves

Docker runs containers on **one machine**. Production needs:

| Need | Without K8s | With K8s |
|------|-------------|----------|
| Run 50 replicas across 20 machines | Manual SSH / scripts | Scheduler places pods |
| Node dies | App down on that node | Reschedule pods elsewhere |
| Rolling update | Downtime risk | Controlled rollout + rollback |
| Service discovery | Hardcoded IPs | Stable Service DNS |
| Scale on CPU | Manual | HPA autoscales |
| Secrets / config | Baked into images | ConfigMaps / Secrets |

**Kubernetes = container orchestrator.** You declare **desired state**; the control plane makes **actual state** match it (reconciliation loop).

```
You:   "I want 3 replicas of api:v2, always"
K8s:   Creates / replaces pods until 3 healthy api:v2 exist
```

### What K8s is NOT

- Not a build system (CI builds images; K8s runs them)
- Not a replacement for app design (still need statelessness, health endpoints, etc.)
- Not only "Docker" — uses a **container runtime** (containerd, CRI-O; historically Docker)

---

## 2. Architecture (Control Plane + Workers)

```
┌─────────────────────────── CONTROL PLANE ───────────────────────────┐
│  API Server     ← single front door (authn/authz, validation)       │
│  etcd           ← cluster state (desired + actual)                  │
│  Scheduler      ← which node gets a new Pod?                        │
│  Controller Mgr ← Deployment/ReplicaSet/Node controllers, etc.      │
│  Cloud Ctrl (opt) ← cloud LBs, disks, etc.                          │
└─────────────────────────────────────────────────────────────────────┘
                │ kube-apiserver
                ▼
┌─────────────────────────── WORKER NODES ────────────────────────────┐
│  kubelet          ← talks to API; runs pods via runtime             │
│  kube-proxy       ← Service ClusterIP / iptables or IPVS rules      │
│  Container runtime (containerd)                                     │
│  Pods (your containers)                                             │
└─────────────────────────────────────────────────────────────────────┘
```

| Component | Job |
|-----------|-----|
| **API Server** | All reads/writes go through here |
| **etcd** | Highly available key-value store of cluster state |
| **Scheduler** | Filters/scores nodes; binds pod to a node |
| **Controller Manager** | Watches state; creates/deletes objects to match spec |
| **kubelet** | Node agent — start/stop containers, report status |
| **kube-proxy** | Implements Service networking on the node |

**HA interview point:** Control plane is HA via multiple API servers + etcd quorum. Apps are HA via replicas across nodes + Services.

---

## 3. Core Objects

```
Cluster
 └── Namespace
      ├── Deployment  → owns → ReplicaSet → owns → Pod(s)
      ├── Service     → selects Pods by labels
      ├── ConfigMap / Secret
      ├── Ingress
      ├── Job / CronJob
      └── StatefulSet / DaemonSet (special workloads)
```

| Object | One-liner |
|--------|-----------|
| **Pod** | Smallest deployable unit; 1+ containers sharing network/storage |
| **ReplicaSet** | Ensures N pod replicas (usually via Deployment) |
| **Deployment** | Declarative updates for Pods/ReplicaSets (rollout/rollback) |
| **Service** | Stable virtual IP / DNS to reach a set of Pods |
| **Ingress** | HTTP(S) routing from outside into Services |
| **ConfigMap** | Non-secret config |
| **Secret** | Sensitive config (base64 in etcd; encrypt at rest in prod) |
| **StatefulSet** | Sticky identity + stable storage (DBs, Kafka brokers) |
| **DaemonSet** | One pod per node (log agents, CNI, monitoring) |
| **Job / CronJob** | Run-to-completion / scheduled tasks |

**Labels + selectors** glue everything:

```yaml
# Pod
metadata:
  labels:
    app: api
    version: v2

# Service selects those pods
selector:
  app: api
```

---

## 4. Pods Deep Dive

**Pod = group of containers that must run together** (share localhost, volumes).

Usually **1 container per pod** for microservices. Sidecars exist for:
- Log shippers
- Proxies (service mesh Envoy)
- Auth/adapters

```
┌──────────── Pod ────────────┐
│  Container: api             │
│  Container: sidecar-proxy   │  ← optional
│  Shared: network namespace   │  (same IP, localhost)
│  Shared: volumes            │
└─────────────────────────────┘
```

### Why not schedule containers directly?

K8s unit of scheduling/networking is the **Pod**. Sidecars need shared localhost → same pod.

### Pods are mortal

Pods die (OOM, node failure, eviction). **Never** rely on a single pod IP as permanent. Put a **Service** in front.

---

## 5. Deployments & ReplicaSets

You almost always create a **Deployment**, not a bare Pod, for long-running apps.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api
spec:
  replicas: 3
  selector:
    matchLabels:
      app: api
  template:
    metadata:
      labels:
        app: api
    spec:
      containers:
        - name: api
          image: myreg/api:1.2.0
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: "100m"
              memory: "256Mi"
            limits:
              cpu: "500m"
              memory: "512Mi"
```

### Who does what?

```
Deployment (desired: image v2, replicas=3)
    │
    ▼ creates/manages
ReplicaSet (current generation)
    │
    ▼ creates
Pod, Pod, Pod
```

- **ReplicaSet:** keep N copies alive  
- **Deployment:** manage ReplicaSets for rollouts (new RS for new version)

```bash
kubectl rollout status deployment/api
kubectl rollout undo deployment/api
kubectl set image deployment/api api=myreg/api:1.3.0
```

---

## 6. Services & Networking

Pods have dynamic IPs. **Service** gives stable access.

| Service type | Use |
|--------------|-----|
| **ClusterIP** (default) | Internal only — other pods call `api.default.svc.cluster.local` |
| **NodePort** | Expose on each node's IP:high-port (dev / simple) |
| **LoadBalancer** | Cloud LB → Service (AWS NLB/ALB, GCP LB, …) |
| **ExternalName** | DNS CNAME to external host |

```
Client → Service (stable VIP / DNS)
              │
              ├── Pod A (10.0.1.7)
              ├── Pod B (10.0.1.9)
              └── Pod C (10.0.2.4)
         kube-proxy load-balances
```

```yaml
apiVersion: v1
kind: Service
metadata:
  name: api
spec:
  type: ClusterIP
  selector:
    app: api
  ports:
    - port: 80
      targetPort: 8080
```

**Interview line:**  
> "Service is a stable abstraction over changing pods. DNS name stays; pod IPs churn."

---

## 7. ConfigMap & Secrets

**Don't bake config/secrets into images.**

| Object | For |
|--------|-----|
| ConfigMap | Feature flags, non-secret URLs, log level |
| Secret | Passwords, API keys, TLS certs |

Mount as env vars or files:

```yaml
envFrom:
  - configMapRef:
      name: api-config
  - secretRef:
      name: api-secrets
```

**Caveats:**
- Secrets are base64-encoded by default — **not encryption**. Enable encryption at rest + RBAC + external secret managers (AWS SM, Vault) in real prod.
- Updating ConfigMap may need pod restart unless app watches files.

---

## 8. Ingress

Service `LoadBalancer` per app gets expensive. **Ingress** = HTTP router.

```
Internet
   ↓
Ingress Controller (nginx / ALB / Traefik)
   ↓ host/path rules
   ├── api.example.com  → Service api:80
   └── www.example.com  → Service web:80
```

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: main
spec:
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: api
                port:
                  number: 80
```

Need an **Ingress Controller** installed — Ingress object alone does nothing.

---

## 9. Probes (Liveness / Readiness / Startup)

| Probe | Question | If fail |
|-------|----------|---------|
| **Liveness** | Is process stuck/dead? | Restart container |
| **Readiness** | Ready for traffic? | Remove from Service endpoints |
| **Startup** | Slow boot still starting? | Disable liveness until success (avoid kill loops) |

```yaml
livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /ready
    port: 8080
  periodSeconds: 5
```

**Design tip:**  
- `/healthz` = process up (liveness)  
- `/ready` = can accept traffic (DB connected, cache warm) — **readiness**

Misusing liveness (hitting DB) can cause restart storms when DB blips.

---

## 10. Scaling

### Manual

```bash
kubectl scale deployment/api --replicas=10
```

### Horizontal Pod Autoscaler (HPA)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

Needs **resource requests** set — HPA uses them as baseline.

### Cluster Autoscaler

Adds/removes **nodes** when pods are Pending (no capacity) or nodes underused.

```
HPA        → more/fewer Pods
Cluster AS → more/fewer Nodes
```

---

## 11. Deployment Strategies

| Strategy | Behavior | Downtime | Use |
|----------|----------|----------|-----|
| **RollingUpdate** (default) | Gradual replace old→new | Usually none | Most APIs |
| **Recreate** | Kill all, then create new | Yes | Can't run 2 versions together |
| **Blue/Green** | Two full stacks; switch Service/Ingress | None (cutover) | Instant rollback |
| **Canary** | Send % traffic to new version | None | Risk reduction |

Deployment rolling controls:

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

---

## 12. Volumes & StatefulSets

### Ephemeral vs durable

| Need | Object |
|------|--------|
| Scratch space | `emptyDir` |
| Config as files | ConfigMap/Secret volume |
| Durable disk | **PersistentVolumeClaim** → PersistentVolume (cloud disk / NFS) |

### StatefulSet (when Deployment is wrong)

Use for **sticky identity + ordered pods + stable storage**:

- MySQL primary/replicas
- Kafka brokers
- Elasticsearch nodes

```
StatefulSet "kafka"
  kafka-0  → PVC data-kafka-0  → stable DNS kafka-0.kafka-svc
  kafka-1  → PVC data-kafka-1
  kafka-2  → PVC data-kafka-2
```

**Interview line:**  
> "Deployments for stateless replicas. StatefulSets when each pod needs stable name and its own disk."

---

## 13. Namespaces, RBAC, Resource Limits

### Namespaces

Virtual clusters in one physical cluster: `dev`, `staging`, `prod`, `team-a`.

```bash
kubectl get pods -n prod
```

### Resource requests & limits

| | Meaning |
|--|---------|
| **requests** | Scheduler guarantee — used for placement |
| **limits** | Hard cap — CPU throttled / memory OOMKilled |

Always set both for production workloads.

### RBAC

Who can do what:

```
User/SA → Role/ClusterRole → RoleBinding
```

Least privilege: CI deploy SA can update Deployments in `prod`, not delete namespaces.

---

## 14. What Happens on `kubectl apply`

```
kubectl apply -f deploy.yaml
        │
        ▼
1. kubectl → API Server (authn/authz)
2. API Server validates & writes desired state to etcd
3. Deployment controller sees change → creates new ReplicaSet (if image changed)
4. ReplicaSet controller creates Pod objects
5. Scheduler assigns each Pod to a Node
6. kubelet on that Node pulls image, starts containers
7. Readiness passes → Endpoints updated → Service sends traffic
8. Controllers continuously reconcile (self-heal if pod/node dies)
```

**Desired state loop** is the heart of Kubernetes.

---

## 15. Interview Q&A

### Q1: Pod vs Deployment?
Pod = running instance(s). Deployment = controller that maintains replicas and handles rollouts/rollbacks via ReplicaSets. Prefer Deployments for apps.

### Q2: What happens when a node dies?
Node controller marks node NotReady. Pods on that node are terminated/rescheduled (for Deployments). New pods start on healthy nodes. Service keeps VIP; endpoints update.

### Q3: ClusterIP vs NodePort vs LoadBalancer?
ClusterIP = internal. NodePort = expose on node ports. LoadBalancer = cloud provider provisions external LB.

### Q4: Liveness vs Readiness?
Liveness fail → restart container. Readiness fail → stop sending traffic (no restart). Use readiness for dependency warmup.

### Q5: How does K8s achieve HA?
Control plane: multi master + etcd quorum. Apps: replicas across nodes + Services + anti-affinity. Infra: multi-AZ nodes.

### Q6: ConfigMap vs Secret?
Both inject config. Secret for sensitive data + tighter RBAC/encryption. Neither replaces a real secret manager alone.

### Q7: StatefulSet vs Deployment?
Deployment = interchangeable stateless pods. StatefulSet = stable network ID + ordered deploy + per-pod PVC.

### Q8: What is Ingress?
HTTP(S) routing object. Needs an Ingress Controller. Centralizes TLS and host/path routing to Services.

### Q9: How does HPA work?
Watches metrics (CPU/custom). Adjusts Deployment replicas between min/max. Needs resource requests.

### Q10: Docker vs Kubernetes?
Docker packages/runs containers. Kubernetes orchestrates containers across a cluster (schedule, heal, scale, network, rollout).

### Q11: Why labels matter?
Services, Deployments, NetworkPolicies all select pods by labels. Labels are the glue of K8s networking and ownership.

### Q12: requests vs limits?
Requests = scheduling/capacity planning. Limits = runtime caps. Memory over limit → OOMKill.

---

## 16. Cheat Sheet

```
DESIRED STATE → API Server → etcd → Controllers reconcile forever

Pod         = smallest unit (1+ containers)
Deployment  = replicas + rollout for stateless apps
ReplicaSet  = keeps N pods (owned by Deployment)
Service     = stable DNS/VIP → pods by label
Ingress     = HTTP entry (needs controller)
ConfigMap   = config
Secret      = sensitive config
StatefulSet = sticky identity + disks
DaemonSet   = one per node
HPA         = scale pods
Cluster AS  = scale nodes

Probes:  liveness=restart | readiness=traffic | startup=slow boot
Apply:   declare YAML → controllers make world match

Stateless API  → Deployment + Service + HPA + Ingress
Database       → StatefulSet + PVC (or managed DB outside cluster)
```

### Essential kubectl

```bash
kubectl get pods,svc,deploy -A
kubectl describe pod <name>
kubectl logs <pod> -c <container> -f
kubectl exec -it <pod> -- sh
kubectl apply -f app.yaml
kubectl rollout status deploy/api
kubectl rollout undo deploy/api
kubectl scale deploy/api --replicas=5
kubectl top pods   # needs metrics-server
```

### Related files
- Docker: [Docker.md](./Docker.md)
- Overview: `../Scalability-Architecture/Containers_and_Orchestration.md`
- Deploy strategies: `../Scalability-Architecture/Deployment_Strategies.md`
- Short roadmap: `../../RoadMaps/Kubernates.md` (note: filename typo in repo)
