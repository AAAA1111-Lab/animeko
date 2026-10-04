/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

/**
 * The single translation unit that compiles dr_flac's implementation.
 *
 * dr_flac is a single-header library: exactly one .cpp must define DR_FLAC_IMPLEMENTATION, every
 * other file only includes the header for declarations. See flac_jni.cpp for the JNI surface and
 * thirdparty/dr_flac.h for the licence (public domain / MIT-0, reproduced in
 * src/androidMain/resources/META-INF/licenses/.../dr_flac.txt).
 *
 * DR_FLAC_NO_STDIO keeps the stdio-based file API out of the binary; Mediamp never decodes FLAC
 * from a path, only from frames handed over by the container.
 */

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO

#include "thirdparty/dr_flac.h"
