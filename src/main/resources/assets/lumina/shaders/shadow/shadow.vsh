#version 460

layout (binding = 0) uniform UniformBufferObject {
    mat4 LuminaShadowMat;
};

layout (binding = 1) uniform ShadowParams {
    vec4 LuminaCamera;
    vec4 LuminaWave;
};

#include "waving.glsl"

layout (binding = 2) uniform UBO2 {
    ivec4 SectionOffsets[128];
    vec4 SectionFadeFactors[128];
};

layout (push_constant) uniform pushConstant {
    vec3 ModelOffset;
};

layout (location = 0) in ivec4 Position;
layout (location = 1) in uvec2 UV0;
layout (location = 2) in uint PackedColor;

layout (location = 0) out vec2 texCoord0;

const float UV_INV = 1.0 / 32768.0;
const vec3 POSITION_INV = vec3(1.0 / 2048.0);

void main() {
    const int encOffset = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
    const vec3 baseOffset = bitfieldExtract(ivec3(encOffset) >> ivec3(0, 16, 8), 0, 8);
    vec3 pos = fma(Position.xyz, POSITION_INV, ModelOffset + baseOffset);

    // Foliage must sway in its shadow exactly as it does on screen
    pos += windOffset(uint(Position.a) & 15u, pos);

    vec4 clip = LuminaShadowMat * vec4(pos, 1.0);
    // Casters outside the depth range are flattened onto its limits instead of being clipped away
    clip.z = clamp(clip.z, 0.0, 1.0);

    gl_Position = clip;
    texCoord0 = UV0 * UV_INV;
}
