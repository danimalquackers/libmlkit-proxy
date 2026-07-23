{
  pkgs ? import <nixpkgs> {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  },
  lib ? pkgs.lib,
  stdenv ? pkgs.stdenv,
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

  baksmali = stdenv.mkDerivation rec {
    pname = "baksmali";
    version = "3.0.9";

    src = pkgs.fetchurl {
      url = "https://github.com/baksmali/smali/releases/download/${version}/baksmali-${version}-fat.jar";
      hash = "sha256-r0qBj26b/Koxst4t3ZkRfZlyXXbWZuklfmM9MF0r1NQ=";
    };

    dontUnpack = true;

    nativeBuildInputs = with pkgs; [ makeWrapper ];

    installPhase = ''
      runHook preInstall

      mkdir -p $out/share/java $out/bin
      cp $src $out/share/java/baksmali.jar

      makeWrapper ${pkgs.jre}/bin/java $out/bin/baksmali \
        --add-flags "-jar $out/share/java/baksmali.jar"

      runHook postInstall
    '';

    meta = with lib; {
      description = "smali/baksmali is an assembler/disassembler for the dex format used by dalvik";
      homepage = "https://github.com/baksmali/smali";
      sourceProvenance = with sourceTypes; [ binaryBytecode ];
      platforms = platforms.all;
    };
  };
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    just
    just-lsp

    jdk17
    androidEnv.androidsdk

    apktool
    baksmali
    gradle
    zip
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
