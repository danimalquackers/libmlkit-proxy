# Keep the ContentProvider intact so it auto-initializes
-keep class com.libmlkitproxy.AutoInitProvider {
    public <init>();
}

# Keep the NanoHTTPD server and its background worker mechanisms
-keep class fi.iki.elonen.NanoHTTPD** { *; }

# Keep standard org.json classes if they are modified or shrunk by R8
-keep class org.json.** { *; }

# Prevent obfuscation of ML Kit GenAI API wrappers used by the proxy
-keep class com.google.mlkit.genai.** { *; }