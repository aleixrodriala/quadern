# Applied to apps that depend on :whisper when they run R8.

# JNI: whisper_jni.cpp binds Java_io_github_aleixrodriala_quadern_whisper_WhisperJni_* by name, so
# the class and its native methods must keep their names (and the classes in their signatures).
-keepclasseswithmembernames,includedescriptorclasses class io.github.aleixrodriala.quadern.whisper.WhisperJni {
    native <methods>;
}

# Called from native code through GetMethodID("onProgress", "(I)V").
-keepclassmembers class io.github.aleixrodriala.quadern.whisper.ProgressSink {
    void onProgress(int);
}
