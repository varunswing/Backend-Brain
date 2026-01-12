# Content Delivery Network (CDN): Complete Guide

## Overview
A CDN is a geographically distributed network of servers that delivers content to users from the nearest location, reducing latency and improving performance.

## How CDN Works

```
Without CDN:                    With CDN:
User (India) ───────────────→   User (India) ────→ CDN Edge (Mumbai)
         │                                                │
         │    High Latency                    Cache Hit?  │
         │    (~300ms)                             │      │
         │                                    YES  ↓  NO  │
         ▼                                   Return │     │
Origin Server (US)                          Cached  │     ▼
                                            Content │  Origin Server
                                                    │     │
                                                    ←─────┘
                                                 Cache Response
```

## CDN Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         CDN Network                          │
│                                                              │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐     │
│  │ Edge    │   │ Edge    │   │ Edge    │   │ Edge    │     │
│  │ (Asia)  │   │ (Europe)│   │ (US)    │   │ (S.Am)  │     │
│  └────┬────┘   └────┬────┘   └────┬────┘   └────┬────┘     │
│       │             │             │             │           │
│       └─────────────┴──────┬──────┴─────────────┘           │
│                            │                                 │
│                     ┌──────▼──────┐                         │
│                     │   Origin    │                         │
│                     │   Shield    │                         │
│                     └──────┬──────┘                         │
│                            │                                 │
└────────────────────────────┼────────────────────────────────┘
                             │
                      ┌──────▼──────┐
                      │   Origin    │
                      │   Server    │
                      └─────────────┘
```

## Types of CDN Content

### Static Content
- Images (JPEG, PNG, WebP)
- CSS files
- JavaScript files
- Fonts
- Videos

### Dynamic Content
- API responses (with short TTL)
- Personalized content (with edge computing)
- HTML pages (with cache invalidation)

## Caching Strategies

### Pull CDN (Lazy Loading)
```
1. User requests content
2. CDN checks cache
3. If miss → Fetch from origin
4. Cache and serve
```
**Pros**: Simple setup, automatic caching
**Cons**: First request is slow (cache miss)

### Push CDN (Proactive)
```
1. Origin pushes content to CDN
2. Content cached before requests
3. Users always get cached version
```
**Pros**: No cold starts, predictable performance
**Cons**: More complex, storage costs

## Cache Control Headers

### Cache-Control
```http
# Public cache, max 1 hour
Cache-Control: public, max-age=3600

# Private (browser only), 10 minutes
Cache-Control: private, max-age=600

# No caching
Cache-Control: no-store, no-cache

# Stale while revalidate
Cache-Control: max-age=3600, stale-while-revalidate=86400
```

### Common Headers
```http
# Static assets (long cache)
Cache-Control: public, max-age=31536000, immutable
ETag: "abc123"

# API responses (short cache)
Cache-Control: public, max-age=60, s-maxage=300
Vary: Accept-Encoding, Authorization

# HTML pages
Cache-Control: public, max-age=0, must-revalidate
```

## Cache Invalidation

### Purge by URL
```bash
# Invalidate specific URL
curl -X PURGE https://cdn.example.com/images/logo.png
```

### Purge by Tag
```bash
# Invalidate all content with tag
curl -X POST \
  -H "X-Purge-Tag: product-images" \
  https://api.cdn.com/purge
```

### Versioned URLs (Best Practice)
```html
<!-- Instead of invalidation, version your assets -->
<script src="/js/app.v2.3.1.js"></script>
<link href="/css/styles.abc123.css">
```

## CDN Configuration Examples

### Nginx (Edge Server)
```nginx
proxy_cache_path /var/cache/nginx levels=1:2 
                 keys_zone=cdn_cache:100m 
                 max_size=10g 
                 inactive=60m;

server {
    location /static/ {
        proxy_cache cdn_cache;
        proxy_cache_valid 200 1d;
        proxy_cache_valid 404 1m;
        proxy_cache_use_stale error timeout updating;
        
        add_header X-Cache-Status $upstream_cache_status;
        
        proxy_pass http://origin_server;
    }
}
```

### CloudFront (AWS)
```yaml
# CloudFormation
Distribution:
  Type: AWS::CloudFront::Distribution
  Properties:
    DistributionConfig:
      Origins:
        - DomainName: origin.example.com
          Id: myOrigin
          CustomOriginConfig:
            HTTPPort: 80
            OriginProtocolPolicy: https-only
      
      DefaultCacheBehavior:
        TargetOriginId: myOrigin
        ViewerProtocolPolicy: redirect-to-https
        CachePolicyId: !Ref CachePolicy
        
      CacheBehaviors:
        - PathPattern: /api/*
          CachePolicyId: !Ref ApiCachePolicy
          TTL: 60
        
        - PathPattern: /static/*
          CachePolicyId: !Ref StaticCachePolicy
          TTL: 31536000
```

## Edge Computing

### Use Cases
- A/B testing at edge
- Personalization
- Security (WAF)
- Request routing
- Image optimization

### CloudFlare Workers Example
```javascript
addEventListener('fetch', event => {
    event.respondWith(handleRequest(event.request));
});

async function handleRequest(request) {
    const url = new URL(request.url);
    
    // A/B testing at edge
    const variant = Math.random() < 0.5 ? 'A' : 'B';
    url.pathname = `/variant-${variant}${url.pathname}`;
    
    // Modify request
    const modifiedRequest = new Request(url, request);
    
    const response = await fetch(modifiedRequest);
    
    // Add custom headers
    const newResponse = new Response(response.body, response);
    newResponse.headers.set('X-Variant', variant);
    
    return newResponse;
}
```

## CDN Security

### DDoS Protection
- Traffic scrubbing
- Rate limiting at edge
- Geographic blocking
- Bot detection

### SSL/TLS
```
Client → HTTPS → CDN Edge → HTTPS → Origin
              (SSL termination)
```

### Origin Protection
```nginx
# Only allow CDN IPs
allow 103.21.244.0/22;  # CDN IP range
allow 103.22.200.0/22;
deny all;
```

## Performance Optimization

### Image Optimization
```
Original: /images/hero.jpg (2MB)
CDN transforms:
  - /images/hero.jpg?w=800&q=80 (optimized)
  - /images/hero.jpg?format=webp (modern format)
  - /images/hero.jpg?w=400&dpr=2 (responsive)
```

### Compression
```http
Accept-Encoding: gzip, br
Content-Encoding: br  # Brotli compression
```

### HTTP/2 & HTTP/3
- Multiplexing
- Header compression
- Server push
- QUIC (HTTP/3)

## Monitoring Metrics

| Metric | Description | Target |
|--------|-------------|--------|
| **Cache Hit Ratio** | % of requests served from cache | > 90% |
| **Bandwidth** | Data transferred | Monitor trends |
| **Latency** | Time to first byte | < 100ms |
| **Error Rate** | 4xx/5xx responses | < 1% |
| **Origin Load** | Requests to origin | Minimize |

## Popular CDN Providers

| Provider | Strengths |
|----------|-----------|
| **CloudFlare** | Security, Workers, free tier |
| **AWS CloudFront** | AWS integration, Lambda@Edge |
| **Akamai** | Enterprise, largest network |
| **Fastly** | Real-time purge, edge computing |
| **Google Cloud CDN** | GCP integration |
| **Azure CDN** | Microsoft integration |

## Best Practices

### Caching Strategy
1. Use versioned URLs for static assets
2. Set appropriate TTLs
3. Use stale-while-revalidate
4. Implement cache tags for invalidation

### Performance
1. Enable compression (Brotli/Gzip)
2. Use modern image formats (WebP, AVIF)
3. Implement responsive images
4. Enable HTTP/2 or HTTP/3

### Security
1. Always use HTTPS
2. Enable WAF rules
3. Protect origin server
4. Implement rate limiting

## Interview Questions

**Q: How does a CDN improve performance?**
> Geographic proximity (reduced latency), caching (reduced origin load), optimizations (compression, HTTP/2), and DDoS protection.

**Q: Cache invalidation strategies?**
> 1) Purge by URL, 2) Purge by tag, 3) Versioned URLs (preferred), 4) TTL-based expiration. Versioned URLs avoid cache invalidation complexity.

**Q: Pull vs Push CDN?**
> Pull: CDN fetches on demand (simpler, cold start issue). Push: Content pushed proactively (no cold starts, more complex). Most use Pull with pre-warming for critical content.

## Quick Reference

| Content Type | TTL | Cache-Control |
|--------------|-----|---------------|
| Static assets (JS/CSS) | 1 year | `public, max-age=31536000, immutable` |
| Images | 1 year | `public, max-age=31536000` |
| HTML | 0 or short | `public, max-age=0, must-revalidate` |
| API responses | 1-5 min | `public, max-age=60, s-maxage=300` |
| User-specific | none | `private, no-store` |
