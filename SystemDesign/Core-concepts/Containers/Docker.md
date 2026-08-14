# Docker - Core Concepts Deep Dive

> **Where this sits:** RoadMaps has a short Docker cheat sheet. `Scalability-Architecture/Containers_and_Orchestration.md` is a combined overview. **This file** is the interview-depth Docker guide.

## Table of Contents
1. [What Problem Docker Solves](#1-what-problem-docker-solves)
2. [Container vs VM](#2-container-vs-vm)
3. [Core Building Blocks](#3-core-building-blocks)
4. [How Isolation Works (Linux)](#4-how-isolation-works-linux)
5. [Images, Layers & Dockerfile](#5-images-layers--dockerfile)
6. [Multi-Stage Builds & Image Optimization](#6-multi-stage-builds--image-optimization)
7. [Volumes & Persistence](#7-volumes--persistence)
8. [Networking](#8-networking)
9. [Docker Compose](#9-docker-compose)
10. [Security Basics](#10-security-basics)
11. [Docker vs Kubernetes](#11-docker-vs-kubernetes)
12. [Interview Q&A](#12-interview-qa)
13. [Cheat Sheet](#13-cheat-sheet)

---

## 1. What Problem Docker Solves

**Classic pain:** "Works on my machine."

| Environment | What breaks |
|-------------|-------------|
| Dev laptop | Java 17, Redis 7 |
| Staging | Java 11, Redis 6 |
| Prod | Different OS libs, missing package |

**Docker idea:** Package **app + runtime + libs + config** into one unit (image). Run that unit anywhere the Docker runtime exists.

```
Without Docker:
  Code → hope host has correct Java/Node/Python/libs → often breaks

With Docker:
  Code + deps → Image → Container runs same on laptop / CI / prod
```

### What Docker is NOT

- Not a VM replacement for every workload (stronger isolation still = VMs / Firecracker)
- Not an orchestrator (that's Kubernetes / Swarm / Nomad)
- Not magic security — containers share the host kernel

---

## 2. Container vs VM

```
VIRTUAL MACHINE                         CONTAINER
┌─────────────────────────┐             ┌─────────────────────────┐
│ App A   │ App B         │             │ App A │ App B │ App C   │
│ Libs    │ Libs          │             │ Libs  │ Libs  │ Libs    │
│ Guest OS│ Guest OS      │  (heavy)    └───────┴───────┴─────────┘
├─────────────────────────┤             │ Container Runtime       │
│ Hypervisor              │             │ Host OS (shared kernel) │
│ Host OS                 │             │ Hardware                │
│ Hardware                │             └─────────────────────────┘
└─────────────────────────┘
```

| Aspect | Container | VM |
|--------|-----------|-----|
| Startup | Seconds | Minutes |
| Size | MBs–hundreds of MBs | GBs |
| Isolation | Process + namespaces (shared kernel) | Hardware-level (own kernel) |
| Density | Hundreds per host | Tens per host |
| Portability | Excellent | Heavier to move |
| Use when | Microservices, CI, consistent deploys | Strong isolation, different kernels, legacy |

**Interview line:**  
> "Containers virtualize the OS; VMs virtualize hardware. Containers share the host kernel → faster and denser, weaker isolation than VMs."

---

## 3. Core Building Blocks

| Term | Meaning |
|------|---------|
| **Image** | Immutable blueprint (layers of filesystem + metadata + start command) |
| **Container** | Running (or stopped) instance of an image |
| **Dockerfile** | Recipe to build an image |
| **Registry** | Store for images (Docker Hub, ECR, GCR, Harbor) |
| **Volume** | Persistent storage outside container lifecycle |
| **Network** | How containers talk to each other / host |
| **Compose** | Multi-container app definition (YAML) |

### Mental model

```
Dockerfile  --build-->  Image  --run-->  Container
                              |
                         push/pull
                              ↓
                          Registry
```

**Image vs Container (must be crisp):**
- Image = class / template  
- Container = object / instance  

Many containers can run from one image.

---

## 4. How Isolation Works (Linux)

Docker (via containerd/runc) uses Linux kernel features:

| Feature | What it isolates |
|---------|------------------|
| **Namespaces** | PID, network, mount, UTS (hostname), IPC, user — each container sees its own "world" |
| **cgroups** | CPU, memory, I/O limits — prevent one container starving the host |
| **Union filesystems (overlay)** | Layered images (copy-on-write) |
| **seccomp / AppArmor / SELinux** | Syscall / MAC security policies |

```
Container A                    Container B
PID ns: sees only its procs    PID ns: different PIDs
Net ns: own eth0/IP            Net ns: own IP
Mount ns: own /                Mount ns: own /
         \                    /
          \                  /
           Shared Host Kernel
```

**Interview line:**  
> "Containers are not magic VMs — they are processes with namespaces and cgroups. They share the host kernel."

---

## 5. Images, Layers & Dockerfile

### Layers

Each Dockerfile instruction often creates a layer:

```
FROM alpine          → layer 1 (base)
RUN apk add curl     → layer 2
COPY app /app        → layer 3
CMD ["./app"]        → metadata (config)
```

**Why layers matter:**
- **Cache:** unchanged early layers reuse cache → faster builds
- **Share:** multiple images can share base layers on disk
- **Order:** put rarely changing steps first (`deps` before `COPY source`)

### Example Dockerfile (production-minded)

```dockerfile
# Base
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy only what's needed (not whole build context secrets)
COPY target/app.jar app.jar

# Non-root user (security)
USER 1000

EXPOSE 8080

# Prefer exec form — correct signal handling (SIGTERM for graceful shutdown)
CMD ["java", "-jar", "app.jar"]
```

### Exec form vs shell form

| Form | Example | Issue |
|------|---------|-------|
| Exec `CMD ["java","-jar","app.jar"]` | PID 1 = java | Gets SIGTERM → graceful stop |
| Shell `CMD java -jar app.jar` | PID 1 = `/bin/sh` | Java may not get signals → kill -9 on stop |

### Essential commands

```bash
docker build -t myapp:1.0 .
docker run -d -p 8080:8080 --name myapp myapp:1.0
docker ps
docker logs -f myapp
docker exec -it myapp sh
docker stop myapp && docker rm myapp
docker images
docker push registry/myapp:1.0
```

---

## 6. Multi-Stage Builds & Image Optimization

### Problem

Build tools (Maven, Node, compilers) bloat the final image and increase attack surface.

### Multi-stage build

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Stage 2: runtime only
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
USER 1000
CMD ["java", "-jar", "app.jar"]
```

Final image has **JRE + jar**, not Maven + source.

### Optimization checklist

| Technique | Why |
|-----------|-----|
| Small base (`alpine`, `distroless`) | Smaller attack surface + pull time |
| Multi-stage | Drop build toolchain |
| `.dockerignore` | Don't send `.git`, `node_modules`, secrets into build context |
| Layer order | Dependencies before source for cache hits |
| Pin versions | `node:20.11-alpine` not `node:latest` |
| One process per container | Easier scale/restart (12-factor) |

---

## 7. Volumes & Persistence

**Containers are ephemeral.** Delete container → writable layer gone.

| Type | Use |
|------|-----|
| **Named volume** | DB data, managed by Docker (`postgres_data`) |
| **Bind mount** | Dev: mount local source into container |
| **tmpfs** | Secrets/temp in memory only |

```bash
# Named volume (prod-ish)
docker run -v pgdata:/var/lib/postgresql/data postgres:16

# Bind mount (dev)
docker run -v $(pwd):/app -w /app node:20 npm start
```

**Interview line:**  
> "Use volumes for state that must survive container recreation. Don't store durable data only in the container writable layer."

---

## 8. Networking

### Common modes

| Mode | Behavior |
|------|----------|
| **bridge** (default) | Containers on private bridge; publish ports with `-p host:container` |
| **host** | Container shares host network stack (no isolation; lower latency) |
| **none** | No network |
| **overlay** | Multi-host (Swarm / some setups) |

### Container-to-container

Same custom bridge / Compose network → resolve by **service/container name** as DNS:

```
web → talks to → redis:6379  (hostname = service name)
```

```bash
docker network create mynet
docker run -d --network mynet --name redis redis:7
docker run -d --network mynet --name api -e REDIS_HOST=redis myapi:1.0
```

### Port mapping

```bash
docker run -p 8080:80 nginx
# Host:8080 → Container:80
```

---

## 9. Docker Compose

**Problem:** App needs API + DB + Redis + queue. Running 4 `docker run` commands is painful.

**Compose:** one YAML → start whole stack.

```yaml
services:
  api:
    build: .
    ports:
      - "8080:8080"
    environment:
      REDIS_URL: redis://redis:6379
      DB_URL: jdbc:postgresql://db:5432/app
    depends_on:
      - redis
      - db

  redis:
    image: redis:7-alpine

  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_PASSWORD: secret
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

```bash
docker compose up -d
docker compose logs -f api
docker compose down
```

**Interview line:**  
> "Compose is for multi-container local/dev (and simple single-host deploys). Kubernetes is for cluster orchestration at scale."

---

## 10. Security Basics

| Practice | Why |
|----------|-----|
| Non-root `USER` | Limit blast radius if RCE |
| Don't bake secrets into image | Use env / secret stores; images get pushed/copied |
| Scan images (Trivy, Snyk) | CVEs in base layers |
| Minimal base / distroless | Fewer packages = fewer CVEs |
| Read-only root FS when possible | Harder persistence for attacker |
| Pin digests for prod | Supply-chain integrity |
| Avoid `latest` in prod | Unreproducible deploys |

---

## 11. Docker vs Kubernetes

| | Docker | Kubernetes |
|--|--------|------------|
| Job | Build/run **containers** | Orchestrate containers **across machines** |
| Scale | One host (or Compose/Swarm) | Cluster of nodes |
| Self-heal | Restart policies (limited) | Reschedule pods, health probes, desired state |
| Service discovery | Compose DNS / links | Services, DNS, Ingress |
| Rolling updates | Manual / Compose | Deployments, rollouts, rollbacks |

```
Developer machine:  Dockerfile → Image → docker run / Compose
Production cluster: Image → Kubernetes Pods / Deployments / Services
```

Docker builds the box. Kubernetes decides **where** boxes run, **how many**, and **how traffic reaches them**.

→ See also: [Kubernetes.md](./Kubernetes.md)

---

## 12. Interview Q&A

### Q1: What is Docker and why use it?
Package app + dependencies into portable containers so the same artifact runs in every environment. Faster than VMs, consistent deploys, great for microservices/CI.

### Q2: Image vs container?
Image = immutable template. Container = running instance of that image.

### Q3: How does Docker isolate containers?
Linux namespaces (PID, net, mount, …) + cgroups (resource limits) + optional security profiles. Shared host kernel.

### Q4: How do you persist data?
Volumes (named/bind). Data lives outside container lifecycle.

### Q5: What is a multi-stage build?
Build in a fat image; copy only artifacts into a slim runtime image → smaller, safer production images.

### Q6: How do containers on the same host talk?
Put them on the same user-defined bridge network; use service/container DNS names.

### Q7: Difference between Docker Compose and Kubernetes?
Compose = multi-container on one host (dev/simple). K8s = production cluster orchestration (scheduling, heal, scale, rollout).

### Q8: Why prefer `CMD ["exec","form"]`?
Process becomes PID 1 and receives SIGTERM for graceful shutdown.

### Q9: How to optimize image size?
Alpine/distroless, multi-stage, `.dockerignore`, fewer layers of junk, don't copy build tools into final stage.

### Q10: Is a container as secure as a VM?
No. Shared kernel → weaker isolation. Harden with non-root, scanning, policies; use VMs/sandbox for hostile multi-tenant if needed.

---

## 13. Cheat Sheet

```
BUILD     docker build -t app:1.0 .
RUN       docker run -d -p 8080:8080 --name app app:1.0
LOGS      docker logs -f app
SHELL     docker exec -it app sh
STOP/RM   docker stop app && docker rm app
COMPOSE   docker compose up -d

IMAGE  = blueprint (layers)
CONTAINER = running instance
VOLUME = durable data
NETWORK = how containers reach each other

Isolation = namespaces + cgroups (shared kernel)
Prod tip  = multi-stage + non-root + no secrets in image
Compose   = local multi-service
K8s       = cluster orchestration
```

### Related files
- Overview: `../Scalability-Architecture/Containers_and_Orchestration.md`
- Short roadmap: `../../RoadMaps/Docker.md`
- Next: [Kubernetes.md](./Kubernetes.md)
