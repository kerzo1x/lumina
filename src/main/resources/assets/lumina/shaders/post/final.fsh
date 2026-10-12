#version 460

layout (binding = 0) uniform FinalUBO {
    // x: saturation, y: contrast, z: brightness, w: vignette
    vec4 LuminaGrade;
    // rgb: white balance tint, a: bloom strength
    vec4 LuminaTint;
    // rgb: godray colour, a: godray strength
    vec4 LuminaRayColor;
    // x: sharpening amount, yz: texel size of the scene
    vec4 LuminaPost;
};

layout (binding = 1) uniform sampler2D Sampler0;
layout (binding = 2) uniform sampler2D Sampler1;
layout (binding = 3) uniform sampler2D Sampler2;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    vec3 color = textureLod(Sampler0, texCoord, 0.0).rgb;

    if (LuminaPost.x > 0.0) {
        // Unsharp mask against the four direct neighbours, limited so hard edges do not ring
        vec2 t = LuminaPost.yz;
        vec3 around = textureLod(Sampler0, texCoord + vec2(t.x, 0.0), 0.0).rgb
                    + textureLod(Sampler0, texCoord - vec2(t.x, 0.0), 0.0).rgb
                    + textureLod(Sampler0, texCoord + vec2(0.0, t.y), 0.0).rgb
                    + textureLod(Sampler0, texCoord - vec2(0.0, t.y), 0.0).rgb;
        vec3 detail = clamp(color - around * 0.25, -0.12, 0.12);
        color = clamp(color + detail * LuminaPost.x * 1.5, 0.0, 1.0);
    }

    // Screen blend keeps the glow from clipping highlights
    vec3 bloom = textureLod(Sampler1, texCoord, 0.0).rgb * LuminaTint.a;
    color = 1.0 - (1.0 - color) * (1.0 - min(bloom, vec3(1.0)));

    vec3 rays = textureLod(Sampler2, texCoord, 0.0).rgb * LuminaRayColor.rgb * LuminaRayColor.a;
    color = 1.0 - (1.0 - color) * (1.0 - min(rays, vec3(1.0)));

    color *= LuminaTint.rgb * LuminaGrade.z;

    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, LuminaGrade.x);

    // Pivot below mid grey: extra contrast should not crush an already dark scene
    color = (color - 0.3) * LuminaGrade.y + 0.3;

    vec2 v = texCoord - 0.5;
    color *= 1.0 - LuminaGrade.w * smoothstep(0.25, 0.9, dot(v, v) * 2.0);

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
