#version 100
precision highp float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uInputSize;

const float SHARPNESS = 0.6;

void main() {
    vec2 step = 1.0 / uInputSize;
    vec2 uv = vTexSamplingCoord;

    // Fetch 3x3 neighborhood (cross + corners)
    vec3 a = texture2D(uTexSampler, uv + vec2(-step.x, -step.y)).rgb;
    vec3 b = texture2D(uTexSampler, uv + vec2(    0.0, -step.y)).rgb;
    vec3 c = texture2D(uTexSampler, uv + vec2( step.x, -step.y)).rgb;
    vec3 d = texture2D(uTexSampler, uv + vec2(-step.x,     0.0)).rgb;
    vec3 e = texture2D(uTexSampler, uv).rgb;
    vec3 f = texture2D(uTexSampler, uv + vec2( step.x,     0.0)).rgb;
    vec3 g = texture2D(uTexSampler, uv + vec2(-step.x,  step.y)).rgb;
    vec3 h = texture2D(uTexSampler, uv + vec2(    0.0,  step.y)).rgb;
    vec3 i = texture2D(uTexSampler, uv + vec2( step.x,  step.y)).rgb;

    // Soft min and max of cross (b, d, e, f, h)
    vec3 minRGB = min(min(min(d, e), min(f, b)), h);
    vec3 minRGB2 = min(min(min(minRGB, a), min(c, g)), i);
    minRGB += minRGB2;

    vec3 maxRGB = max(max(max(d, e), max(f, b)), h);
    vec3 maxRGB2 = max(max(max(maxRGB, a), max(c, g)), i);
    maxRGB += maxRGB2;

    // Smooth reciprocal
    vec3 rcpMRGB = 1.0 / max(maxRGB, 0.0001);
    vec3 ampRGB = clamp(min(minRGB, 2.0 - maxRGB) * rcpMRGB, 0.0, 1.0);

    // Shaping amount of sharpening
    ampRGB = inversesqrt(max(ampRGB, 0.0001));
    float peak = -3.0 * SHARPNESS + 8.0;
    vec3 wRGB = -1.0 / (ampRGB * peak);

    vec3 rcpWeightRGB = 1.0 / (1.0 + 4.0 * wRGB);

    // Filter
    vec3 window = (b + d) + (f + h);
    vec3 outRGB = clamp((window * wRGB + e) * rcpWeightRGB, 0.0, 1.0);

    float alpha = texture2D(uTexSampler, uv).a;
    gl_FragColor = vec4(outRGB, alpha);
}
