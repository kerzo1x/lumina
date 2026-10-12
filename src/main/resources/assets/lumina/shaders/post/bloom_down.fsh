#version 460

layout (binding = 0) uniform BloomUBO {
    // xy: source texel size, z: threshold, w: unused
    vec4 LuminaBlur;
};

layout (binding = 1) uniform sampler2D Sampler0;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

vec3 bright(vec2 uv) {
    vec3 c = textureLod(Sampler0, uv, 0.0).rgb;
    float l = max(max(c.r, c.g), c.b);
    // Soft knee, so only genuinely bright pixels (sky, sunlit surfaces, light sources) feed the glow
    float w = smoothstep(LuminaBlur.z, 1.0, l);
    return c * w * w;
}

void main() {
    vec2 o = LuminaBlur.xy;

    vec3 c = bright(texCoord + vec2(-o.x, -o.y));
    c += bright(texCoord + vec2( o.x, -o.y));
    c += bright(texCoord + vec2(-o.x,  o.y));
    c += bright(texCoord + vec2( o.x,  o.y));

    fragColor = vec4(c * 0.25, 1.0);
}
