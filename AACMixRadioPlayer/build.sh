#!/bin/bash

# AAC Mix Radio Player Build Script
# Usage: ./build.sh [debug|release|bundle|clean]
# Works on Linux, macOS, and Windows (Git Bash/WSL)

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== AAC Mix Radio Player Build Script ===${NC}"

# Try to find Java on Windows if JAVA_HOME not set
if [ -z "$JAVA_HOME" ]; then
    # Check common Windows Java locations (Git Bash)
    for jdk_path in \
        "/c/Program Files/Java/jdk-17"* \
        "/c/Program Files/Eclipse Adoptium/jdk-17"* \
        "/c/Program Files/Microsoft/jdk-17"* \
        "/c/Program Files/Android/Android Studio/jbr" \
        "$LOCALAPPDATA/Programs/Eclipse Adoptium/jdk-17"*; do
        if [ -d "$jdk_path" ]; then
            export JAVA_HOME="$jdk_path"
            export PATH="$JAVA_HOME/bin:$PATH"
            echo -e "${GREEN}Found Java at: $JAVA_HOME${NC}"
            break
        fi
    done
fi

# Check for Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java is not installed or not in PATH.${NC}"
    echo ""
    echo "For Git Bash on Windows, set JAVA_HOME:"
    echo "  export JAVA_HOME=\"/c/Program Files/Android/Android Studio/jbr\""
    echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\""
    echo ""
    echo "For Linux/macOS, install JDK 17:"
    echo "  Ubuntu/Debian: sudo apt install openjdk-17-jdk"
    echo "  Fedora:        sudo dnf install java-17-openjdk-devel"
    echo "  macOS:         brew install openjdk@17"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
echo -e "${GREEN}✓ Java $JAVA_VERSION found${NC}"

# Check for ANDROID_HOME or ANDROID_SDK_ROOT
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    # Try common Windows Android SDK locations
    for sdk_path in \
        "$LOCALAPPDATA/Android/Sdk" \
        "/c/Users/$USER/AppData/Local/Android/Sdk" \
        "$HOME/Android/Sdk"; do
        if [ -d "$sdk_path" ]; then
            export ANDROID_HOME="$sdk_path"
            echo -e "${GREEN}Found Android SDK at: $ANDROID_HOME${NC}"
            break
        fi
    done
fi

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo -e "${YELLOW}Warning: ANDROID_HOME not set. Build may fail.${NC}"
fi

# Make gradlew executable
chmod +x ./gradlew

echo -e "${GREEN}✓ Prerequisites check passed${NC}"
echo ""

# Parse command
CMD=${1:-debug}

case $CMD in
    debug)
        echo -e "${YELLOW}Building debug APK...${NC}"
        ./gradlew assembleDebug
        echo -e "${GREEN}✓ Debug APK: app/build/outputs/apk/debug/app-debug.apk${NC}"
        ;;
    release)
        echo -e "${YELLOW}Building release APK...${NC}"
        if [ ! -f "keystore.properties" ] && [ -z "$STOREFILE" ]; then
            echo -e "${RED}Error: No keystore configured.${NC}"
            echo "Either create keystore.properties from template or set environment variables:"
            echo "  cp keystore.properties.template keystore.properties"
            echo "  # Edit keystore.properties with your values"
            echo ""
            echo "Or use environment variables:"
            echo "  export STOREFILE=/path/to/keystore.jks"
            echo "  export STOREPASSWORD=your_password"
            echo "  export KEYALIAS=your_alias"
            echo "  export KEYPASSWORD=your_key_password"
            exit 1
        fi
        ./gradlew assembleRelease
        echo -e "${GREEN}✓ Release APK: app/build/outputs/apk/release/${NC}"
        ls -la app/build/outputs/apk/release/*.apk 2>/dev/null || true
        ;;
    bundle)
        echo -e "${YELLOW}Building release APK and AAB bundle...${NC}"
        if [ ! -f "keystore.properties" ] && [ -z "$STOREFILE" ]; then
            echo -e "${RED}Error: No keystore configured. See 'release' command for setup.${NC}"
            exit 1
        fi
        ./gradlew assembleRelease bundleRelease
        echo -e "${GREEN}✓ Release APK: app/build/outputs/apk/release/${NC}"
        echo -e "${GREEN}✓ Release AAB: app/build/outputs/bundle/release/app-release.aab${NC}"
        echo -e "${GREEN}✓ Mapping file: app/build/outputs/mapping/release/mapping.txt${NC}"
        ;;
    clean)
        echo -e "${YELLOW}Cleaning build...${NC}"
        ./gradlew clean
        echo -e "${GREEN}✓ Clean complete${NC}"
        ;;
    *)
        echo "Usage: ./build.sh [debug|release|bundle|clean]"
        echo ""
        echo "Commands:"
        echo "  debug   - Build debug APK (default, no signing required)"
        echo "  release - Build signed release APK"
        echo "  bundle  - Build signed release APK and AAB for Play Store"
        echo "  clean   - Clean build files"
        exit 1
        ;;
esac

echo -e "${GREEN}=== Build Complete ===${NC}"
