#version 460

layout (binding = 0) uniform UniformBufferObject {
    mat4 MVP;
};

#include "terrain_common.glsl"

layout (binding = 2) uniform UBO2 {
    ivec4 SectionOffsets[128];
    vec4 SectionFadeFactors[128];
};

layout (push_constant) uniform pushConstant {
    vec3 ModelOffset;
};

layout (binding = 5) uniform sampler2D Sampler2;

layout (location = 0) in ivec4 Position;
layout (location = 1) in uvec2 UV0;
layout (location = 2) in uint PackedColor;

layout (location = 0) out vec4 vertexColor;
layout (location = 1) out vec2 texCoord0;
layout (location = 2) out float sphericalVertexDistance;
layout (location = 3) out float cylindricalVertexDistance;
layout (location = 4) out flat float fadeFactor;
layout (location = 5) out vec3 lightColor;
layout (location = 6) out float skyLevel;
layout (location = 7) out vec3 relPos;
// 0 plain, 1 leaves, 2 plant, 3 water, 4 glossy (ice, stained glass), 8 lava. See BlockFlags.java
layout (location = 8) out flat float blockKind;

#include "waving.glsl"

const float UV_INV = 1.0 / 32768.0;
const vec3 POSITION_INV = vec3(1.0 / 2048.0);

vec3 getVertexPosition() {
    const int encOffset = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
    const vec3 baseOffset = bitfieldExtract(ivec3(encOffset) >> ivec3(0, 16, 8), 0, 8);

    return fma(Position.xyz, POSITION_INV, ModelOffset + baseOffset);
}

void main() {
    // Packed light: bits 0-3 block flags, 4-7 block light, 8-15 sky light with 16 units per level
    const uint light = uint(Position.a);

    vec3 pos = getVertexPosition();
    pos += windOffset(light & 15u, pos);

    gl_Position = MVP * vec4(pos, 1.0);
    relPos = pos;

    // Kinds that sway keep their weight in the upper two bits, the others use those bits for more kinds
    blockKind = (light & 3u) == 0u ? float(light & 15u) : float(light & 3u);

    sphericalVertexDistance = length(pos);
    cylindricalVertexDistance = max(length(pos.xz), abs(pos.y));

    const ivec2 lm = ivec2(bitfieldExtract(light, 4, 4), bitfieldExtract(light, 12, 4));

    lightColor = texelFetch(Sampler2, lm, 0).rgb;

    // Torches and lava glow warmer where they, not the sky, are what lights the block
    float blockLight = float(lm.x) / 15.0;
    float skyLight = float(lm.y) / 15.0;
    float warm = blockLight * (1.0 - skyLight * 0.8) * LuminaParams.x;
    lightColor *= mix(vec3(1.0), vec3(1.10, 0.98, 0.80), warm);
    skyLevel = float(bitfieldExtract(light, 8, 8)) / 240.0;

    vertexColor = unpackUnorm4x8(PackedColor);
    fadeFactor = SectionFadeFactors[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
    texCoord0 = UV0 * UV_INV;
}
