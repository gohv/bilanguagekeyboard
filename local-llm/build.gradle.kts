plugins {
    id("com.android.library")
}

android {
    namespace = "helium314.keyboard.localllm"
    compileSdk = 36
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 26
        ndk {
            val targetAbi = providers.gradleProperty("targetAbi").orNull
            abiFilters += targetAbi?.let(::listOf) ?: listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    // Dot-product, int8 matmul and fp16 instructions: ~3-5x faster prompt processing
                    // and ~1.6x faster generation than baseline armv8-a, identical results. Ignored
                    // on x86_64. LocalLlmModelStore refuses phones without these features.
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                )
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        create("nouserlib") {
            initWith(getByName("release"))
        }
        create("runTests") {
            initWith(getByName("debug"))
        }
        create("debugNoMinify") {
            initWith(getByName("debug"))
        }
    }
}
