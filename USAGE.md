# libmlkit-proxy: Usage & Injection Guide

This guide details how to build `libmlkit-proxy` and inject it into existing Android applications. Because the library uses a `ContentProvider` (`com.libmlkitproxy.ProxyLoader`) for zero-touch library loading and initialization and dynamically hashes the host package name to assign a local port, a single compiled artifact can be injected into multiple apps simultaneously without resource conflicts.

---

## 🛠️ Building the Artifacts

This project uses a fully declarative, Nix-driven build environment. You do not need to install Android Studio, the Android SDK, or Gradle globally if you use Nix.

### Prerequisites

* [Nix](https://nixos.org/download) package manager
* [Just](https://github.com/casey/just) command runner

### Build Commands

Drop into the development shell to automatically fetch the JDK, Gradle, and Android SDK:

```bash
nix-shell
```

#### Generate the required artifacts using just:

Build standard Android Library (.aar)

```bash
just aar
```

**Output: `build/outputs/aar/libmlkit-proxy-release.aar`**

#### Build fat Dalvik Executable (classes.dex)

```bash
just dex
```

**Output: `build/outputs/dex/classes.dex`**

#### Generate Smali source (.smali)

```bash
just smali
```

**Output: `build/outputs/smali/`**

## 💉 Injection Methods

### Method 1: Source Inclusion (Standard Development)

If you are modifying an open-source project and building it from source, use the `.aar` file.

1. Run `just aar`.
2. Copy `host-release.aar` to the target project's `app/libs/` directory and rename it to `libmlkit-proxy.aar`.
3. Add the dependency to the target app's `build.gradle.kts`:

```kotlin
dependencies {
  implementation(files("libs/libmlkit-proxy.aar"))

  ...
}
```

5. Build the app. The `ContentProvider` will self-register and start the proxy server on app launch.

### Method 2: Smali Injection (apktool)

Use this method for static, permanent injection into an existing compiled .apk.

1. Run `just smali` to generate the smali code for the proxy.
2. Decompile the target APK:

```bash
apktool d target_app.apk -o decompiled_app/
```

3. Copy the generated proxy smali files into the decompiled app's source tree:

```bash
cp -r build/outputs/smali/com/libmlkitproxy decompiled_app/smali/com/
```

4. Open `decompiled_app/AndroidManifest.xml` and insert the `AutoInitProvider` inside the `<application>` tag:

```xml
<provider
    android:name="com.libmlkitproxy.AutoInitProvider"
    android:authorities="YOUR_TARGET_APP_PACKAGE_NAME.mlkitproxyinit"
    android:exported="false"
    android:initOrder="199" />
```

(Note: Ensure the INTERNET permission is also present in the manifest).

5. Rebuild and sign the modified APK:

```bash
apktool b decompiled_app -o injected_app.apk
uber-apk-signer -a injected_app.apk
```

### Method 3: LSPosed / Xposed Module

Use this method for dynamic, root-level injection without modifying the target APK signature.

1. Run `just dex` to generate the classes.dex payload.
2. Create a standard LSPosed module project.
3. Place `classes.dex` into your module's `assets/` folder.
4. In your module's `IXposedHookLoadPackage` implementation, hook the target app's application startup (e.g., `Application.attachBaseContext` or `Application.onCreate`).
5. Dynamically load the DEX and instantiate the provider:

```java
XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        Application app = (Application) param.thisObject;
        Context context = app.getApplicationContext();

        // Extract dex from assets, load via DexClassLoader, and invoke the proxy
    }
});
```

### Method 4: ReVanced Patches

Use this method to bundle the proxy as a selectable ReVanced Integration.

1. Run `just dex` to generate `classes.dex`.
2. Rename `classes.dex` to `libmlkit-proxy.dex` and include it in your ReVanced Integrations repository.
3. Write a ReVanced Patch that applies to the target app.
4. In your Patch logic, merge the `libmlkit-proxy.dex` into the target's dex pool using `com.revanced.patcher.patch.DexPatch`.
5. Write an `AndroidManifestPatch` to inject the `<provider>` XML block into the target app's manifest, dynamically replacing the `android:authorities` variable with the target's package name.

## 📡 Verification & Testing

Once injected and running, the host app must be in the foreground for the model to execute.

You will see a Toast message when the app starts indicating the calculated port (e.g., `libmlkit-proxy: Listening on :14320`).

To verify the proxy is routing correctly, send a test cURL request to the device from `adb shell` or Termux:

```bash
curl -X POST http://127.0.0.1:14320/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "What is the capital of California?"}
    ]
  }'
```

If the ML Kit model is downloaded and the app is foregrounded, you will receive a standard OpenAI-formatted JSON response. If the app is backgrounded, you will receive an HTTP 403 Forbidden error.

When an application supports OpenAI-compatible endpoints, use the following parameters to connect to `libmlkit-proxy`:

* **Base URL:** `http://127.0.0.1:<port>/v1` using the port shown in the toast message.
* **Model:** Use any value for the model, as it is ignored and Gemini Nano is used instead. This will change in a future version.
* **API Key:** Use any value for the API key, as it is ignored.
* **Max Output Tokens:** Use any value up to 256, larger values will be clamped to 256 (default 256).
* **Temperature:** Use any value between 0.0 to 1.0 (default 0.0).
