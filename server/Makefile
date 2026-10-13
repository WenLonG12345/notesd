VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS := -s -w -X main.version=$(VERSION)
export CGO_ENABLED := 0

.PHONY: build test release clean

build:
	go build -trimpath -ldflags '$(LDFLAGS)' -o bin/notes-server ./cmd/notes-server

test:
	CGO_ENABLED=1 go test -race ./...

release:
	GOOS=linux GOARCH=amd64 go build -trimpath -ldflags '$(LDFLAGS)' -o bin/notes-server-linux-amd64 ./cmd/notes-server
	GOOS=linux GOARCH=arm64 go build -trimpath -ldflags '$(LDFLAGS)' -o bin/notes-server-linux-arm64 ./cmd/notes-server

clean:
	rm -rf bin
