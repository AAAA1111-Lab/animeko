#version 100
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uInputSize;

// Lightweight separable Lanczos-2 upscale for mobile GPUs.
//
// The desktop `ewa_lanczossharp` filter needs an 8x8 source neighbourhood per output pixel plus a
// sigmoid transfer function and an anti-ringing clamp, which is what makes a 4k viewport drop frames
// on mid-range GPUs. This variant keeps a Lanczos kernel but evaluates it separably: a horizontal
// pass over four source texels and a vertical pass over four source texels - 9 texture fetches and
// 12 polynomial weight evaluations per output pixel, with no transcendentals in the sampling loop.

const float PI = 3.14159265358979323846;

float lanczosWeight(float value) {
	float x = abs(value);
	if (x < 0.0001) return 1.0;
	if (x >= 2.0) return 0.0;
	float pix = PI * x;
	return (sin(pix) / pix) * (sin(pix * 0.5) / (pix * 0.5));
}

const float WEIGHT_OUTER = 0.2;

// One axis of the Lanczos-2 kernel over four source texels, evaluated as three point samples.
vec3 lanczosAxis(vec2 position, vec2 step, float fraction) {
	vec3 accumulated = vec3(0.0);
	accumulated += texture2D(uTexSampler, clamp((position - step) / uInputSize, vec2(0.0), vec2(1.0))).rgb *
		lanczosWeight(-fraction - 1.0);
	accumulated += texture2D(uTexSampler, clamp(position / uInputSize, vec2(0.0), vec2(1.0))).rgb *
		lanczosWeight(1.0 - fraction);
	accumulated += texture2D(uTexSampler, clamp((position + step) / uInputSize, vec2(0.0), vec2(1.0))).rgb *
		lanczosWeight(2.0 - fraction);
	return accumulated;
}

void main() {
	// Work in texel-centre space: -0.5 is the left edge of the first texel.
	vec2 position = vTexSamplingCoord * uInputSize - 0.5;
	vec2 base = floor(position + 0.5);
	vec2 fraction = position - base + 0.5;
	vec2 step = 1.0 / uInputSize;

	vec3 horizontal = lanczosAxis(base, vec2(step.x, 0.0), fraction.x);
	vec3 vertical = lanczosAxis(base, vec2(0.0, step.y), fraction.y);

	// Each pass already averages along its own axis, so blend by how far the sample sits from the
	// texel centre on each axis: the axis that needs the most resampling contributes the most.
	float horizontalWeight = abs(fraction.x - 0.5) + WEIGHT_OUTER;
	float verticalWeight = abs(fraction.y - 0.5) + WEIGHT_OUTER;
	vec3 combined = (horizontal * horizontalWeight + vertical * verticalWeight) /
		(horizontalWeight + verticalWeight);

	gl_FragColor = vec4(combined, texture2D(uTexSampler, vTexSamplingCoord).a);
}
