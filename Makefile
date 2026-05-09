.PHONY: all build android host test clean

ANDROID_BIN := dist/beautiful-dump-android-arm64
HOST_BIN    := dist/beautiful-dump-host

all: android

# Android arm64 静态 ELF (适用于 Termux / 已 root 的 Android shell)
android:
	@mkdir -p dist
	GOOS=linux GOARCH=arm64 CGO_ENABLED=0 \
		go build -trimpath -ldflags="-s -w" -o $(ANDROID_BIN) .
	@file $(ANDROID_BIN)

# 当前主机架构 (调试 / 跑测试用)
host:
	@mkdir -p dist
	go build -o $(HOST_BIN) .

test:
	go test -v ./...

clean:
	rm -rf dist
