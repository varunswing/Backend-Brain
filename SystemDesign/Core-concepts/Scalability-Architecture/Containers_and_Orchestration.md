# Containers and Orchestration

## Containers

### What is a Container?

A lightweight, standalone package that includes everything needed to run an application: code, runtime, libraries, and settings.

```
┌────────────────────────────────────────┐
│             Virtual Machine            │
│  ┌──────────┐ ┌──────────┐            │
│  │   App    │ │   App    │            │
│  │ + Libs   │ │ + Libs   │            │
│  │ + Guest  │ │ + Guest  │            │
│  │   OS     │ │   OS     │ (GBs each) │
│  └──────────┘ └──────────┘            │
│         Hypervisor                     │
│         Host OS                        │
│         Hardware                       │
└────────────────────────────────────────┘

┌────────────────────────────────────────┐
│              Containers                │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐  │
│  │   App   │ │   App   │ │   App   │  │
│  │ + Libs  │ │ + Libs  │ │ + Libs  │  │
│  └─────────┘ └─────────┘ └─────────┘  │
│        Container Runtime (Docker)      │
│        Host OS                 (MBs)   │
│        Hardware                        │
└────────────────────────────────────────┘
```

### Container vs VM

| Aspect | Container | VM |
|--------|-----------|-----|
| Startup | Seconds | Minutes |
| Size | MBs | GBs |
| Overhead | Low | High |
| Isolation | Process-level | Hardware-level |
| Portability | Very portable | Less portable |
| Density | 100s per host | 10s per host |

---

## Docker Basics

### Dockerfile

```dockerfile
# Base image
FROM node:18-alpine

# Working directory
WORKDIR /app

# Copy dependency files
COPY package*.json ./

# Install dependencies
RUN npm ci --production

# Copy application code
COPY . .

# Expose port
EXPOSE 3000

# Run command
CMD ["node", "server.js"]
```

### Docker Commands

```bash
# Build image
docker build -t myapp:v1 .

# Run container
docker run -d -p 3000:3000 --name myapp myapp:v1

# List containers
docker ps

# View logs
docker logs myapp

# Execute command in container
docker exec -it myapp /bin/sh

# Stop and remove
docker stop myapp
docker rm myapp

# Push to registry
docker push registry.example.com/myapp:v1
```

### Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  web:
    build: ./web
    ports:
      - "3000:3000"
    environment:
      - DATABASE_URL=postgres://db:5432/myapp
    depends_on:
      - db
  
  db:
    image: postgres:15
    volumes:
      - postgres_data:/var/lib/postgresql/data
    environment:
      - POSTGRES_PASSWORD=secret

volumes:
  postgres_data:
```

```bash
docker-compose up -d
docker-compose down
```

---

## Kubernetes (K8s)

### What is Kubernetes?

Container orchestration platform that automates deployment, scaling, and management of containerized applications.

### K8s Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Control Plane                           │
│  ┌──────────────┐  ┌──────────┐  ┌────────────────────┐    │
│  │  API Server  │  │ Scheduler│  │ Controller Manager │    │
│  └──────────────┘  └──────────┘  └────────────────────┘    │
│                        │                                    │
│                   ┌────┴────┐                              │
│                   │  etcd   │ (State store)                │
│                   └─────────┘                              │
└─────────────────────────────────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│   Worker Node │  │   Worker Node │  │   Worker Node │
│ ┌───────────┐ │  │ ┌───────────┐ │  │ ┌───────────┐ │
│ │  kubelet  │ │  │ │  kubelet  │ │  │ │  kubelet  │ │
│ └───────────┘ │  │ └───────────┘ │  │ └───────────┘ │
│ ┌───────────┐ │  │ ┌───────────┐ │  │ ┌───────────┐ │
│ │kube-proxy │ │  │ │kube-proxy │ │  │ │kube-proxy │ │
│ └───────────┘ │  │ └───────────┘ │  │ └───────────┘ │
│   [Pods...]   │  │   [Pods...]   │  │   [Pods...]   │
└───────────────┘  └───────────────┘  └───────────────┘
```

### Core Concepts

#### Pod
Smallest deployable unit - one or more containers.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: myapp
spec:
  containers:
  - name: myapp
    image: myapp:v1
    ports:
    - containerPort: 3000
```

#### Deployment
Manages ReplicaSets and provides declarative updates.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp
spec:
  replicas: 3
  selector:
    matchLabels:
      app: myapp
  template:
    metadata:
      labels:
        app: myapp
    spec:
      containers:
      - name: myapp
        image: myapp:v1
        ports:
        - containerPort: 3000
        resources:
          requests:
            memory: "128Mi"
            cpu: "100m"
          limits:
            memory: "256Mi"
            cpu: "500m"
```

#### Service
Stable network endpoint for pods.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: myapp-service
spec:
  selector:
    app: myapp
  ports:
  - port: 80
    targetPort: 3000
  type: ClusterIP  # or LoadBalancer, NodePort
```

#### ConfigMap & Secret

```yaml
# ConfigMap for non-sensitive config
apiVersion: v1
kind: ConfigMap
metadata:
  name: myapp-config
data:
  LOG_LEVEL: "debug"
  API_URL: "https://api.example.com"

---
# Secret for sensitive data
apiVersion: v1
kind: Secret
metadata:
  name: myapp-secrets
type: Opaque
data:
  DB_PASSWORD: cGFzc3dvcmQxMjM=  # base64 encoded
```

#### Ingress
HTTP routing to services.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: myapp-ingress
spec:
  rules:
  - host: myapp.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: myapp-service
            port:
              number: 80
```

---

## Kubernetes Operations

### Scaling

```bash
# Manual scaling
kubectl scale deployment myapp --replicas=5

# Horizontal Pod Autoscaler
kubectl autoscale deployment myapp --min=2 --max=10 --cpu-percent=80
```

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: myapp-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: myapp
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 80
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/myapp myapp=myapp:v2

# Check rollout status
kubectl rollout status deployment/myapp

# Rollback
kubectl rollout undo deployment/myapp
```

### Health Checks

```yaml
spec:
  containers:
  - name: myapp
    livenessProbe:
      httpGet:
        path: /health
        port: 3000
      initialDelaySeconds: 10
      periodSeconds: 5
    readinessProbe:
      httpGet:
        path: /ready
        port: 3000
      initialDelaySeconds: 5
      periodSeconds: 3
```

---

## Common kubectl Commands

```bash
# Get resources
kubectl get pods
kubectl get deployments
kubectl get services
kubectl get all

# Describe resource
kubectl describe pod myapp-xyz

# Logs
kubectl logs myapp-xyz
kubectl logs -f myapp-xyz  # Follow

# Execute in pod
kubectl exec -it myapp-xyz -- /bin/sh

# Apply configuration
kubectl apply -f deployment.yaml

# Delete
kubectl delete -f deployment.yaml
kubectl delete pod myapp-xyz

# Port forward
kubectl port-forward pod/myapp-xyz 8080:3000

# Context management
kubectl config get-contexts
kubectl config use-context production
```

---

## Service Mesh (Istio)

### What is a Service Mesh?

Infrastructure layer for service-to-service communication with features like:
- mTLS (mutual TLS)
- Traffic management
- Observability
- Policy enforcement

```
┌─────────────────────────────────────────────────────────────┐
│                        Service Mesh                          │
│                                                              │
│  ┌─────────────────┐         ┌─────────────────┐           │
│  │    Service A    │         │    Service B    │           │
│  │  ┌───────────┐  │         │  ┌───────────┐  │           │
│  │  │    App    │  │  mTLS   │  │    App    │  │           │
│  │  └───────────┘  │◄───────►│  └───────────┘  │           │
│  │  ┌───────────┐  │         │  ┌───────────┐  │           │
│  │  │  Sidecar  │  │         │  │  Sidecar  │  │           │
│  │  │  (Envoy)  │  │         │  │  (Envoy)  │  │           │
│  │  └───────────┘  │         │  └───────────┘  │           │
│  └─────────────────┘         └─────────────────┘           │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Control Plane (Istiod)                  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## Best Practices

### Docker
1. Use specific image tags (not `latest`)
2. Multi-stage builds for smaller images
3. Run as non-root user
4. Scan images for vulnerabilities
5. Keep images minimal (alpine base)

### Kubernetes
1. Set resource requests and limits
2. Use liveness and readiness probes
3. Don't store secrets in images
4. Use namespaces for isolation
5. Implement network policies
6. Use RBAC for access control

---

## Interview Questions

**Q: Container vs VM?**
> Containers share host OS kernel, lightweight (MBs vs GBs), start in seconds. VMs have full OS, stronger isolation, heavier. Containers for microservices, VMs for legacy or strict isolation needs.

**Q: What is a Pod in Kubernetes?**
> Smallest deployable unit, one or more containers that share network namespace and storage. Containers in pod can communicate via localhost. Usually one main container per pod.

**Q: How does Kubernetes handle scaling?**
> Horizontal Pod Autoscaler (HPA) scales based on metrics (CPU, memory, custom). Cluster Autoscaler adds/removes nodes. Vertical Pod Autoscaler adjusts resource requests.

**Q: What is a Service in Kubernetes?**
> Stable network endpoint that routes traffic to pods. Types: ClusterIP (internal), NodePort (external via node), LoadBalancer (cloud LB). Uses labels to select pods.

---

## Quick Reference

| K8s Resource | Purpose |
|--------------|---------|
| Pod | Run containers |
| Deployment | Manage pods with updates |
| Service | Network endpoint |
| ConfigMap | Non-sensitive config |
| Secret | Sensitive config |
| Ingress | HTTP routing |
| HPA | Auto-scaling |
