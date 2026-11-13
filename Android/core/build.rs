// Compiles the iPhone app's real-time C (the ring, the renderer, the sound processor) into this library, from where
// it lives. Nothing is copied: both apps build the same files.
use std::path::PathBuf;

fn main() {
    let shared = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../Packages/BoomEngine/Sources/CBoomAudio");
    let shared = shared.canonicalize().expect("the shared C folder (Packages/BoomEngine/Sources/CBoomAudio) is missing");
    let files = ["BoomRing.c", "BoomRenderer.c", "BoomDSP.c", "BoomHRIR.c"];

    let mut build = cc::Build::new();
    build.include(shared.join("include")).include(&shared).std("c11").warnings(true);
    for file in files {
        build.file(shared.join(file));
    }
    // The sound processor's arithmetic is the same on both platforms; release builds are optimised either way.
    build.opt_level(3);
    build.compile("boomaudio");

    println!("cargo:rerun-if-changed={}", shared.join("include").display());
    for file in files.iter().chain(["BoomHRIR.h"].iter()) {
        println!("cargo:rerun-if-changed={}", shared.join(file).display());
    }
    for header in ["BoomDSP.h", "BoomRenderer.h", "BoomRing.h"] {
        println!("cargo:rerun-if-changed={}", shared.join("include").join(header).display());
    }

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=aaudio");
        println!("cargo:rustc-link-lib=log");
    }
}
