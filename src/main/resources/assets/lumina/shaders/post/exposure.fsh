#version 460

// Second step of the exposure metering, drawn into a single pixel: averages the 16x9 grid (centre weighted, the
// way a camera meters), turns the average brightness into the exposure that would bring it to a mid tone, and
// moves the previous exposure part of the way there. Brightening is slow and darkening quick, like an eye.

layout (binding = 0) uniform ExposureUBO {
    // x: strength (how far towards the metered exposure), y: rate towards a brighter picture this frame,
    // z: rate towards a darker one, w: 1 to take the target at once (first frame, after a pause)
    vec4 LuminaExposure;
};

// Log luminance grid from luminance_down.fsh
layout (binding = 1) uniform sampler2D Sampler0;
// The exposure of the previous frame
layout (binding = 2) uniform sampler2D Sampler1;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

// Average brightness the picture is steered towards: a little below the game's typical daylight frame, so a sunny
// day stays as it is and only dark or glaring scenes are corrected
const float KEY = 0.36;

void main() {
    float sum = 0.0;
    float weights = 0.0;

    for (int y = 0; y < 9; ++y) {
        for (int x = 0; x < 16; ++x) {
            vec2 fromCentre = (vec2(x, y) + 0.5) / vec2(16.0, 9.0) - 0.5;
            float weight = 1.0 - 1.2 * dot(fromCentre, fromCentre);

            sum += texelFetch(Sampler0, ivec2(x, y), 0).r * weight;
            weights += weight;
        }
    }

    float average = exp(sum / weights);
    float target = mix(1.0, clamp(KEY / max(average, 1.0e-3), 0.72, 1.6), LuminaExposure.x);

    float previous = texelFetch(Sampler1, ivec2(0), 0).r;
    float rate = target > previous ? LuminaExposure.y : LuminaExposure.z;
    float exposure = LuminaExposure.w > 0.5 || !(previous > 0.0) ? target : mix(previous, target, rate);

    fragColor = vec4(exposure, 0.0, 0.0, 1.0);
}
