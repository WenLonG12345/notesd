package main

import (
	"net"
	"net/url"
	"testing"
)

func TestSetupURL(t *testing.T) {
	tcp := func(ip string, port int) net.Addr { return &net.TCPAddr{IP: net.ParseIP(ip), Port: port} }
	public, _ := url.Parse("https://notes.example.com/")
	under, _ := url.Parse("https://example.com/notes")
	for _, c := range []struct {
		public *url.URL
		addr   net.Addr
		want   string
	}{
		{nil, tcp("127.0.0.1", 8080), "http://127.0.0.1:8080/setup#tok"},
		{nil, tcp("0.0.0.0", 8080), "http://localhost:8080/setup#tok"},
		{nil, tcp("::", 9000), "http://localhost:9000/setup#tok"},
		{nil, tcp("::1", 8080), "http://[::1]:8080/setup#tok"},
		{public, tcp("127.0.0.1", 8080), "https://notes.example.com/setup#tok"},
		{under, tcp("127.0.0.1", 8080), "https://example.com/notes/setup#tok"},
	} {
		if got := setupURL(c.public, c.addr, "tok"); got != c.want {
			t.Errorf("setupURL(%v, %v) = %q, want %q", c.public, c.addr, got, c.want)
		}
	}
}
