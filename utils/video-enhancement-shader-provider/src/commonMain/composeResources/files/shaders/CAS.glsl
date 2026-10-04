// AMD FidelityFX Contrast Adaptive Sharpening (CAS) for mpv
// Ported to mpv user shader format
//
// MIT License
// Copyright (c) 2020 Advanced Micro Devices, Inc. All rights reserved.

//!DESC FidelityFX Contrast Adaptive Sharpening (CAS)
//!HOOK MAIN
//!BIND MAIN
//!WIDTH MAIN.w
//!HEIGHT MAIN.h
//!DESC cas

#define SHARPNESS 0.8

vec4 hook() {
    vec2 uv = MAIN_pos;
    vec2 texel = MAIN_pt;

    // Fetch 3x3 neighborhood
    vec3 a = MAIN_texOff(vec2(-1.0, -1.0)).rgb;
    vec3 b = MAIN_texOff(vec2( 0.0, -1.0)).rgb;
    vec3 c = MAIN_texOff(vec2( 1.0, -1.0)).rgb;
    vec3 d = MAIN_texOff(vec2(-1.0,  0.0)).rgb;
    vec3 e = MAIN_texOff(vec2( 0.0,  0.0)).rgb;
    vec3 f = MAIN_texOff(vec2( 1.0,  0.0)).rgb;
    vec3 g = MAIN_texOff(vec2(-1.0,  1.0)).rgb;
    vec3 h = MAIN_texOff(vec2( 0.0,  1.0)).rgb;
    vec3 i = MAIN_texOff(vec2( 1.0,  1.0)).rgb;

    // Soft min and max of cross (b, d, e, f, h)
    vec3 minRGB = min(min(min(d, e), min(f, b)), h);
    vec3 minRGB2 = min(min(min(minRGB, a), min(c, g)), i);
    minRGB += minRGB2;

    vec3 maxRGB = max(max(max(d, e), max(f, b)), h);
    vec3 maxRGB2 = max(max(max(maxRGB, a), max(c, g)), i);
    maxRGB += maxRGB2;

    // Smooth reciprocal
    vec3 rcpMRGB = vec3(1.0) / maxRGB;
    vec3 ampRGB = clamp(min(minRGB, 2.0 - maxRGB) * rcpMRGB, 0.0, 1.0);

    // Shaping amount of sharpening
    ampRGB = inversesqrt(ampRGB);
    float peak = -3.0 * SHARPNESS + 8.0;
    vec3 wRGB = -vec3(1.0) / (ampRGB * peak);

    vec3 rcpWeightRGB = vec3(1.0) / (1.0 + 4.0 * wRGB);

    // Filter
    vec3 window = (b + d) + (f + h);
    vec3 outRGB = clamp((window * wRGB + e) * rcpWeightRGB, 0.0, 1.0);

    float alpha = MAIN_texOff(vec2(0.0, 0.0)).a;
    return vec4(outRGB, alpha);
}
