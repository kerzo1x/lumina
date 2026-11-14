#version 460

// Averages the 4x4 pattern the occlusion pass was dithered with, without bleeding across depth edges.
// The occlusion pass keeps the distance of each texel in blue, so only one texture is read.

layout (binding = 0) uniform sampler2D Sampler0;
// Distance from the camera at full size: no longer read, kept so the pipeline layout stays the same
layout (binding = 1) uniform sampler2D Sampler1;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(Sampler0, 0) - 1;
    float centre = texelFetch(Sampler0, pixel, 0).b;
    float tolerance = 0.25 + centre * 0.04;

    vec2 sum = vec2(0.0);
    float weightSum = 0.0;

    for (int y = -2; y < 2; ++y) {
        for (int x = -2; x < 2; ++x) {
            vec3 texel = texelFetch(Sampler0, clamp(pixel + ivec2(x, y), ivec2(0), last), 0).rgb;
            float weight = step(abs(texel.b - centre), tolerance) + 1.0e-3;

            sum += texel.rg * weight;
            weightSum += weight;
        }
    }

    fragColor = vec4(sum / weightSum, centre, 1.0);
}
