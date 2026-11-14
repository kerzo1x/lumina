#version 460

// Darkens the frame by the blurred occlusion and contact shadow. Drawn with multiplicative blending,
// so the output is the factor the picture is multiplied by.

layout (binding = 0) uniform AoApplyUBO {
    // x: occlusion strength, y: contact shadow strength
    vec4 LuminaAoApply;
};

// Half size
layout (binding = 1) uniform sampler2D Sampler0;
// Distance from the camera, full size
layout (binding = 2) uniform sampler2D Sampler1;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    ivec2 fullSize = textureSize(Sampler1, 0);
    vec2 lowSize = vec2(textureSize(Sampler0, 0));
    float depth = texelFetch(Sampler1, ivec2(texCoord * vec2(fullSize)), 0).r;

    // Depth-aware upscale: of the four half-size texels around this pixel, the ones on another surface (a fence
    // post in front of a wall, the ground behind a tree) lose their weight, so no dark or bright fringe bleeds
    // across silhouettes the way plain bilinear filtering does
    vec2 position = texCoord * lowSize - 0.5;
    vec2 base = floor(position);
    vec2 f = position - base;
    float tolerance = 0.08 + depth * 0.03;

    vec2 sum = vec2(0.0);
    float weights = 0.0;

    for (int i = 0; i < 4; ++i) {
        vec2 offset = vec2(i & 1, i >> 1);
        vec2 texel = clamp(base + offset, vec2(0.0), lowSize - 1.0);

        // Blue holds the distance the occlusion pass worked with for that texel
        vec3 low = texelFetch(Sampler0, ivec2(texel), 0).rgb;
        vec2 bilinear = mix(1.0 - f, f, offset);
        float weight = bilinear.x * bilinear.y / (1.0 + abs(low.b - depth) / tolerance) + 1.0e-4;

        sum += low.rg * weight;
        weights += weight;
    }

    vec2 occlusion = sum / weights;

    // Distant terrain is mostly fog: darkening it would draw dark outlines into the haze
    float near = 1.0 - smoothstep(48.0, 110.0, depth);

    float ambient = mix(1.0, occlusion.r, LuminaAoApply.x * near);
    vec3 contact = mix(vec3(1.0), vec3(0.60, 0.64, 0.76), (1.0 - occlusion.g) * LuminaAoApply.y);

    fragColor = vec4(ambient * contact, 1.0);
}
