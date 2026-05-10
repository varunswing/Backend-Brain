# Social Media Platform - LLD / Machine Coding Interview Study Document

---

## 1. Problem Statement

Design a social media platform (like Facebook/Twitter) that allows users to create posts, follow other users, like and comment on posts, and view a personalized feed. The system must support multiple feed algorithms (chronological, ranked, trending), real-time notifications, and handle fan-out strategies for feed distribution at scale.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement | Description |
|----|-------------|-------------|
| FR1 | User Registration & Profile | Create account, update profile (bio, avatar) |
| FR2 | Create Post | Create text, image, or video posts with optional tags, location, visibility |
| FR3 | Follow/Unfollow | Follow other users; asymmetric relationship (A follows B ≠ B follows A) |
| FR4 | Like/Unlike | Like a post; unlike removes like. Idempotent (double-like = single like) |
| FR5 | Comment | Add comments on posts; nested replies optional |
| FR6 | Feed | View personalized feed (chronological, ranked, or trending) |
| FR7 | Notifications | Receive notifications for likes, comments, new followers |
| FR8 | Post Visibility | PUBLIC, PRIVATE, FRIENDS_ONLY (visible only to followers) |

### Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR1 | Latency | Feed load < 200ms (P95) |
| NFR2 | Scalability | Support 10M+ users, 100M+ posts |
| NFR3 | Availability | 99.9% uptime |
| NFR4 | Extensibility | Easy to add new feed algorithms and post types |
| NFR5 | Consistency | Like count eventually consistent; idempotent like/unlike |

---

## 3. Database Design with Explanations

```sql
/*
 * TABLE: users
 *
 * WHY this table exists:
 *   - Core entity. Every post, like, comment, follow is tied to a user.
 *   - Stores profile data for display in feeds and notifications.
 *
 * WHY each foreign key/relationship:
 *   - No FKs in users (root entity). Referenced by posts, comments, likes,
 *     follows, notifications.
 *
 * WHY this structure over alternatives:
 *   - BIGINT id: Simple, efficient for joins. UUID alternative for distributed
 *     systems; BIGINT fine for single-DB LLD scope.
 *   - followers_count, following_count: Denormalized counters for fast display.
 *     Updated via triggers or application logic on follow/unfollow.
 *
 * WHY these indexes:
 *   - idx_users_username: Unique lookup for @username mentions and profile URLs.
 *   - idx_users_email: Auth and uniqueness.
 */
CREATE TABLE users (
    user_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    bio TEXT,
    avatar_url VARCHAR(500),
    followers_count INT DEFAULT 0,
    following_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_users_username (username),
    INDEX idx_users_email (email)
);

/*
 * TABLE: posts
 *
 * WHY this table exists:
 *   - Core content entity. Feed is built from posts.
 *   - Stores text content and metadata; media stored separately (see media table).
 *
 * WHY each foreign key/relationship:
 *   - user_id -> users: Every post has an author. Enables "posts by user X"
 *     and feed assembly from followed users.
 *
 * WHY this structure over alternatives:
 *   - visibility enum: PUBLIC/PRIVATE/FRIENDS_ONLY. Stored as VARCHAR for
 *     flexibility; enum in application.
 *   - content TEXT: Variable-length; TEXT allows long posts.
 *
 * WHY these indexes:
 *   - idx_posts_user_created: User's timeline = posts by user_id ORDER BY created_at DESC.
 *   - idx_posts_created: Chronological feed; trending by recency.
 *   - idx_posts_visibility: Filter by visibility for feed assembly.
 */
CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    likes_count INT DEFAULT 0,
    comments_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    INDEX idx_posts_user_created (user_id, created_at DESC),
    INDEX idx_posts_created (created_at DESC),
    INDEX idx_posts_visibility (visibility)
);

/*
 * TABLE: comments
 *
 * WHY this table exists:
 *   - Comments are first-class entities. Need to show "who commented what when",
 *     support nested replies, and count comments per post.
 *
 * WHY each foreign key/relationship:
 *   - user_id -> users: Commenter identity.
 *   - post_id -> posts: Comment belongs to a post. CASCADE delete when post deleted.
 *   - parent_comment_id -> comments: Optional. Enables nested replies (threaded comments).
 *
 * WHY this structure over alternatives:
 *   - parent_comment_id NULL: Top-level comment. Non-NULL = reply to that comment.
 *   - Flat vs nested: parent_comment_id allows both; can fetch top-level and
 *     optionally load replies.
 *
 * WHY these indexes:
 *   - idx_comments_post_created: "Get comments for post X" ordered by time.
 *   - idx_comments_parent: Load replies for a given parent comment.
 */
CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_comment_id BIGINT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts(post_id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_comment_id) REFERENCES comments(comment_id) ON DELETE CASCADE,
    INDEX idx_comments_post_created (post_id, created_at ASC),
    INDEX idx_comments_parent (parent_comment_id)
);

/*
 * TABLE: likes
 *
 * WHY likes is a SEPARATE table (not just a counter on posts):
 *   1. WHO liked: Need to show "Liked by X, Y and 10 others" and "Unlike" for
 *      the current user. A counter alone cannot tell who liked.
 *   2. Idempotency: Like (user_id, post_id) UNIQUE prevents duplicate likes.
 *      Double-click = single row; unlike deletes that row. Counter would need
 *      complex logic to avoid double-counting.
 *   3. Unlike: Delete row from likes; decrement posts.likes_count. Simple and correct.
 *   4. Analytics: "Which posts did user X like?" requires querying by user_id.
 *   5. Notifications: "User Y liked your post" — need to know Y's identity.
 *
 * WHY each foreign key/relationship:
 *   - user_id -> users: Who liked.
 *   - post_id -> posts: Which post. CASCADE delete when post deleted.
 *
 * WHY UNIQUE(user_id, post_id):
 *   - One like per user per post. Prevents duplicates; enables idempotent like.
 *
 * WHY these indexes:
 *   - idx_likes_post: "Get likers of post X" and "Count likes for post X".
 *   - idx_likes_user: "Posts user X liked" for analytics and feed ranking.
 *   - UNIQUE: Enforces one-like-per-user and enables upsert/delete for idempotency.
 */
CREATE TABLE likes (
    like_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_likes_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_likes_post FOREIGN KEY (post_id) REFERENCES posts(post_id) ON DELETE CASCADE,
    UNIQUE KEY uk_likes_user_post (user_id, post_id),
    INDEX idx_likes_post (post_id),
    INDEX idx_likes_user (user_id)
);

/*
 * TABLE: follows
 *
 * WHY follows has BOTH follower_id AND following_id:
 *   - Asymmetric relationship: "A follows B" means A is the follower, B is being followed.
 *   - follower_id = user who clicks "Follow" (the follower).
 *   - following_id = user who is followed (the followee).
 *   - A follows B ≠ B follows A. Two rows: (A,B) and (B,A) if mutual.
 *   - Feed query: "Get posts from users that current_user follows" =
 *     SELECT * FROM posts WHERE user_id IN (SELECT following_id FROM follows WHERE follower_id = current_user).
 *   - "Who follows me?" = follower_id where following_id = me.
 *   - "Who do I follow?" = following_id where follower_id = me.
 *
 * WHY each foreign key/relationship:
 *   - follower_id -> users: The follower.
 *   - following_id -> users: The followee.
 *
 * WHY UNIQUE(follower_id, following_id):
 *   - One follow relationship per pair. Prevents duplicate follows.
 *
 * WHY these indexes:
 *   - idx_follows_follower: "Who do I follow?" — list of following_id for a follower.
 *   - idx_follows_following: "Who follows me?" — list of follower_id for a followee.
 */
CREATE TABLE follows (
    follow_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_follows_following FOREIGN KEY (following_id) REFERENCES users(user_id) ON DELETE CASCADE,
    UNIQUE KEY uk_follows_follower_following (follower_id, following_id),
    INDEX idx_follows_follower (follower_id),
    INDEX idx_follows_following (following_id),
    CHECK (follower_id != following_id)
);

/*
 * TABLE: media
 *
 * WHY media is SEPARATE from posts:
 *   1. One post, multiple media: A post can have multiple images/videos.
 *      Storing in posts would require JSON array or separate columns; media table
 *      is normalized (one row per media item).
 *   2. Reusability: Same image could theoretically be attached to multiple posts
 *      (e.g., shared content). Separate table allows post_id FK to link.
 *   3. Different processing: Media needs upload, CDN, transcoding (video).
 *      Separate table allows media-specific logic (dimensions, format, thumbnail).
 *   4. Storage/bandwidth: Media URLs and metadata separate from post text.
 *      Can archive/delete media independently.
 *   5. Lazy loading: Feed can load post text first, media on demand.
 *
 * WHY each foreign key/relationship:
 *   - post_id -> posts: Media belongs to a post. CASCADE when post deleted.
 *
 * WHY this structure:
 *   - content_type: IMAGE, VIDEO. Different handling (thumbnail vs stream).
 *   - url: CDN URL. Actual file in object storage (S3); DB stores reference.
 *   - display_order: Order of media in carousel (1, 2, 3...).
 *
 * WHY these indexes:
 *   - idx_media_post: "Get all media for post X" for display.
 */
CREATE TABLE media (
    media_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    thumbnail_url VARCHAR(1000),
    display_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_media_post FOREIGN KEY (post_id) REFERENCES posts(post_id) ON DELETE CASCADE,
    INDEX idx_media_post (post_id)
);

/*
 * TABLE: notifications
 *
 * WHY this table exists:
 *   - Users need to see "X liked your post", "Y commented", "Z followed you".
 *   - Store notification events for in-app feed and push.
 *
 * WHY each foreign key/relationship:
 *   - user_id -> users: Recipient of notification (whose notification feed).
 *   - actor_id -> users: Who performed the action (liker, commenter, follower).
 *   - post_id, comment_id: Optional. Reference to related content for deep linking.
 *
 * WHY this structure:
 *   - notification_type: LIKE, COMMENT, FOLLOW, etc. Drives UI and routing.
 *   - is_read: Track read state for badge counts.
 *
 * WHY these indexes:
 *   - idx_notifications_user_created: "Get my notifications" ordered by time.
 *   - idx_notifications_unread: "Count unread" for badge.
 */
CREATE TABLE notifications (
    notification_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    notification_type VARCHAR(30) NOT NULL,
    post_id BIGINT NULL,
    comment_id BIGINT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_post FOREIGN KEY (post_id) REFERENCES posts(post_id) ON DELETE SET NULL,
    CONSTRAINT fk_notifications_comment FOREIGN KEY (comment_id) REFERENCES comments(comment_id) ON DELETE SET NULL,
    INDEX idx_notifications_user_created (user_id, created_at DESC),
    INDEX idx_notifications_unread (user_id, is_read)
);
```

---

## 4. Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `FeedGenerationStrategy` (ChronologicalFeed, RankedFeed, TrendingFeed) | Different feed algorithms are interchangeable. Client selects strategy at runtime; add new algorithms without changing FeedService. |
| **Observer** | `PostEventObserver` (FeedUpdateObserver, NotificationObserver, TrendingObserver) | When post is created/liked/commented, multiple subsystems react (feed update, notification, trending). Observer decouples publishers from subscribers. |
| **Factory** | `ContentFactory` (create TextPost, ImagePost, VideoPost) | Encapsulates creation logic for different post types. Caller doesn't need to know concrete classes. |
| **Builder** | `Post.Builder` | Post has many optional fields (media, tags, location, visibility). Builder avoids telescoping constructors and improves readability. |
| **Repository** | `PostRepository`, `UserRepository`, etc. | Abstracts data access. Services depend on interfaces; enables testing with mocks. |
| **Dependency Injection** | `FeedService`, `PostService` constructors | Services receive dependencies via constructor. Loose coupling, testability. |

---

## 5. SOLID Principles

| Principle | How |
|-----------|-----|
| **S - Single Responsibility** | `ChronologicalFeedStrategy` only generates chronological feed. `PostService` orchestrates post creation; doesn't implement feed logic. |
| **O - Open/Closed** | New feed algorithms: implement `FeedGenerationStrategy`. New post types: extend `ContentFactory`. No changes to existing code. |
| **L - Liskov Substitution** | Any `FeedGenerationStrategy` can replace another in `FeedService`. Any `PostEventObserver` can be added/removed without breaking others. |
| **I - Interface Segregation** | `FeedGenerationStrategy` has one method. `PostEventObserver` has one method. No fat interfaces. |
| **D - Dependency Inversion** | `FeedService` depends on `FeedGenerationStrategy` and `PostRepository` (abstractions), not concrete implementations. |

---

## 6. Code Implementation in Java

### Enums

```java
/**
 * WHY enum: Type-safe visibility. Prevents invalid values.
 * PUBLIC: visible to all. PRIVATE: only author. FRIENDS_ONLY: only followers.
 */
public enum PostVisibility {
    PUBLIC,
    PRIVATE,
    FRIENDS_ONLY
}

/**
 * WHY enum: Drives notification UI and routing.
 * Matches observer logic (NotificationObserver creates by type).
 */
public enum NotificationType {
    LIKE,
    COMMENT,
    FOLLOW,
    MENTION
}

/**
 * WHY enum: Different post types need different creation/display logic.
 * ContentFactory uses this to create appropriate Post subclass.
 */
public enum ContentType {
    TEXT,
    IMAGE,
    VIDEO
}
```

### Models with OOP

```java
import java.util.*;

/**
 * User with follow management.
 * OOP: Encapsulation - follow/unfollow logic inside User or UserService.
 * WHY: User "knows" about its social graph; follow operations are user-centric.
 */
public final class User {
    private final long userId;
    private final String username;
    private final String email;
    private final String bio;
    private final String avatarUrl;
    private final int followersCount;
    private final int followingCount;

    private User(Builder b) {
        this.userId = b.userId;
        this.username = b.username;
        this.email = b.email;
        this.bio = b.bio;
        this.avatarUrl = b.avatarUrl;
        this.followersCount = b.followersCount;
        this.followingCount = b.followingCount;
    }

    public long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getBio() { return bio; }
    public String getAvatarUrl() { return avatarUrl; }
    public int getFollowersCount() { return followersCount; }
    public int getFollowingCount() { return followingCount; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private long userId; private String username; private String email;
        private String bio; private String avatarUrl;
        private int followersCount; private int followingCount;
        public Builder userId(long id) { this.userId = id; return this; }
        public Builder username(String u) { this.username = u; return this; }
        public Builder email(String e) { this.email = e; return this; }
        public Builder bio(String b) { this.bio = b; return this; }
        public Builder avatarUrl(String a) { this.avatarUrl = a; return this; }
        public Builder followersCount(int c) { this.followersCount = c; return this; }
        public Builder followingCount(int c) { this.followingCount = c; return this; }
        public User build() { return new User(this); }
    }
}

/**
 * Post with encapsulation.
 * OOP: Post encapsulates content, visibility, metadata.
 * WHY: Callers use getters; validation (e.g., visibility check) inside Post.
 */
public class Post {
    private final long postId;
    private final long userId;
    private final String content;
    private final PostVisibility visibility;
    private final ContentType contentType;
    private final List<MediaItem> media;
    private final Set<String> tags;
    private final String location;
    private final int likesCount;
    private final int commentsCount;
    private final java.time.Instant createdAt;

    private Post(Builder b) {
        this.postId = b.postId;
        this.userId = b.userId;
        this.content = b.content;
        this.visibility = b.visibility;
        this.contentType = b.contentType;
        this.media = Collections.unmodifiableList(new ArrayList<>(b.media));
        this.tags = Collections.unmodifiableSet(new HashSet<>(b.tags));
        this.location = b.location;
        this.likesCount = b.likesCount;
        this.commentsCount = b.commentsCount;
        this.createdAt = b.createdAt;
    }

    public long getPostId() { return postId; }
    public long getUserId() { return userId; }
    public String getContent() { return content; }
    public PostVisibility getVisibility() { return visibility; }
    public ContentType getContentType() { return contentType; }
    public List<MediaItem> getMedia() { return media; }
    public Set<String> getTags() { return tags; }
    public String getLocation() { return location; }
    public int getLikesCount() { return likesCount; }
    public int getCommentsCount() { return commentsCount; }
    public java.time.Instant getCreatedAt() { return createdAt; }

    /** WHY: Encapsulation - visibility logic in one place. */
    public boolean isVisibleTo(long viewerId, boolean isFollower) {
        if (visibility == PostVisibility.PUBLIC) return true;
        if (visibility == PostVisibility.PRIVATE) return viewerId == userId;
        return visibility == PostVisibility.FRIENDS_ONLY && (viewerId == userId || isFollower);
    }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private long postId; private long userId; private String content;
        private PostVisibility visibility = PostVisibility.PUBLIC;
        private ContentType contentType = ContentType.TEXT;
        private List<MediaItem> media = new ArrayList<>();
        private Set<String> tags = new HashSet<>();
        private String location;
        private int likesCount; private int commentsCount;
        private java.time.Instant createdAt = java.time.Instant.now();

        public Builder postId(long id) { this.postId = id; return this; }
        public Builder userId(long id) { this.userId = id; return this; }
        public Builder content(String c) { this.content = c; return this; }
        public Builder visibility(PostVisibility v) { this.visibility = v; return this; }
        public Builder contentType(ContentType t) { this.contentType = t; return this; }
        public Builder media(List<MediaItem> m) { this.media = m != null ? m : new ArrayList<>(); return this; }
        public Builder addMedia(MediaItem m) { this.media.add(m); return this; }
        public Builder tags(Set<String> t) { this.tags = t != null ? t : new HashSet<>(); return this; }
        public Builder addTag(String t) { this.tags.add(t); return this; }
        public Builder location(String l) { this.location = l; return this; }
        public Builder likesCount(int c) { this.likesCount = c; return this; }
        public Builder commentsCount(int c) { this.commentsCount = c; return this; }
        public Builder createdAt(java.time.Instant t) { this.createdAt = t; return this; }
        public Post build() { return new Post(this); }
    }
}

public class MediaItem {
    private final long mediaId;
    private final String url;
    private final String thumbnailUrl;
    private final ContentType contentType;
    public MediaItem(long mediaId, String url, String thumbnailUrl, ContentType contentType) {
        this.mediaId = mediaId; this.url = url; this.thumbnailUrl = thumbnailUrl; this.contentType = contentType;
    }
    public long getMediaId() { return mediaId; }
    public String getUrl() { return url; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public ContentType getContentType() { return contentType; }
}
```

### Strategy: FeedGenerationStrategy

```java
/**
 * Strategy Pattern: Interchangeable feed algorithms.
 * WHY: Open/Closed - add ChronologicalFeed, RankedFeed, TrendingFeed without
 * changing FeedService. Client injects strategy.
 */
public interface FeedGenerationStrategy {
    List<Post> generateFeed(long userId, int limit, int offset);
}

/**
 * Chronological: Posts from followed users, newest first.
 * Fan-out on READ: Fetch posts at read time.
 */
public class ChronologicalFeedStrategy implements FeedGenerationStrategy {
    private final PostRepository postRepo;
    private final FollowRepository followRepo;

    public ChronologicalFeedStrategy(PostRepository postRepo, FollowRepository followRepo) {
        this.postRepo = postRepo;
        this.followRepo = followRepo;
    }

    @Override
    public List<Post> generateFeed(long userId, int limit, int offset) {
        List<Long> followingIds = followRepo.findFollowingIds(userId);
        if (followingIds.isEmpty()) return List.of();
        return postRepo.findByUserIdsOrderByCreatedDesc(followingIds, limit, offset);
    }
}

/**
 * Ranked: ML-style ranking by engagement, recency, relevance.
 * Simplified: score = likes_count * 2 + comments_count + recency_factor.
 */
public class RankedFeedStrategy implements FeedGenerationStrategy {
    private final PostRepository postRepo;
    private final FollowRepository followRepo;

    public RankedFeedStrategy(PostRepository postRepo, FollowRepository followRepo) {
        this.postRepo = postRepo;
        this.followRepo = followRepo;
    }

    @Override
    public List<Post> generateFeed(long userId, int limit, int offset) {
        List<Long> followingIds = followRepo.findFollowingIds(userId);
        if (followingIds.isEmpty()) return List.of();
        List<Post> posts = postRepo.findByUserIdsOrderByCreatedDesc(followingIds, 500, 0);
        // Rank by engagement score (simplified)
        posts.sort((a, b) -> {
            double scoreA = a.getLikesCount() * 2.0 + a.getCommentsCount() + recencyFactor(a.getCreatedAt());
            double scoreB = b.getLikesCount() * 2.0 + b.getCommentsCount() + recencyFactor(b.getCreatedAt());
            return Double.compare(scoreB, scoreA);
        });
        int from = Math.min(offset, posts.size());
        int to = Math.min(offset + limit, posts.size());
        return posts.subList(from, to);
    }

    private double recencyFactor(java.time.Instant createdAt) {
        long hoursAgo = java.time.Duration.between(createdAt, java.time.Instant.now()).toHours();
        return Math.max(0, 10 - hoursAgo);
    }
}

/**
 * Trending: Global posts with highest engagement in last 24h.
 * Fan-out on READ: No precomputation in this simplified version.
 */
public class TrendingFeedStrategy implements FeedGenerationStrategy {
    private final PostRepository postRepo;

    public TrendingFeedStrategy(PostRepository postRepo) {
        this.postRepo = postRepo;
    }

    @Override
    public List<Post> generateFeed(long userId, int limit, int offset) {
        java.time.Instant since = java.time.Instant.now().minus(java.time.Duration.ofHours(24));
        return postRepo.findTrendingSince(since, limit, offset);
    }
}
```

### Observer: PostEventObserver

```java
/**
 * Observer Pattern: When post events occur, observers react.
 * WHY: Loose coupling. PostService publishes; FeedUpdateObserver, NotificationObserver,
 * TrendingObserver subscribe. Add new observers without changing PostService.
 */
public interface PostEventObserver {
    void onPostCreated(Post post);
    void onPostLiked(long postId, long userId, long likerId);
    void onPostCommented(long postId, long userId, long commenterId, long commentId);
}

/**
 * Updates feed cache when new post is created.
 * Fan-out on WRITE: Push post to followers' feed caches.
 */
public class FeedUpdateObserver implements PostEventObserver {
    private final FollowRepository followRepo;
    private final FeedCache feedCache;

    public FeedUpdateObserver(FollowRepository followRepo, FeedCache feedCache) {
        this.followRepo = followRepo;
        this.feedCache = feedCache;
    }

    @Override
    public void onPostCreated(Post post) {
        List<Long> followerIds = followRepo.findFollowerIds(post.getUserId());
        for (long fid : followerIds) {
            feedCache.prependToFeed(fid, post);
        }
    }

    @Override
    public void onPostLiked(long postId, long userId, long likerId) { /* no feed update */ }
    @Override
    public void onPostCommented(long postId, long userId, long commenterId, long commentId) { /* no feed update */ }
}

/**
 * Creates notifications for likes, comments, follows.
 */
public class NotificationObserver implements PostEventObserver {
    private final NotificationRepository notificationRepo;

    public NotificationObserver(NotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    @Override
    public void onPostCreated(Post post) { /* no notification for own post */ }

    @Override
    public void onPostLiked(long postId, long userId, long likerId) {
        if (userId != likerId) {
            notificationRepo.create(userId, likerId, NotificationType.LIKE, postId, null);
        }
    }

    @Override
    public void onPostCommented(long postId, long userId, long commenterId, long commentId) {
        if (userId != commenterId) {
            notificationRepo.create(userId, commenterId, NotificationType.COMMENT, postId, commentId);
        }
    }
}

/**
 * Updates trending score for post (simplified: just track engagement).
 */
public class TrendingObserver implements PostEventObserver {
    private final TrendingRepository trendingRepo;

    public TrendingObserver(TrendingRepository trendingRepo) {
        this.trendingRepo = trendingRepo;
    }

    @Override
    public void onPostCreated(Post post) {
        trendingRepo.addPost(post.getPostId(), post.getCreatedAt());
    }

    @Override
    public void onPostLiked(long postId, long userId, long likerId) {
        trendingRepo.incrementEngagement(postId);
    }

    @Override
    public void onPostCommented(long postId, long userId, long commenterId, long commentId) {
        trendingRepo.incrementEngagement(postId);
    }
}
```

### Factory: ContentFactory

```java
/**
 * Factory Pattern: Create different post types (text, image, video).
 * WHY: Encapsulates creation logic. Caller says create(ContentType.IMAGE, ...)
 * and gets appropriate handling. Open/Closed for new types.
 */
public interface ContentFactory {
    Post createPost(long userId, String content, ContentType type, List<MediaItem> media,
                    Set<String> tags, String location, PostVisibility visibility);
}

public class DefaultContentFactory implements ContentFactory {
    @Override
    public Post createPost(long userId, String content, ContentType type, List<MediaItem> media,
                           Set<String> tags, String location, PostVisibility visibility) {
        return Post.builder()
                .userId(userId)
                .content(content)
                .contentType(type)
                .media(media != null ? media : List.of())
                .tags(tags != null ? tags : Set.of())
                .location(location)
                .visibility(visibility)
                .build();
    }
}
```

### FeedService and PostService (Orchestrators with DI)

```java
/**
 * FeedService: Orchestrates feed generation.
 * SOLID (S): Single responsibility - feed assembly only.
 * SOLID (D): Depends on FeedGenerationStrategy (abstraction), not concrete strategies.
 */
public class FeedService {
    private final FeedGenerationStrategy strategy;
    private final FeedCache feedCache;

    public FeedService(FeedGenerationStrategy strategy, FeedCache feedCache) {
        this.strategy = strategy;
        this.feedCache = feedCache;
    }

    public List<Post> getFeed(long userId, int limit, int offset) {
        // Optional: check cache first for fan-out-on-write
        List<Post> cached = feedCache.getFeed(userId, limit, offset);
        if (!cached.isEmpty()) return cached;
        return strategy.generateFeed(userId, limit, offset);
    }
}

/**
 * PostService: Orchestrates post creation, like, comment.
 * Fan-out: On post create, notify observers (feed update, trending).
 * Like/Unlike: Idempotent via UNIQUE(user_id, post_id) - insert ignore or delete.
 */
public class PostService {
    private final PostRepository postRepo;
    private final LikeRepository likeRepo;
    private final List<PostEventObserver> observers;

    public PostService(PostRepository postRepo, LikeRepository likeRepo,
                       List<PostEventObserver> observers) {
        this.postRepo = postRepo;
        this.likeRepo = likeRepo;
        this.observers = observers;
    }

    public Post createPost(Post post) {
        Post saved = postRepo.save(post);
        for (PostEventObserver o : observers) {
            o.onPostCreated(saved);
        }
        return saved;
    }

    /**
     * Like: Idempotent. If (userId, postId) already exists, no-op.
     * WHY: Double-click like = single like. UNIQUE constraint prevents duplicate.
     */
    public boolean like(long userId, long postId) {
        if (likeRepo.exists(userId, postId)) return false; // already liked
        likeRepo.insert(userId, postId);
        postRepo.incrementLikesCount(postId);
        Post p = postRepo.findById(postId).orElseThrow();
        for (PostEventObserver o : observers) {
            o.onPostLiked(postId, p.getUserId(), userId);
        }
        return true;
    }

    /**
     * Unlike: Idempotent. If not liked, no-op.
     */
    public boolean unlike(long userId, long postId) {
        if (!likeRepo.exists(userId, postId)) return false;
        likeRepo.delete(userId, postId);
        postRepo.decrementLikesCount(postId);
        return true;
    }
}
```

### Fan-out on Write vs Read

```java
/**
 * FAN-OUT ON WRITE (push):
 *   - When user creates post, push to each follower's feed cache.
 *   - Pros: Fast feed read (just read cache). Good for users with few followers.
 *   - Cons: Celebrity with 1M followers = 1M cache writes per post. Expensive.
 *
 * FAN-OUT ON READ (pull):
 *   - When user requests feed, fetch posts from followed users and merge/sort.
 *   - Pros: No write amplification. Good for celebrities.
 *   - Cons: Slower feed read (query + merge).
 *
 * HYBRID (common in production):
 *   - Push for users with < 10K followers.
 *   - Pull for users with > 10K followers (celebrities).
 */
public class HybridFeedService {
    private static final int PUSH_THRESHOLD = 10_000;
    private final FollowRepository followRepo;
    private final FeedCache feedCache;
    private final FeedGenerationStrategy pullStrategy;

    public List<Post> getFeed(long userId, int limit, int offset) {
        List<Post> cached = feedCache.getFeed(userId, limit, offset);
        if (!cached.isEmpty()) return cached;
        return pullStrategy.generateFeed(userId, limit, offset);
    }

    public void onPostCreated(Post post) {
        int followerCount = followRepo.countFollowers(post.getUserId());
        if (followerCount <= PUSH_THRESHOLD) {
            List<Long> followerIds = followRepo.findFollowerIds(post.getUserId());
            for (long fid : followerIds) {
                feedCache.prependToFeed(fid, post);
            }
        }
        // Else: pull-only; no push to cache
    }
}
```

---

## 7. Edge Cases & Tests

| # | Edge Case | Test / Handling |
|---|-----------|-----------------|
| 1 | **Double like (idempotency)** | User likes same post twice. Second like returns false; likes_count unchanged. Assert: likeRepo.count(postId) == 1. |
| 2 | **Unlike when not liked** | User unlikes post they never liked. Return false; no error. Assert: postRepo.getLikesCount(postId) unchanged. |
| 3 | **Follow self** | User tries to follow themselves. CHECK (follower_id != following_id) or application validation. Reject. |
| 4 | **Duplicate follow** | User follows same person twice. UNIQUE(follower_id, following_id) prevents duplicate. Second insert fails; return "already following". |
| 5 | **Feed for user with no follows** | User follows nobody. Feed returns empty list. ChronologicalFeedStrategy handles: if (followingIds.isEmpty()) return List.of(). |
| 6 | **Post visibility FRIENDS_ONLY** | User A posts FRIENDS_ONLY. User B (not follower) requests feed. Post not included. Post.isVisibleTo(B, isFollower=false) returns false. |
| 7 | **Delete post cascades** | Post deleted. Likes, comments, media, notifications with post_id should CASCADE or SET NULL. Assert: likes for postId == 0. |
| 8 | **Pagination offset beyond data** | getFeed(userId, limit=20, offset=1000) when user has 50 posts. Return empty list; no exception. |

---

## 8. Summary

| Aspect | Key Takeaway |
|--------|--------------|
| **Likes table** | Separate table (not counter) for WHO liked, idempotency, unlike, analytics, notifications |
| **Follows table** | Both follower_id and following_id for asymmetric relationship; feed = posts from following_id where follower_id = me |
| **Media table** | Separate for one-post-many-media, reusability, different processing/storage |
| **Feed strategies** | Strategy pattern: Chronological, Ranked, Trending interchangeable |
| **Post events** | Observer: feed update, notification, trending react to create/like/comment |
| **Fan-out** | Push for small followings; pull for celebrities; hybrid at threshold |
| **Like/Unlike** | Idempotent via UNIQUE(user_id, post_id); insert = like, delete = unlike |
| **SOLID** | SRP (each strategy/observer does one thing), OCP (add strategies without change), DIP (depend on abstractions) |
