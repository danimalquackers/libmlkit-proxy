set shell := ["bash", "-c"]

# Configuration
BUILD_DIR := "build"
DIST_DIR := "dist"
PKG_NAME := "libmlkit-proxy"

# List available build commands
default:
    @just --list

# Build all
all: apk aar dex smali

# Build just the proxy APK
apk: clean
    @mkdir -p {{DIST_DIR}}

    ./gradlew :proxy:assembleRelease

    @cp proxy/{{BUILD_DIR}}/outputs/apk/release/proxy-release-unsigned.apk {{DIST_DIR}}/{{PKG_NAME}}.apk
    @echo "APK built at: {{DIST_DIR}}/{{PKG_NAME}}.apk"

# Build the standard Android library (.aar)
aar: apk
    @mkdir -p {{DIST_DIR}}

    ./gradlew :host:assembleRelease

    @cp host/{{BUILD_DIR}}/outputs/aar/host-release.aar {{DIST_DIR}}/{{PKG_NAME}}.aar
    @echo "AAR built at: {{DIST_DIR}}/{{PKG_NAME}}.aar"

# Build a fat classes.dex (for LSPosed / ReVanced injection)
dex: aar
    @mkdir -p {{BUILD_DIR}}/temp {{BUILD_DIR}}/outputs/dex

    @echo "Extracting classes.jar from AAR..."
    @unzip -q -o host/{{BUILD_DIR}}/outputs/aar/host-release.aar classes.jar -d {{BUILD_DIR}}/temp

    @echo "Converting to classes.dex using d8..."
    d8 {{BUILD_DIR}}/temp/classes.jar --output {{BUILD_DIR}}/outputs/dex/

    @cp {{BUILD_DIR}}/outputs/dex/classes.dex {{DIST_DIR}}/{{PKG_NAME}}.dex
    @echo "classes.dex built at: {{DIST_DIR}}/{{PKG_NAME}}.dex"

# Generate smali (for apktool smali injection)
smali: dex
    @mkdir -p {{BUILD_DIR}}/temp {{BUILD_DIR}}/outputs/smali

    @echo "Disassembling classes.dex to smali..."
    baksmali d {{BUILD_DIR}}/outputs/dex/classes.dex -o {{BUILD_DIR}}/temp/

    @zip -r {{BUILD_DIR}}/outputs/smali.zip {{BUILD_DIR}}/temp/*
    @cp {{BUILD_DIR}}/outputs/smali.zip {{DIST_DIR}}/{{PKG_NAME}}_smali.zip
    @echo "Smali files generated at: {{DIST_DIR}}/{{PKG_NAME}}_smali.zip"

# Clean all build outputs
clean:
    ./gradlew clean
    rm -rf {{BUILD_DIR}}/temp {{BUILD_DIR}}/outputs {{DIST_DIR}}
