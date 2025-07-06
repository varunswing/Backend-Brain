To **put a debugger or debug a file inside a Docker container**, follow these steps depending on your programming language/environment:

---

## ✅ **Generic Approach:**

### 🔹 1. **Exec into the running container**

```bash
docker exec -it <container_name> /bin/sh
# or /bin/bash if available
```

---

### 🔹 2. **Edit the file and insert debugger**

Use `vi`, `nano`, or `vim` (if installed):

```bash
vi /app/src/my_file.py
# Add breakpoint like: import pdb; pdb.set_trace()
```

If editor is missing:

```bash
apk add vim      # Alpine-based containers
apt-get update && apt-get install vim  # Debian/Ubuntu
```

---

### 🔹 3. **Restart the script with interpreter manually**

Example for Python:

```bash
python /app/src/my_file.py
```

---

## 🧑‍💻 **Language-Specific Tips:**

### 🐍 **Python:**

```python
import pdb; pdb.set_trace()
```

### 🟦 **Node.js:**

```js
debugger;
```

Run with:

```bash
node inspect file.js
```

### ☕ **Java:**

Start your container with debug port open:

```bash
JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
```

Expose port:

```bash
docker run -p 5005:5005 ...
```

Connect IDE (e.g., IntelliJ or Eclipse) to `localhost:5005`.

---

## 🧠 **Pro Tip:**

If you frequently debug, create a **`debug` Dockerfile** that:

* Installs debugging tools
* Exposes debug ports
* Mounts code as volume for live editing

---

Let me know your tech stack (Python, Java, Node, etc.), and I can give you exact steps and Dockerfile config.


If a **Docker container has a sanity lock** (i.e., a lock file preventing a process from restarting or running), you can remove it using the following approach:

---

## 🔍 Step-by-Step to Remove a Sanity Lock in Container

### ✅ 1. **Find the Container**

```bash
docker ps
```

---

### ✅ 2. **Exec into the Container**

```bash
docker exec -it <container_name_or_id> /bin/sh
# or bash if available
```

---

### ✅ 3. **Locate and Delete the Lock File**

Typical lock files:

* `/tmp/sanity.lock`
* `/var/lock/myapp.lock`
* `/app/locks/lockfile`

```bash
rm -f /path/to/sanity.lock
```

---

### ✅ 4. **Restart the Process (if needed)**

```bash
supervisorctl restart <process_name>
# or
service <your-service> restart
# or just
exit
docker restart <container_name>
```

---

## ⚠️ Caution:

* Lock files are usually created to prevent **duplicate or unsafe execution**.
* Make sure **no other instance is running** or it’s **safe to remove the lock**.

---

Let me know your container's tech stack (e.g., Python, Java, custom app), and I can help you locate common lock paths or automate this!


Here’s a complete **Kubernetes (K8s) guide**—ideal for interviews, production use, or just mastering cloud-native deployments.

---

## 🚀 What is Kubernetes?

**Kubernetes** is an open-source container orchestration platform for automating deployment, scaling, and management of containerized applications.

> Originally developed by Google, now maintained by the CNCF.

---

## 🧠 Why Kubernetes?

* Auto-scaling & self-healing
* Zero-downtime rolling deployments
* Efficient resource utilization
* Load balancing & service discovery
* Infrastructure abstraction

---

## 🧱 Core Concepts

| Concept        | Purpose                                            |
| -------------- | -------------------------------------------------- |
| **Pod**        | Smallest deployable unit; 1+ containers            |
| **Node**       | A machine (VM/physical) in the cluster             |
| **Cluster**    | Group of nodes managed by control plane            |
| **Deployment** | Manages desired state (replicas, rolling updates)  |
| **ReplicaSet** | Ensures a set number of pod replicas               |
| **Service**    | Exposes pods (ClusterIP, NodePort, LoadBalancer)   |
| **Ingress**    | HTTP routing to services (e.g., via hostname/path) |
| **ConfigMap**  | Injects config data (non-secret) into containers   |
| **Secret**     | Securely injects sensitive data (API keys, etc.)   |
| **Volume**     | Persistent storage (e.g., PVCs backed by EBS, NFS) |
| **Namespace**  | Logical group of resources for isolation           |

---

## 📦 Common kubectl Commands

```bash
kubectl get pods                         # List all pods
kubectl describe pod <name>              # Pod details
kubectl logs <pod-name>                  # Show logs
kubectl exec -it <pod-name> -- bash      # Shell into pod
kubectl apply -f file.yaml               # Deploy resources
kubectl delete -f file.yaml              # Delete resources
kubectl get svc                          # Services
kubectl get deployments                  # Deployments
```

---

## ⚙️ Kubernetes Architecture

```
                    +------------------------+
                    |     Control Plane      |
                    |------------------------|
                    | API Server             |
                    | Scheduler              |
                    | Controller Manager     |
                    | etcd (KV store)        |
                    +------------------------+
                              |
                +-------------+-------------+
                |                           |
      +---------+---------+       +---------+---------+
      |      Worker Node 1 |       |      Worker Node 2 |
      |---------------------|       |---------------------|
      | kubelet             |       | kubelet             |
      | kube-proxy          |       | kube-proxy          |
      | Container Runtime   |       | Container Runtime   |
      | Pods (containers)   |       | Pods (containers)   |
      +---------------------+       +---------------------+
```

---

## 📄 Example Deployment YAML

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
        - name: app
          image: myapp:v1
          ports:
            - containerPort: 80
```

---

## 🚦 Types of Services

| Type             | Purpose                               |
| ---------------- | ------------------------------------- |
| **ClusterIP**    | Default; internal access only         |
| **NodePort**     | Exposes service on host port          |
| **LoadBalancer** | Uses cloud LB (e.g., AWS ELB)         |
| **Ingress**      | HTTP routing layer on top of services |

---

## 🔐 Secrets vs ConfigMap

| Feature  | ConfigMap          | Secret                        |
| -------- | ------------------ | ----------------------------- |
| Used for | Non-sensitive data | Sensitive data (tokens, keys) |
| Encoding | Plain text         | Base64                        |
| Example  | ENV config         | DB password                   |

---

## 📊 Kubernetes in Interviews

### Common Questions:

* Difference between **Pod vs Deployment**
* What happens when you run `kubectl apply`?
* How does Kubernetes achieve **high availability**?
* Explain **liveness** and **readiness probes**
* What is the use of **Namespaces**?
* How does **autoscaling** work (HPA)?
* What’s a **StatefulSet**?

---

## 🔄 Deployment Strategies

| Strategy       | Purpose                                  |
| -------------- | ---------------------------------------- |
| Rolling Update | Default, zero downtime                   |
| Recreate       | Stops old pods before starting new ones  |
| Blue/Green     | Deploy new version alongside old         |
| Canary         | Release to small set before full rollout |

---

## 🧠 Cool Tips

* ✅ Use `kubens` to switch namespaces quickly.
* ✅ Use `k9s` terminal UI for managing K8s resources.
* ✅ Enable `kubectl explain <resource>` to learn any resource in-line.
* ✅ Use `kubectl diff -f` before apply to preview changes.

---

Here are **detailed answers** to your Kubernetes interview questions:

---

## ✅ 1. **Difference between Pod vs Deployment**

| Feature    | **Pod**                                   | **Deployment**                                  |
| ---------- | ----------------------------------------- | ----------------------------------------------- |
| Definition | Smallest unit in K8s; wraps 1+ containers | High-level controller that manages pods         |
| Use Case   | Direct container execution (simple/test)  | Manages pod replicas, rolling updates, scaling  |
| Scaling    | Not directly scalable                     | Easily scalable (replica count)                 |
| Updates    | Manual recreation needed                  | Automatically handles rolling updates/rollbacks |
| Restart    | Not auto-rescheduled on failure           | Recreates failed pods automatically             |

**→ Summary**:
Use **Pod** for basic usage/debugging. Use **Deployment** for production-grade app lifecycle management.

---

## ✅ 2. **What happens when you run `kubectl apply`?**

* Parses the YAML/JSON manifest.
* Talks to the **API Server**.
* Compares desired state from file vs existing state in cluster.
* Creates/updates resources (Pods, Services, etc.).
* Saves config as an annotation (`kubectl.kubernetes.io/last-applied-configuration`) for future diffs.

> 💡 `kubectl apply` is declarative—ensures that your system matches the desired state.

---

## ✅ 3. **How does Kubernetes achieve High Availability (HA)?**

### At **Control Plane Level**:

* Multiple **API servers**, **etcd** nodes, **controller managers**, and **schedulers** running behind a load balancer.
* Failover handled automatically.

### At **Application Level**:

* **Deployments** maintain desired replicas.
* **Pods** rescheduled on failure.
* **Node auto-replacement** via cloud autoscaler or taints/tolerations.

### At **Network/Service Level**:

* **Services** abstract pod IPs and provide seamless failover.
* **LoadBalancer / Ingress** handle traffic routing across healthy pods.

---

## ✅ 4. **Explain Liveness and Readiness Probes**

| Probe Type    | Purpose                                                           |
| ------------- | ----------------------------------------------------------------- |
| **Liveness**  | Checks if the container is **alive** (should be restarted if not) |
| **Readiness** | Checks if the container is **ready to serve traffic**             |

### Example:

```yaml
livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 10

readinessProbe:
  tcpSocket:
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
```

> 🔁 Liveness → Restart if stuck
> ⏳ Readiness → Delay traffic until ready

---

## ✅ 5. **What is the use of Namespaces?**

**Namespaces** logically group and isolate resources in a cluster.

### Why use them:

* Environment separation (`dev`, `qa`, `prod`)
* Access control via **RBAC**
* Resource quotas (memory/CPU limits)
* Easier organization in multi-tenant clusters

### Commands:

```bash
kubectl get pods -n dev
kubectl create namespace staging
```

---

## ✅ 6. **How does autoscaling work (HPA)?**

**HPA (Horizontal Pod Autoscaler)** automatically adjusts pod replicas based on resource usage.

### How it works:

* Monitors metrics (CPU, memory, custom)
* Uses Kubernetes Metrics Server
* Scales pods up/down within min-max limits

### YAML Example:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

> 🔄 Periodically checks and scales based on CPU/Memory load.

---

## ✅ 7. **What is a StatefulSet?**

**StatefulSet** is used for stateful apps that:

* Require **stable identities** (hostnames)
* Need **persistent storage** across restarts
* Have **ordered startup/shutdown**

### Use Cases:

* Databases (MySQL, Cassandra)
* Queues (Kafka, RabbitMQ)

### Key Features:

* Persistent Volume Claims per pod
* Fixed pod names (`app-0`, `app-1`, ...)
* Ordered deployment and scaling

### Example:

```yaml
apiVersion: apps/v1
kind: StatefulSet
spec:
  serviceName: "db"
  replicas: 3
  selector:
    matchLabels:
      app: db
  template:
    spec:
      containers:
      - name: mysql
        image: mysql
```

---
