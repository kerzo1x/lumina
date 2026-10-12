#version 460

// Averages the 4x4 pattern the occlusion pass was dithered with, without bleeding across depth edges.

layout (binding = 0) uniform sampler2D Sampler0;
// Distance from the camera
layout (binding = 1) uniform sampler2D Sampler1;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    float centre = textureLod(Sampler1, texCoord, 0.0).r;
    float tolerance = 0.25 + centre * 0.04;

    vec2 sum = vec2(0.0);
    float weightSum = 0.0;

    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            vec2 uv = texCoord + (vec2(x, y) - 1.5) * texel;
            float weight = step(abs(textureLod(Sampler1, uv, 0.0).r - centre), tolerance) + 1.0e-3;

            sum += textureLod(Sampler0, uv, 0.0).rg * weight;
            weightSum += weight;
        }
    }

    fragColor = vec4(sum / weightSum, 0.0, 1.0);
}
