set shell := ["bash", "-c"]

# List available build commands
default:
    @just --list

# Build the standard Android library (.aar)
aar:
    ./gradlew assembleRelease
    @echo "AAR built at: build/outputs/aar/libmlkit-proxy-release.aar"

# Build a fat classes.dex (for LSPosed / ReVanced injection)
dex: aar
    @mkdir -p build/temp build/outputs/dex
    @echo "Extracting classes.jar from AAR..."
    @unzip -q -o build/outputs/aar/libmlkit-proxy-release.aar classes.jar -d build/temp
    @echo "Converting to classes.dex using d8..."
    d8 build/temp/classes.jar --output build/outputs/dex/
    @echo "classes.dex built at: build/outputs/dex/classes.dex"

# Generate smali files (for apktool smali injection)
smali: dex
    @mkdir -p build/outputs/smali
    @echo "Disassembling classes.dex to smali..."
    baksmali d build/outputs/dex/classes.dex -o build/outputs/smali/
    @echo "Smali files generated at: build/outputs/smali/"

# Clean all build outputs
clean:
    ./gradlew clean
    rm -rf build/temp build/outputs