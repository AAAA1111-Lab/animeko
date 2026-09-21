#version 100
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uInputSize2;
uniform vec2 uPassDirection;

// One pass of a separable Catmull-Rom upscale (horizontal when uPassDirection is (1,0), vertical
// when it is (0,1)). Run it twice, once per axis: the first pass renders the source into an
// intermediate target at the upscaled size, the second pass renders that into the output.
//
// Compared to the desktop `ewa_lanczossharp` emulation this replaces:
//  * no sigmoid transfer function and no anti-ringing clamp, which dominated the per-pixel cost;
//  * four taps per axis instead of an 8x8 neighbourhood: 16 texture fetches per output pixel across
//    the two passes, against roughly 64 before.
//
// It is a real reconstruction filter with +/-2 source texel support, so a 2x upscale does not alias
// the way a plain bilinear stretch does. Catmull-Rom's four taps at offsets -1, 0, 1, 2 sum to
// exactly one, which keeps a constant image constant; its overshoot at a step edge is the standard
// Catmull-Rom amount (about 1/4 of a step).

float catmullRomWeight(float value) {
	float x = abs(value);
	if (x < 1.0) {
		return ((1.5 * x - 2.5) * x) * x + 1.0;
	}
	if (x < 2.0) {
		return (((2.5 - 0.5 * x) * x) - 4.0) * x + 2.0;
	}
	return 0.0;
}

void main() {
	vec2 axis = uPassDirection / uInputSize2;
	vec2 sourcePosition = vTexSamplingCoord * uInputSize2 - 0.5;
	float base = floor(dot(sourcePosition + 0.5, uPassDirection) + 0.5);
	float fraction = dot(sourcePosition + 0.5, uPassDirection) - base;
	float textureSize = dot(uPassDirection, uInputSize2) - 1.0;

	vec3 accumulated = vec3(0.0);
	float weightSum = 0.0;
	for (int tap = -1; tap <= 2; tap++) {
		float position = base + float(tap) - fraction;
		if (position < 0.0 || position > textureSize) continue;
		float weight = catmullRomWeight(float(tap) - fraction);
		vec2 coordinate = clamp(vTexSamplingCoord + axis * (base + float(tap)), vec2(0.0), vec2(1.0));
		accumulated += texture2D(uTexSampler, coordinate).rgb * weight;
		weightSum += weight;
	}

	gl_FragColor = vec4(accumulated / max(weightSum, 0.00001), texture2D(uTexSampler, vTexSamplingCoord).a);
}
