#version 460

layout (binding = 0) uniform GodraysUBO {
    // xy: sun position in uv space, z: intensity, w: aspect ratio
    vec4 LuminaSunScreen;
};

layout (binding = 1) uniform sampler2D Sampler1;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

const int STEPS = 20;

float hash(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec2 sun = LuminaSunScreen.xy;
    vec2 delta = (sun - texCoord) / float(STEPS);

    float jitter = hash(gl_FragCoord.xy);
    vec2 uv = texCoord + delta * jitter;

    // Only open sky lets light through, so the result is zero wherever the sun is hidden by terrain
    float sum = 0.0;
    for (int i = 0; i < STEPS; ++i) {
        sum += step(1.0, textureLod(Sampler1, clamp(uv, 0.001, 0.999), 0.0).r);
        uv += delta;
    }
    sum /= float(STEPS);

    vec2 toSun = (sun - texCoord) * vec2(LuminaSunScreen.w, 1.0);
    float falloff = exp(-dot(toSun, toSun) * 3.5);

    fragColor = vec4(vec3(sum * falloff * LuminaSunScreen.z), 1.0);
}
