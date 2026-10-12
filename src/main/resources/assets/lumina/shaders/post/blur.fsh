#version 460

layout (binding = 0) uniform BloomUBO {
    // xy: step between taps (direction * texel size)
    vec4 LuminaBlur;
};

layout (binding = 1) uniform sampler2D Sampler0;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    // 9-tap gaussian folded into 5 bilinear fetches
    vec2 d = LuminaBlur.xy;

    vec3 c = textureLod(Sampler0, texCoord, 0.0).rgb * 0.2270270270;
    c += textureLod(Sampler0, texCoord + d * 1.3846153846, 0.0).rgb * 0.3162162162;
    c += textureLod(Sampler0, texCoord - d * 1.3846153846, 0.0).rgb * 0.3162162162;
    c += textureLod(Sampler0, texCoord + d * 3.2307692308, 0.0).rgb * 0.0702702703;
    c += textureLod(Sampler0, texCoord - d * 3.2307692308, 0.0).rgb * 0.0702702703;

    fragColor = vec4(c, 1.0);
}
