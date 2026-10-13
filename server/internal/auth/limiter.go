package auth

import (
	"sync"
	"time"
)

// Limiter throttles failed login and setup attempts per client key (usually
// the remote IP; behind a reverse proxy all clients share one key, which for
// a single-owner server is an acceptable global limit).
type Limiter struct {
	mu     sync.Mutex
	now    func() time.Time
	max    int
	window time.Duration
	hits   map[string][]time.Time
}

// NewLimiter allows max failures per key within window.
func NewLimiter(max int, window time.Duration) *Limiter {
	return &Limiter{now: time.Now, max: max, window: window, hits: map[string][]time.Time{}}
}

// Allow reports whether key may attempt now and, if not, how long to wait.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	h := l.prune(key, now)
	if len(h) < l.max {
		return true, 0
	}
	return false, h[0].Add(l.window).Sub(now)
}

// Fail records a failed attempt.
func (l *Limiter) Fail(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.hits[key] = append(l.prune(key, now), now)
	if len(l.hits) > 10_000 {
		for k := range l.hits {
			if len(l.prune(k, now)) == 0 {
				delete(l.hits, k)
			}
		}
	}
}

// Reset clears a key after a successful attempt.
func (l *Limiter) Reset(key string) {
	l.mu.Lock()
	delete(l.hits, key)
	l.mu.Unlock()
}

func (l *Limiter) prune(key string, now time.Time) []time.Time {
	h := l.hits[key]
	i := 0
	for i < len(h) && now.Sub(h[i]) >= l.window {
		i++
	}
	h = h[i:]
	if len(h) == 0 {
		delete(l.hits, key)
		return nil
	}
	l.hits[key] = h
	return h
}
