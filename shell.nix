{
  pkgs ? import <nixpkgs> {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  },
}:

let
  androidEnv = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    abiVersions = [
      "x86"
      "arm64-v8a"
    ];
  };
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    just
    just-lsp

    jdk17
    androidEnv.androidsdk

    apktool
    # smali
    gradle
    unzip
    ktlint
  ];

  shellHook = ''
    export ANDROID_HOME="${androidEnv.androidsdk}/libexec/android-sdk"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export JAVA_HOME="${pkgs.jdk17.home}"

    # Add build-tools to PATH so Just can easily call d8
    export PATH="$ANDROID_HOME/build-tools/34.0.0:$PATH"

    echo "libmlkit-proxy dev environment loaded."
    echo "Run 'just' to see available commands."
  '';
}
