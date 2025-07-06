Design a **Meeting and Room Reservation System** (like Outlook or Microsoft Teams) using LLD best practices.

---

## ✅ 1. Functional & Non-Functional Requirements

### 🎯 Functional Requirements

* Create/edit/delete a meeting
* Book a meeting room if available
* Invite attendees to a meeting
* Check availability of attendees and rooms
* Allow recurring meetings
* Notify participants
* Cancel meeting and free the room
* View calendar/day-wise schedule

### ⚙️ Non-Functional Requirements

* High availability and consistency
* Optimistic concurrency control (to prevent double-booking)
* Role-based access (admin vs. employee)
* Secure APIs (JWT, RBAC)
* Scalable to large orgs with 1000+ rooms/users

---

## ✅ 2. DB Schema

### `User`

```sql
CREATE TABLE Users (
  id UUID PRIMARY KEY,
  name TEXT,
  email TEXT UNIQUE,
  role TEXT CHECK (role IN ('EMPLOYEE', 'ADMIN'))
);
```

### `Room`

```sql
CREATE TABLE Rooms (
  id UUID PRIMARY KEY,
  name TEXT,
  capacity INT,
  floor TEXT,
  building TEXT
);
```

### `Meeting`

```sql
CREATE TABLE Meetings (
  id UUID PRIMARY KEY,
  organizer_id UUID REFERENCES Users(id),
  room_id UUID REFERENCES Rooms(id),
  title TEXT,
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  recurrence TEXT, -- DAILY, WEEKLY, NONE
  status TEXT CHECK (status IN ('SCHEDULED', 'CANCELLED')),
  created_at TIMESTAMP DEFAULT now()
);
```

### `MeetingParticipants`

```sql
CREATE TABLE MeetingParticipants (
  meeting_id UUID REFERENCES Meetings(id),
  user_id UUID REFERENCES Users(id),
  PRIMARY KEY (meeting_id, user_id)
);
```

---

## ✅ 3. UML Class Diagram

```
+--------+        +----------+        +------------+
|  User  |<-----> | Meeting  |<------>|   Room     |
+--------+        +----------+        +------------+
    |                 |
    v                 v
+---------------------------+
|   MeetingParticipants     |
+---------------------------+
```

---

## ✅ 4. Core Classes (LLD)

### Class: `User`

```java
class User {
    UUID id;
    String name;
    String email;
    Role role;
}
```

### Class: `Room`

```java
class Room {
    UUID id;
    String name;
    int capacity;
    String building;
    String floor;
}
```

### Class: `Meeting`

```java
class Meeting {
    UUID id;
    User organizer;
    Room room;
    String title;
    LocalDateTime start;
    LocalDateTime end;
    Recurrence recurrence;
    List<User> participants;
    MeetingStatus status;
}
```

---

## ✅ 5. Key Service: `MeetingService`

### Create Meeting with Room Booking

```java
public class MeetingService {
    private RoomRepository roomRepo;
    private MeetingRepository meetingRepo;

    public Meeting createMeeting(User organizer, List<User> participants,
                                 LocalDateTime start, LocalDateTime end,
                                 String title, int capacity) {
        List<Room> rooms = roomRepo.findAvailableRooms(start, end, capacity);
        if (rooms.isEmpty()) throw new NoRoomAvailableException();

        Room bookedRoom = rooms.get(0); // apply better selection logic if needed

        Meeting meeting = new Meeting(UUID.randomUUID(), organizer, bookedRoom,
                                      title, start, end, Recurrence.NONE,
                                      participants, MeetingStatus.SCHEDULED);
        meetingRepo.save(meeting);
        return meeting;
    }
}
```

---

## ✅ 6. API Endpoints

### 📅 Create Meeting

```http
POST /api/meetings
Authorization: Bearer <JWT>
{
  "title": "Design Review",
  "organizerId": "u1",
  "startTime": "2025-07-01T10:00",
  "endTime": "2025-07-01T11:00",
  "participantIds": ["u2", "u3"],
  "capacity": 5
}
```

### 🔍 Search Room Availability

```http
GET /api/rooms/available?start=2025-07-01T10:00&end=2025-07-01T11:00&capacity=4
```

### ❌ Cancel Meeting

```http
POST /api/meetings/{id}/cancel
```

---

## ✅ 7. Design Patterns Used

| Pattern        | Where Used                           |
| -------------- | ------------------------------------ |
| **Factory**    | Creating Meeting instances           |
| **Repository** | RoomRepository, MeetingRepository    |
| **Observer**   | For notification on meeting creation |
| **Builder**    | (Optional) to build meeting objects  |
| **Strategy**   | Room allocation strategy             |

---

## ✅ 8. Concurrency Handling

### 🔄 Problem: Two people try to book the same room

**Solution**:

* Use **pessimistic locking** at DB layer (`SELECT ... FOR UPDATE`)
* Or **optimistic locking** with version numbers

#### In SQL:

```sql
SELECT * FROM Meetings
WHERE room_id = :roomId
  AND start_time < :end
  AND end_time > :start
FOR UPDATE;
```

---

## ✅ 9. Edge Cases

| Edge Case                        | Solution                           |
| -------------------------------- | ---------------------------------- |
| Recurring meetings conflict      | Check all instances before booking |
| Attendee not available           | Optional conflict warning UI       |
| Meeting cancel → room not freed  | Use transactions & status flags    |
| Double booking                   | DB transaction isolation           |
| Room capacity < participant size | Filter during booking              |

---

## ✅ 10. Observability

| Metric                 | How to Track                        |
| ---------------------- | ----------------------------------- |
| Total bookings         | Count of `Meetings` per day/week    |
| Room utilization       | % of hours booked                   |
| Failed room allocation | Count of `NoRoomAvailableException` |
| Participant conflicts  | Count + which users were affected   |
| API response latency   | Prometheus + Grafana                |

---

## ✅ 11. Security

* **JWT Auth**: all APIs require authentication
* **RBAC**:

  * `ADMIN` → can add/edit/delete rooms
  * `EMPLOYEE` → can book/view/cancel meetings
* Input validation and rate limiting

---

## ✅ 12. Scalability

| Component     | Strategy                              |
| ------------- | ------------------------------------- |
| Meeting table | Partition by `room_id` or date        |
| Caching       | Cache available rooms                 |
| Services      | Stateless APIs for horizontal scaling |
| Search        | ElasticSearch for advanced filters    |

---

## ✅ 13. Hold Expiry (Optional)

If you want to allow temporary room hold (before final confirmation), use Redis with TTL:

```bash
SET hold:room:roomId:userId START-END EX 300
```

---