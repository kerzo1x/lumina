#version 460

// Darkens the frame by the blurred occlusion and contact shadow. Drawn with multiplicative blending,
// so the output is the factor the picture is multiplied by.

layout (binding = 0) uniform AoApplyUBO {
    // x: occlusion strength, y: contact shadow strength
    vec4 LuminaAoApply;
};

layout (binding = 1) uniform sampler2D Sampler0;
// Distance from the camera
layout (binding = 2) uniform sampler2D Sampler1;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    vec2 occlusion = textureLod(Sampler0, texCoord, 0.0).rg;
    float depth = textureLod(Sampler1, texCoord, 0.0).r;

    // Distant terrain is mostly fog: darkening it would draw dark outlines into the haze
    float near = 1.0 - smoothstep(48.0, 110.0, depth);

    float ambient = mix(1.0, occlusion.r, LuminaAoApply.x * near);
    vec3 contact = mix(vec3(1.0), vec3(0.60, 0.64, 0.76), (1.0 - occlusion.g) * LuminaAoApply.y);

    fragColor = vec4(ambient * contact, 1.0);
}
