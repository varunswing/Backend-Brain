# Docker

## 🧠 **1. What is Docker?**

**Docker** is a platform to:

* Package an app + dependencies into a **container**.
* Run it **consistently across environments** (dev, test, prod).
* Use lightweight, isolated, and fast instances.

> 💡 Think of containers as “mini-VMs without OS overhead.”

---

## ⚙️ **2. Key Concepts**

| Term               | Description                                                     |
| ------------------ | --------------------------------------------------------------- |
| **Image**          | Blueprint for a container (includes app + environment).         |
| **Container**      | Running instance of an image (isolated and lightweight).        |
| **Dockerfile**     | Script to build images (defines OS, commands, etc).             |
| **Docker Hub**     | Public registry for Docker images.                              |
| **Volumes**        | Used to persist data outside containers.                        |
| **Networks**       | Enable communication between containers.                        |
| **Docker Compose** | Define and run multi-container apps using `docker-compose.yml`. |

---

## 📦 **3. Docker Workflow (End-to-End)**

1. **Write Dockerfile**
2. `docker build -t <image> .` → Build image
3. `docker run <image>` → Start container
4. Push to registry: `docker push`
5. Deploy with: Docker CLI / Compose / Kubernetes

---

## 📁 **4. Dockerfile Example**

```dockerfile
FROM openjdk:17
COPY target/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build:

```bash
docker build -t my-app .
```

Run:

```bash
docker run -p 8080:8080 my-app
```

---

## 🧪 **5. Common Interview Questions with Answers**

---

### ✅ Q1: What is Docker and why do we use it?

**Answer**:
Docker is a containerization tool that packages applications with their dependencies to ensure consistent environments across development and production. It simplifies deployment, reduces conflicts, and accelerates CI/CD workflows.

---

### ✅ Q2: Difference between VM and Docker container?

| Feature   | VM            | Docker Container  |
| --------- | ------------- | ----------------- |
| Size      | Heavy (GBs)   | Lightweight (MBs) |
| Boot time | Minutes       | Seconds           |
| OS        | Full guest OS | Shares host OS    |
| Isolation | Strong        | Process-level     |

---

### ✅ Q3: What is the difference between Docker image and container?

* **Image**: Read-only template used to create containers.
* **Container**: Running instance of an image (with its own filesystem).

---

### ✅ Q4: How do you persist data in Docker?

**Answer**:
Use **Docker Volumes** to store data outside the container:

```bash
docker run -v mydata:/data my-image
```

This ensures data survives container restarts/deletions.

---

### ✅ Q5: What is Docker Compose?

**Answer**:
A tool to define and manage **multi-container applications** using a `docker-compose.yml` file.
Example:

```yaml
version: '3'
services:
  app:
    image: my-app
    ports:
      - "8080:8080"
  db:
    image: postgres
```

---

### ✅ Q6: What are some Docker networking modes?

| Mode        | Description                         |
| ----------- | ----------------------------------- |
| **bridge**  | Default, containers get private IPs |
| **host**    | Shares host network                 |
| **none**    | No networking                       |
| **overlay** | Used in Docker Swarm                |

---

### ✅ Q7: How do you optimize Docker images?

**Answer**:

* Use **multi-stage builds**
* Minimize base image (e.g., `alpine`)
* Combine RUN commands
* Use `.dockerignore` to avoid unnecessary files

---

### ✅ Q8: What is a multi-stage build?

**Answer**:
A technique to reduce final image size by separating build and runtime environments in Dockerfile.

```dockerfile
FROM maven as builder
COPY . .
RUN mvn package

FROM openjdk:17
COPY --from=builder target/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

### ✅ Q9: Can a container communicate with another container?

**Answer**:
Yes, using **Docker networks**. Containers in the same user-defined bridge network can access each other by service name.

---

### ✅ Q10: How does Docker handle isolation?

**Answer**:
Docker uses **Linux kernel features**:

* **Namespaces** → Isolate process, network, file system
* **Cgroups** → Resource limits (CPU, memory)

---

## 📚 **6. Advanced Interview Topics**

* Docker & Kubernetes integration
* CI/CD with Docker (Jenkins, GitLab CI)
* Docker security best practices
* Caching in Docker builds
* Handling secrets in containers
* Deploying with Docker Swarm
* Image layers and union file systems

---

## 🚀 Summary for Interviews

| Area               | Key Focus                        |
| ------------------ | -------------------------------- |
| Basics             | Image vs Container, Dockerfile   |
| Practical          | Commands, Volumes, Networks      |
| Deployment         | Compose, CI/CD                   |
| Optimization       | Small images, multi-stage        |
| Security + Scaling | Secrets, orchestration tools     |
| Debugging          | `docker logs`, `exec`, `inspect` |

---

Here’s a **Docker Cheatsheet** 🐳 – perfect for interviews, daily dev, and quick reference.

---

## 🚀 **1. Basics**

```bash
docker --version                   # Check Docker version
docker info                        # System-wide info
docker help                        # Get help
```

---

## 📦 **2. Images**

```bash
docker pull <image>               # Download image from Docker Hub
docker images                     # List local images
docker rmi <image_id>             # Remove image
docker build -t name .            # Build image from Dockerfile
docker tag <img_id> repo:tag      # Tag image
docker push repo:tag              # Push to Docker Hub
```

---

## 🧱 **3. Containers**

```bash
docker run <img>                  # Run image as container
docker run -it <img> bash         # Interactive mode
docker run -d -p 8080:80 <img>    # Detached, map port
docker ps                         # Running containers
docker ps -a                      # All containers
docker stop <id>                  # Stop container
docker start <id>                 # Start container
docker rm <id>                    # Delete container
docker logs <id>                  # Show logs
docker exec -it <id> bash         # Shell into container
docker inspect <id>               # Low-level details
```

---

## 🧾 **4. Volumes (Data Persistence)**

```bash
docker volume create mydata
docker run -v mydata:/data <img>
docker volume ls
docker volume inspect mydata
docker volume rm mydata
```

---

## 🌐 **5. Networks**

```bash
docker network ls
docker network create my-net
docker run --network=my-net <img>
docker network inspect my-net
```

---

## 🧰 **6. Dockerfile Basics**

```dockerfile
FROM node:18
WORKDIR /app
COPY . .
RUN npm install
EXPOSE 3000
CMD ["npm", "start"]
```

---

## 🧪 **7. Docker Compose**

```yaml
version: '3'
services:
  web:
    image: my-web
    ports:
      - "8080:8080"
  db:
    image: postgres
    environment:
      POSTGRES_PASSWORD: pass
```

```bash
docker-compose up                 # Run
docker-compose up -d             # Detached
docker-compose down              # Stop and clean
```

---

## 🧹 **8. Cleanup**

```bash
docker container prune           # Remove stopped containers
docker image prune               # Remove unused images
docker volume prune              # Clean volumes
docker system prune              # Clean everything
```

---

## 🧠 **9. Useful Flags**

| Flag        | Purpose                     |
| ----------- | --------------------------- |
| `-p`        | Port mapping (`8080:80`)    |
| `-d`        | Detached mode               |
| `-it`       | Interactive + TTY           |
| `--rm`      | Auto-remove after exit      |
| `--name`    | Name your container         |
| `-v`        | Mount volumes               |
| `--network` | Connect to specific network |

---

If you've already deployed a Docker container from an image tag and want to change a **specific file**, you have **3 options** depending on your use case:

---

## ✅ Option 1: **Copy file into running container**

```bash
docker cp ./my-local-file.txt <container_name_or_id>:/path/in/container/
```

### Example:

```bash
docker cp updated_config.yml myapp:/app/config/config.yml
```

> ⚠️ This change is **temporary**—it’s lost if the container restarts.

---

## ✅ Option 2: **Exec and edit directly inside container**

```bash
docker exec -it <container> sh
# or bash
vi /path/to/file
```

> Use this to quickly debug or hotfix in a live container. But again, it won’t persist after a container restart.

---

## ✅ Option 3: **Make change permanent (build new image)**

1. **Edit the file locally**
2. Update your `Dockerfile` (if needed)
3. Rebuild the image:

   ```bash
   docker build -t myapp:v2 .
   ```
4. Stop and remove old container:

   ```bash
   docker stop myapp && docker rm myapp
   ```
5. Run new one:

   ```bash
   docker run -d --name myapp -p 8080:80 myapp:v2
   ```

> ✅ This is the **recommended way** for production — ensures traceability, versioning, CI/CD integration.

---

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
